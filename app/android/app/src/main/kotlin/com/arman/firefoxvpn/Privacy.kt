package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

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
        // Nothing can be measured with no tunnel: a direct read would answer the carrier's
        // own address and the chain test would call that "not carrying".
        val live = CoreService.on
        out["connected"] = live

        // 1. where the traffic really exits, measured through the tunnel
        val mine = if (live) runCatching { Flags.mine(context, true) }.getOrNull() else null
        val exitIp = (mine?.get("ip") ?: "").toString()
        out["exitKnown"] = exitIp.isNotEmpty()

        if (live && hard && id.isNotEmpty()) {
            val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
            out["chainInStore"] = row >= 0
            if (row >= 0 && exitIp.isNotEmpty()) {
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
        } else if (hard) {
            out["chainCarries"] = false
            CoreService.log("chain: no second server chosen")
        }

        // 2. is plain HTTP refused? asked of an address, so the test leaks no name of its own
        if (live && hard) {
            val open = reachesPlainHttp()
            out["httpBlocked"] = !open
            CoreService.log(
                if (!open) "privacy: plain http is refused"
                else "privacy: plain http still gets through",
            )
        }
        return out
    }

    /**
     * A direct HTTP request to a well-known address. With the hardening rules in the config
     * it must fail; without them it gets a redirect back. 1.1.1.1 answers in a millisecond,
     * so a timeout here really means refused rather than slow.
     */
    private fun reachesPlainHttp(): Boolean = try {
        val conn = URL("http://1.1.1.1/").openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.instanceFollowRedirects = false
        val code = conn.responseCode
        conn.disconnect()
        code > 0
    } catch (_: Exception) {
        false
    }

    /** A name for the settings line: the second hop as the list shows it, never its address. */
    fun chainName(context: Context): String {
        val id = chainId(context)
        if (id.isEmpty()) return ""
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return ""
        return runCatching { NativeSec.payloadText(row, 1) }.getOrDefault("")
    }

    /** Whether the handset can see its own DNS at all - a sanity line, not a leak test. */
    fun resolverAlive(): Boolean = try {
        InetAddress.getAllByName("cloudflare.com").isNotEmpty()
    } catch (_: Exception) {
        false
    }
}
