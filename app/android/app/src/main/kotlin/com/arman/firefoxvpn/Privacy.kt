package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec

/**
 * The person's own privacy switches, and the checks that prove them.
 *
 * Two values, both read by the library when it assembles the core's config: a flag
 * (`privacy`) and the id of the server to exit through (`chain_id`). They live in the
 * same prefs file the row store uses, because that is the one file the library already
 * reads - so no new native call was needed, and no server address crosses into Kotlin
 * on the way.
 *
 * The checks are the part that matters. A privacy setting nobody can measure is a
 * placebo: each test here asks the network something the answer cannot be faked by
 * the config text - what IP the traffic really leaves with, and whether a plain HTTP
 * request still gets through.
 */
object Privacy {

    private fun prefs(context: Context) =
        context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)

    fun on(context: Context): Boolean = prefs(context).getInt("privacy", 0) == 1

    fun chainId(context: Context): String = prefs(context).getString("chain_id", "") ?: ""

    /** The chosen second hop, or "" for one hop. Turning the switch off keeps the choice. */
    fun set(context: Context, enabled: Boolean, id: String) {
        val was = on(context)
        val wasChain = chainId(context)
        prefs(context).edit()
            .putInt("privacy", if (enabled) 1 else 0)
            .putString("chain_id", id)
            .apply()
        if (was != enabled) CoreService.log(if (enabled) "privacy: on" else "privacy: off")
        if (wasChain != id) CoreService.log("privacy: second hop changed")
    }

    /**
     * What the two layers are actually doing right now, as verdicts - never as addresses.
     * The lines are the point: "it is on" is not evidence, "the traffic left through the
     * second server" is.
     */
    fun test(context: Context): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val hard = on(context)
        out["hardening"] = hard
        val id = chainId(context)
        out["chainChosen"] = id.isNotEmpty()
        val live = CoreService.on
        out["connected"] = live
        if (!live) {
            // nothing below can be measured without a tunnel, and saying "no second server
            // chosen" when the switch was simply off is how a correct setup looks broken
            out["chainInStore"] = id.isNotEmpty()
            CoreService.log("privacy: not connected - nothing to measure yet")
            return out
        }

        // 1. where the traffic really exits - asked of the tunnel, not of a core beside it
        val seen = runCatching { Flags.tunnelExit() }.getOrDefault(mapOf("ip" to "", "answered" to "0"))
        val exitIp = seen["ip"].orEmpty()
        out["exitKnown"] = exitIp.isNotEmpty()
        if (exitIp.isEmpty()) {
            out["tunnelSilent"] = true
            CoreService.log("privacy: the tunnel did not answer the page")
        }

        val row = if (id.isNotEmpty()) runCatching { NativeSec.rowOf(id) }.getOrDefault(-1) else -1
        out["chainInStore"] = row >= 0
        // the chain is measured on its own: it is a separate choice from the name-hiding
        // rules, and a test that only ran while those were on could never say whether the
        // second hop was carrying
        if (id.isEmpty()) {
            out["chainCarries"] = false
            CoreService.log("chain: no second server chosen")
        } else if (row < 0) {
            out["chainCarries"] = false
            CoreService.log("chain: the second server is no longer in the list")
        } else if (exitIp.isNotEmpty()) {
            // the exit must be the SECOND server's own address: the first one only carries
            val hopIp = runCatching {
                NativeSec.locate(row).split(';').getOrNull(0).orEmpty().trim()
            }.getOrDefault("")
            val carried = hopIp.isNotEmpty() && exitIp == hopIp
            out["chainCarries"] = carried
            CoreService.log(
                if (carried) "chain: the traffic leaves through the second server"
                else "chain: the first server is still the exit",
            )
        }

        // 2. is plain HTTP refused? asked through the tunnel, so the answer is about the
        // rule the app set and not about what the carrier does with port 80
        if (hard) {
            val open = runCatching { Flags.tunnelHttpReaches() }.getOrDefault(false)
            out["httpBlocked"] = !open
            CoreService.log(
                if (!open) "privacy: plain http is refused by the tunnel"
                else "privacy: plain http still gets through the tunnel",
            )
        }
        return out
    }

    /** A name for the settings line: the second hop as the list shows it, never its address. */
    fun chainName(context: Context): String {
        val id = chainId(context)
        if (id.isEmpty()) return ""
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return ""
        return runCatching { NativeSec.payloadText(row, 1) }.getOrDefault("")
    }
}
