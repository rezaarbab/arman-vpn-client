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
    /** The trace page answers with `ip=` and `loc=` - `loc` is the two-letter country, and there
     *  is no `country` field on it. Verified against the page, because asking for a field it does
     *  not have turns every answer into "nothing" and makes a live list look dead. */
    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"
    private const val TRACE_KEYS = "ip;loc"

    /**
     * A page that does not live on the first page's infrastructure, and answers the same two words
     * under its own spellings: the exit address and a two-letter country.
     *
     * One host saying no is that host, not the tunnel: without a second opinion a refusal by the
     * trace page reads exactly like a tunnel carrying nothing - which is what this page did when it
     * answered "Connection closed by peer" while the phone was browsing through the same tunnel.
     */
    private const val ELSEWHERE = "https://get.geojs.io/v1/ip/geo.json"
    private const val ELSEWHERE_KEYS = "ip;country_code"

    /** The same host on the open port the hardening is supposed to refuse. */
    private const val PLAINTEXT = "http://get.geojs.io/v1/ip/geo.json"

    /** The socks inbound of the tunnel's own config. */
    private const val TUNNEL_SOCKS = 10890

    /** A page this run had no reason to ask about, kept apart from a page that was asked and refused. */
    private const val NOT_ASKED = "not asked"

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
     * The chosen second hop as the list names it, so the page can say what it picked instead of
     * an id nobody can read. Empty when no second hop is chosen or when that server has left the
     * list - the name is never guessed from the id, and never an address.
     */
    fun chainName(context: Context): String {
        val id = chainId(context)
        if (id.isEmpty()) return ""
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        return if (row < 0) "" else IrRows.name(row)
    }

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
            // whoever touches this choice again gets the hop back: a drop is the tunnel's
            // judgement about one dial, not a permanent verdict on the server
            .putString("chain_off", "0")
            .commit()
        if (was != enabled) CoreService.log(if (enabled) "privacy: on" else "privacy: off")
        if (wasChain != id) CoreService.log("privacy: second hop changed")
    }

    /**
     * The hop the tunnel took out of its own dial because nothing arrived through it. The choice
     * stays stored and the page still names the server: this only says the next dial goes without.
     * Writing the choice again - which the page's tap does - is what puts it back.
     */
    fun dropped(context: Context): Boolean =
        prefs(context).getString("chain_off", "0") == "1"

    /** Take the chosen hop out of the next dial. The tunnel says why in its own log line. */
    fun dropHop(context: Context) {
        if (dropped(context)) return
        prefs(context).edit().putString("chain_off", "1").commit()
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
        val seen = look(hard)
        val row = if (id.isNotEmpty()) runCatching { NativeSec.rowOf(id) }.getOrDefault(-1) else -1
        out["chainInStore"] = row >= 0
        out["exitKnown"] = seen.exit.isNotEmpty()
        out["tunnelSilent"] = seen.silent
        out["pageRefuses"] = seen.picky
        out["notMeasurable"] = seen.refusedEverywhere
        out["chainDropped"] = dropped(context)
        out["httpBlocked"] = if (hard && seen.httpJudged) !seen.httpArrives else null
        if (id.isEmpty()) {
            out["chainCarries"] = false
            say("chain: no second server chosen")
        } else if (dropped(context)) {
            out["chainCarries"] = false
            say("chain: the tunnel took this hop out of the dial - the choice is still yours")
        } else if (row < 0) {
            out["chainCarries"] = false
            say("chain: the second server is no longer in the list")
        } else if (seen.exit.isEmpty()) {
            out["chainCarries"] = false
            say(
                if (seen.refusedEverywhere) "chain: not measured - the pages refuse even outside the tunnel"
                else "chain: not measured - nothing arrives through the tunnel",
            )
        } else {
            val hopIp = hopIpOf(context, id)
            val owner = exitOwner(
                context,
                seen,
                hopIp,
                Flags.egressOf(context, id),
                Flags.egressOf(context, CoreService.pickedId(context)),
            )
            val carried = owner == "hop2-out" || owner == "hop2-dns" || owner == "country2"
            out["chainCarries"] = carried
            // Which hop owns the exit, and on which test: "the second one" is a chain that works,
            // "the first one" is a chain built and then not used, and "other" is a third thing -
            // three different faults that used to print as one word.
            out["exitIs"] = owner
            if (seen.picky) say("exit: measured on the second page - the trace page refuses this exit")
            say(
                when {
                    carried -> "chain: the traffic leaves through the second server"
                    owner == "hop1-out" || owner == "hop1-dns" || owner == "country1" ->
                        "chain: the exit is still the first server"
                    else -> "chain: the exit belongs to neither server - the hop in the dial is not the way out"
                },
            )
        }
        if (hard) {
            say(
                when {
                    !seen.httpJudged -> "privacy: plain http not measured - the page does not answer outside the tunnel"
                    !seen.httpArrives -> "privacy: plain http is refused by the tunnel"
                    else -> "privacy: plain http still gets through the tunnel"
                },
            )
        }
        return out
    }

    /**
     * Whether the tunnel is carrying, as one number: 1 when a page arrived through it, 0 when
     * nothing arrived and the same page answers outside it, -1 when the pages refuse the phone
     * too and there is nothing to conclude.
     *
     * The tunnel asks this itself a moment after it comes up, because a second hop that cannot
     * carry leaves the person with no internet at all, and that is not a state to wait for a
     * button press in.
     */
    fun carries(context: Context): Int {
        if (!CoreService.on) return -1
        val seen = look(false)
        return when {
            seen.arrives -> 1
            seen.outside.answered -> 0
            else -> -1
        }
    }

    /**
     * Everything that can be asked of the network in one tap, each line one fact.
     *
     * The order is the diagnosis: does the tunnel answer the trace page, does it answer a page on
     * other infrastructure, does the trace page answer from this phone at all, and does the tunnel
     * refuse the open port. Those four separate the three things that used to look identical: the
     * chain broken, the exit refused by one host, and a network that blocks the pages.
     * No address, no id, no name is ever written here.
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
        if (dropped(context)) {
            lines.add("the tunnel dropped the second hop after it carried nothing - the dial is on one server, the choice is kept")
        }
        if (on(context) && shape.isNotEmpty() && !shape.startsWith("1")) {
            lines.add("the switch is on but the running tunnel was built without it - dial again")
        }
        if (!CoreService.on) {
            lines.add("not connected - there is no tunnel to ask")
            return lines
        }

        val seen = look(on(context))
        lines.add("trace page, through the tunnel: " + said(seen.trace))
        lines.add("another page, through the tunnel: " + said(seen.other))
        if (seen.outside.step != NOT_ASKED) {
            lines.add("trace page, outside the tunnel: " + said(seen.outside))
        }
        lines.add(
            when {
                seen.arrives -> "the tunnel carries: a page arrived through it"
                seen.refusedEverywhere ->
                    "the pages refuse even outside the tunnel - this network refuses them, nothing can be judged"
                else -> "nothing arrives through the tunnel and the page answers outside it - the tunnel carries nothing"
            },
        )
        if (seen.arrives && !seen.trace.answered) {
            lines.add("the trace page refuses what the tunnel exits with, another page accepts it - one host saying no is that host")
        }

        val picked = CoreService.pickedId(context)
        val id = chainId(context)
        val row = if (id.isNotEmpty()) runCatching { NativeSec.rowOf(id) }.getOrDefault(-1) else -1
        // Measured before they are compared, on purpose: each of these is also what writes down the
        // address that row's own traffic is seen from, and that - not the resolved name - is the
        // only thing the exit can be matched against to say "this is that server" for certain.
        if (picked.isNotBlank()) {
            lines.add("first server, measured alone: " + delayWord(Flags.delay(context, picked)))
        }
        if (id.isNotEmpty() && row < 0) {
            lines.add("second server: not in the list any more")
        } else if (row >= 0) {
            lines.add("second server, measured alone: " + delayWord(Flags.delay(context, id)))
        }
        val hopIp = if (row >= 0) hopIpOf(context, id) else ""
        val owner = exitOwner(
            context,
            seen,
            hopIp,
            if (row >= 0) Flags.egressOf(context, id) else "",
            Flags.egressOf(context, picked),
        )
        lines.add(
            when {
                seen.exit.isEmpty() -> "no exit could be measured to compare with"
                row < 0 -> "nothing to compare the exit with - no second server is chosen"
                owner == "hop2-out" ->
                    "the exit is the address the second server answers from on its own: the chain carries, and it is that box"
                owner == "hop2-dns" ->
                    "the exit is the address the second server's name resolves to: the chain carries"
                owner == "country2" ->
                    "the exit is in the second server's country, from an address neither test names: the chain is carrying"
                owner == "hop1-out" || owner == "hop1-dns" ->
                    "the exit is the FIRST server's own address: the chain is in the dial and not in use"
                owner == "country1" ->
                    "the exit is in the FIRST server's country: the chain is in the dial and not in use"
                else -> "the exit is neither server's address nor either server's country - " +
                    "the hop in the dial is not the way out"
            },
        )
        if (owner == "hop2-out" && hopIp.isNotEmpty() && hopIp != Flags.egressOf(context, id)) {
            lines.add(
                "that server answers from a different address than the one its name resolves to - " +
                    "which is why the address test alone could not decide it",
            )
        }
        if (shape.split('-').getOrNull(1) == "1" && seen.silent) {
            lines.add("the chain is in the config and the tunnel carries nothing - turn the second hop off until this is fixed")
        }
        if (on(context)) {
            lines.add(
                when {
                    !seen.httpJudged ->
                        "plain http through the tunnel: not measurable - the page does not answer outside it either"
                    seen.httpArrives -> "plain http through the tunnel: arrives - the refusal is not happening"
                    else -> "plain http through the tunnel: refused - the rule works"
                },
            )
        }
        return lines
    }

    private fun delayWord(ms: Int): String = when {
        ms == -2 -> "could not be measured beside the tunnel"
        ms == -1 -> "answered nothing"
        else -> "$ms ms"
    }

    /** The address a row's own name resolves to, as the library sees it. "" when it does not. */
    private fun hopIpOf(context: Context, id: String): String {
        if (id.isBlank()) return ""
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return ""
        return runCatching {
            NativeSec.locate(row).split(';').getOrNull(0).orEmpty().trim()
        }.getOrDefault("")
    }

    /**
     * Whose exit the tunnel answered with, as one word and never an address.
     *
     * The order is the strength of the evidence. Each row's **own egress** comes first: it is the
     * same measurement on both sides - a page naming the address that row's traffic left with - so
     * a match says "this exit is that box" rather than "near it". The address the name resolves to
     * is the weaker second test, since a server can answer from another address than the one it is
     * reached at, and the country is the weakest: good for "the hop is in use", nothing more.
     *
     * `hop2-out` `hop1-out` `hop2-dns` `hop1-dns` `country2` `country1` `other`, "" unmeasured.
     */
    private fun exitOwner(
        context: Context,
        seen: Sight,
        hopIp: String,
        hopOut: String,
        firstOut: String,
    ): String {
        val exit = seen.exit
        if (exit.isEmpty()) return ""
        if (hopOut.isNotEmpty() && exit == hopOut) return "hop2-out"
        if (firstOut.isNotEmpty() && exit == firstOut) return "hop1-out"
        if (hopIp.isNotEmpty() && exit == hopIp) return "hop2-dns"
        if (exit == hopIpOf(context, CoreService.pickedId(context))) return "hop1-dns"
        val cc = seen.cc
        if (cc.length == 2) {
            val second = Flags.of(context, chainId(context)).uppercase()
            val first = Flags.of(context, CoreService.pickedId(context)).uppercase()
            if (second.length == 2 && cc == second) return "country2"
            if (first.length == 2 && cc == first && cc != second) return "country1"
        }
        return "other"
    }

    private class Probe(val ip: String, val cc: String, val answered: Boolean, val step: String) {
        override fun toString(): String = ip
    }

    /** A question this run had no reason to ask: the lines that read it say so instead of "no". */
    private val notAsked = Probe("", "", false, NOT_ASKED)

    /**
     * What the tunnel and the phone answered, kept as answers rather than as conclusions: every
     * verdict in the two functions above is a reading of these, so a line names the one thing it saw.
     */
    private class Sight(
        val trace: Probe,
        val other: Probe,
        val outside: Probe,
        val plain: Probe,
        val plainOutside: Probe,
    ) {
        /** The address the traffic leaves with, from whichever page answered. */
        val exit: String get() = if (trace.ip.isNotEmpty()) trace.ip else other.ip

        /** The country of that exit, from the same page that gave the address. */
        val cc: String get() = if (trace.ip.isNotEmpty()) trace.cc else other.cc

        /** Something came back through the tunnel, whichever page it was. */
        val arrives: Boolean get() = trace.answered || other.answered

        /** The trace page said no and a page on other infrastructure said yes. */
        val picky: Boolean get() = other.answered && !trace.answered

        /** The pages answer from the phone and none of them answer through the tunnel. */
        val silent: Boolean get() = !arrives && outside.answered

        /** The pages say no from the phone too: this network refuses them and nothing can be judged. */
        val refusedEverywhere: Boolean get() = !arrives && !outside.answered && outside.step != NOT_ASKED

        /** Whether the open port was asked of both sides, which is all that makes the next line mean anything. */
        val httpJudged: Boolean get() = plain.step != NOT_ASKED && plainOutside.step != NOT_ASKED

        val httpArrives: Boolean get() = plain.answered
    }

    /**
     * Ask, in the order that lets an early yes cancel the questions that only a no would need.
     * Each of these costs up to eleven seconds of the ask thread, so a working tunnel pays for two
     * pages and a broken one pays for the controls that prove whose fault it is.
     */
    private fun look(askHttp: Boolean): Sight {
        val trace = askTunnel()
        val other = if (trace.answered) notAsked else ask(ELSEWHERE, ELSEWHERE_KEYS, TUNNEL_SOCKS)
        val arrived = trace.answered || other.answered
        val outside = if (arrived) notAsked else askDirect()
        val plain =
            if (arrived && askHttp) ask(PLAINTEXT, ELSEWHERE_KEYS, TUNNEL_SOCKS) else notAsked
        val plainOutside =
            if (plain.step != NOT_ASKED && !plain.answered) ask(PLAINTEXT, ELSEWHERE_KEYS, 0) else notAsked
        return Sight(trace, other, outside, plain, plainOutside)
    }

    /** One page's answer, in the words a person reads: what came back, or where it stopped. */
    private fun said(p: Probe): String =
        if (p.step == NOT_ASKED) "not asked" else if (p.answered) "answered" else "did not answer [${p.step}]"

    /** The trace page, asked through the tunnel's own socks port. */
    private fun askTunnel(): Probe = ask(TRACE, TRACE_KEYS, TUNNEL_SOCKS)

    /** The same page, asked outside the tunnel - the control that makes the other answer mean something. */
    private fun askDirect(): Probe = ask(TRACE, TRACE_KEYS, 0)

    private fun ask(url: String, keys: String, port: Int): Probe {
        val raw = runCatching { NativeSec.probe(url, keys, port) }.getOrDefault("")
        val step = runCatching { NativeSec.errInfo() }.getOrDefault("")
        val parts = if (raw.isEmpty()) emptyList() else raw.split(';')
        val ip = parts.getOrNull(0).orEmpty().trim()
        val cc = parts.getOrNull(1).orEmpty().trim().uppercase()
        return Probe(ip, cc, ip.isNotEmpty(), step.ifBlank { if (ip.isEmpty()) "no-answer" else "ok" })
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
