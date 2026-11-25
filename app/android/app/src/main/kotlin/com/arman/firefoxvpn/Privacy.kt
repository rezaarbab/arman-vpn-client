package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec

/**
 * The person's own privacy switches, and the checks that prove them.
 *
 * Two values, both read by the library when it assembles the core's config: a flag
 * (`privacy`) and the id of the server to exit through (`chain_id`). They live in the
 * file the settings page writes, because that is the file the library was pointed at -
 * and both of them are **strings**: the library reads preferences the way it reads
 * every other value, as text, and an integer stored under a key it asks as text comes
 * back as nothing. That is how a switch that was plainly ON in the page never reached
 * the config, while the second hop - stored as text - did.
 *
 * The checks are the part that matters. A privacy setting nobody can measure is a
 * placebo: each test asks the network something the config text cannot fake.
 */
object Privacy {
    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

    /** The socks inbound of the tunnel's own config. */
    private const val TUNNEL_SOCKS = 10890

    private fun prefs(context: Context) =
        context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)

    /** Read as text, with the old integer value still understood. */
    fun on(context: Context): Boolean = when (val v = prefs(context).all["privacy"]) {
        is String -> v == "1"
        is Int -> v == 1
        else -> false
    }

    fun chainId(context: Context): String = prefs(context).getString("chain_id", "") ?: ""

    /**
     * Move the flag from a number to text, once. The library asks for it as text and a number
     * under that key reads back as absent - so a person who set the switch before this build
     * existed has it on in the page and off in the config until this runs.
     */
    fun migrate(context: Context) {
        val p = prefs(context)
        if (p.all["privacy"] is Int) {
            val v = if (p.getInt("privacy", 0) == 1) "1" else "0"
            p.edit().remove("privacy").putString("privacy", v).commit()
            CoreService.log("privacy: the stored switch was a number and is text now")
        }
    }

    /** The chosen second hop, or "" for one hop. Turning the switch off keeps the choice. */
    fun set(context: Context, enabled: Boolean, id: String) {
        val was = on(context)
        val wasChain = chainId(context)
        // a string, and committed: the library asks for it as text at dial time, and a
        // write still in the apply() queue when the person presses connect is not there
        prefs(context).edit()
            .putString("privacy", if (enabled) "1" else "0")
            .putString("chain_id", id)
            .commit()
        if (was != enabled) CoreService.log(if (enabled) "privacy: on" else "privacy: off")
        if (wasChain != id) CoreService.log("privacy: second hop changed")
    }

    /**
     * The shape of the config the running tunnel was built with, as the library left it:
     * `hardening-chain-chosen`. Three digits and nothing else - no address, no id. This is
     * what turns "it should be on" into "it is in the running config or it is not".
     */
    fun runningShape(context: Context): String =
        prefs(context).getString("cfg_shape", "") ?: ""

    /** The verdicts, as facts the network answered. */
    fun test(context: Context): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val hard = on(context)
        out["hardening"] = hard
        val id = chainId(context)
        out["chainChosen"] = id.isNotEmpty()
        val live = CoreService.on
        out["connected"] = live
        out["running"] = runningShape(context)
        if (!live) {
            out["chainInStore"] = id.isNotEmpty()
            say("privacy: not connected - nothing to measure yet")
            return out
        }
        val probe = askTunnel()
        out["exitKnown"] = probe.ip.isNotEmpty()
        out["tunnelSilent"] = probe.ip.isEmpty()
        val row = if (id.isNotEmpty()) runCatching { NativeSec.rowOf(id) }.getOrDefault(-1) else -1
        out["chainInStore"] = row >= 0
        if (id.isEmpty()) {
            out["chainCarries"] = false
            say("chain: no second server chosen")
        } else if (row < 0) {
            out["chainCarries"] = false
            say("chain: the second server is no longer in the list")
        } else if (probe.ip.isEmpty()) {
            out["chainCarries"] = false
            say("chain: not measured - the tunnel did not answer the page")
        } else {
            val hopIp = runCatching {
                NativeSec.locate(row).split(';').getOrNull(0).orEmpty().trim()
            }.getOrDefault("")
            val carried = hopIp.isNotEmpty() && probe.ip == hopIp
            out["chainCarries"] = carried
            say(
                if (carried) "chain: the traffic leaves through the second server"
                else "chain: the first server is still the exit",
            )
        }
        if (hard) {
            val open = runCatching { Flags.tunnelHttpReaches() }.getOrDefault(false)
            out["httpBlocked"] = !open
            say(
                if (!open) "privacy: plain http is refused by the tunnel"
                else "privacy: plain http still gets through the tunnel",
            )
        }
        return out
    }

    /**
     * Everything that can be asked of the network in one tap, each line one fact.
     *
     * The order is the diagnosis: is the page reachable at all from this phone, does it
     * answer through the tunnel, does the first hop answer on its own, does the second.
     * Four questions, and the combination says whether the chain is broken, whether the
     * chosen second server is dead, or whether nothing is broken and the page is just
     * blocked on this network. No address, no id, no name is ever written here.
     */
    fun debug(context: Context): List<String> {
        val lines = ArrayList<String>()
        val shape = runningShape(context)
        val stored = when (val v = prefs(context).all["privacy"]) {
            is String -> "text"
            is Int -> "number"
            null -> "absent"
            else -> "other"
        }
        lines.add("stored privacy = $stored, running config = ${if (shape.isEmpty()) "never dialed" else shape}")
        if (on(context) && shape.isNotEmpty() && !shape.startsWith("1")) {
            lines.add("the switch is on but the running tunnel was built without it - dial again")
        }

        val through = askTunnel()
        lines.add(
            "through the tunnel: " + if (through.answered) "answered" else "did not answer" +
                " [${through.step}]",
        )
        val direct = askDirect()
        lines.add(
            "outside the tunnel: " + if (direct.answered) "answered" else "did not answer" +
                " [${direct.step}]",
        )
        if (!direct.answered) {
            lines.add("the page does not answer even directly - this network blocks it, the tunnel cannot be judged")
            return lines
        }
        if (direct.answered && !through.answered) {
            lines.add("the page answers outside the tunnel and not inside it - the tunnel is not carrying traffic")
        }

        val picked = CoreService.pickedId(context)
        if (picked.isNotBlank()) {
            lines.add("first server, measured alone: " + delayWord(Flags.delay(context, picked)))
        }
        val id = chainId(context)
        if (id.isNotEmpty()) {
            val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
            if (row < 0) {
                lines.add("second server: not in the list any more")
            } else {
                lines.add("second server, measured alone: " + delayWord(Flags.delay(context, id)))
                val hopIp = runCatching {
                    NativeSec.locate(row).split(';').getOrNull(0).orEmpty().trim()
                }.getOrDefault("")
                lines.add(
                    if (hopIp.isEmpty()) "second server: its address could not be resolved"
                    else "second server: resolves, and the tunnel exit ${if (through.ip == hopIp) "is" else "is not"} that address",
                )
            }
        }
        if (shape.split('-').getOrNull(1) == "1" && !through.answered && direct.answered) {
            lines.add("the chain is in the config and the tunnel carries nothing - turn the second hop off until this is fixed")
        }
        return lines
    }

    private fun delayWord(ms: Int): String = when {
        ms == -2 -> "could not be measured beside the tunnel"
        ms == -1 -> "answered nothing"
        else -> "$ms ms"
    }

    private class Probe(val ip: String, val answered: Boolean, val step: String) {
        override fun toString(): String = ip
    }

    /** The trace page, asked through the tunnel's own socks port. */
    private fun askTunnel(): Probe = ask(TUNNEL_SOCKS)

    /** The same page, asked outside the tunnel - the control that makes the other answer mean something. */
    private fun askDirect(): Probe = ask(0)

    private fun ask(port: Int): Probe {
        val raw = runCatching { NativeSec.probe(TRACE, "ip;loc", port) }.getOrDefault("")
        val step = runCatching { NativeSec.errInfo() }.getOrDefault("")
        val ip = if (raw.isEmpty()) "" else raw.split(';').getOrNull(0).orEmpty().trim()
        return Probe(ip, ip.isNotEmpty(), step.ifBlank { if (ip.isEmpty()) "no-answer" else "ok" })
    }

    /**
     * One line per changed verdict. The same sentence printed thirty times is not evidence,
     * it is noise that hides the line that matters.
     */
    private var lastSaid = ""

    private fun say(line: String) {
        if (line == lastSaid) return
        lastSaid = line
        CoreService.log(line)
    }
}
