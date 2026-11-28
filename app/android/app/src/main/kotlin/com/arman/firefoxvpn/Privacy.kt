package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec
import libv2ray.CoreCallbackHandler

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

    /**
     * The open port, asked of the host the encrypted test already knows answers from this phone.
     *
     * The first target here was unreachable from the handset in both directions, so every run ended
     * with "not measurable" and the rule was never judged. A page that arrives over 443 and not over
     * 80, from the same host, is the whole question - and a body with no fields in it still counts as
     * carried, which is what the redirect and error pages of a blocked port never produce.
     */
    private const val PLAINTEXT = "http://www.cloudflare.com/cdn-cgi/trace"
    private const val PLAIN_KEYS = "ip;loc"

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
            // judgement about one dial, not a permanent verdict on the server - and a new choice
            // starts its own count of failures
            .putString("chain_off", "0")
            .remove("chain_why")
            .remove("chain_fails")
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

    /** Take the chosen hop out of the next dial, with the one reason the tunnel knows. */
    fun dropHop(context: Context, why: String) {
        prefs(context).edit()
            .putString("chain_off", "1")
            .putString("chain_why", why)
            .commit()
    }

    /** The reason recorded with the drop: `same-host` or `carried-nothing`, "" when nothing dropped. */
    fun dropWhy(context: Context): String = prefs(context).getString("chain_why", "") ?: ""

    /**
     * Put the hop back - unless it has already failed three times, in which case the person is
     * done with it and the next connect should not cost them eight more seconds of a dead tunnel.
     * A new choice clears the count, so the record is about this pair, not about the feature.
     */
    fun rearmHop(context: Context) {
        if (!dropped(context)) return
        if (hopFails(context) >= 3) return
        prefs(context).edit().remove("chain_off").remove("chain_why").remove("chain_fails").commit()
    }

    /** How many times this pair was dialed and carried nothing. */
    fun hopFails(context: Context): Int =
        runCatching { prefs(context).getString("chain_fails", "0")!!.toInt() }.getOrDefault(0)

    /** One more failure, and whether that was the last one the tunnel will attempt. */
    fun noteDrop(context: Context): Int {
        val n = hopFails(context) + 1
        prefs(context).edit().putString("chain_fails", n.toString()).commit()
        return n
    }

    /** Stop asking, without waiting for three tries: for a pair that cannot be two hops at all,
     *  every attempt is eight seconds of a tunnel that carries nothing. */
    fun giveUpHop(context: Context) {
        prefs(context).edit().putString("chain_fails", "3").commit()
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
        out["sameHost"] = id.isNotEmpty() && sameHost(context)
        out["exitKnown"] = seen.exit.isNotEmpty()
        out["tunnelSilent"] = seen.silent
        out["pageRefuses"] = seen.picky
        out["notMeasurable"] = seen.refusedEverywhere
        out["chainDropped"] = dropped(context)
        out["dropWhy"] = dropWhy(context)
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
        } else if (out["sameHost"] == true) {
            out["chainCarries"] = false
            say("chain: the second server is the same machine as the first - there is no second hop there")
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
     * Whether the two chosen servers are one machine reached on two ports.
     *
     * A scraped list is full of them: one host selling twenty rows. Chosen as a second hop it adds
     * nothing to hide and nothing to gain - the same box is on both sides - and it is a common
     * mistake to make by accident, so it is named instead of failing as a mystery.
     */
    fun sameHost(context: Context): Boolean {
        val id = chainId(context)
        if (id.isEmpty()) return false
        val first = hopIpOf(context, CoreService.pickedId(context))
        val second = hopIpOf(context, id)
        return first.isNotEmpty() && first == second
    }

    /**
     * Whether the phone's own server can carry another server's traffic - asked **without touching
     * the running tunnel**, so a person learns it before connecting instead of by losing the internet.
     *
     * A short-lived core starts on the carrier row with the pair switch armed for that row only, and
     * the library builds it exactly as it builds the tunnel's chain. The person's own choice is never
     * written, and the switch is off again before this returns. It answers 1 when the page came
     * through the pair, 0 when it did not, -2 when the test itself could not run - and the verdict is
     * kept per candidate, so the picker can mark the rows that carry rather than being tried blind.
     */
    fun pairTest(context: Context, exitId: String): Int {
        val word = probePair(context, exitId)
        CoreService.log("pair: $word")
        return when (word) {
            "carries" -> 1
            "no-core", "no-row" -> -2
            else -> 0
        }
    }

    /**
     * What a chained page through this pair came back as, as a word and never an address or a name.
     *
     * `carries` is the only good one. The rest name different faults, and telling them apart is the
     * whole point: a server that refuses the connection, one whose handshake is torn down, and one
     * that never answers at all are three different problems - and until here all three printed as
     * "it did not work", which is what made this feel random.
     */
    private fun probePair(context: Context, exitId: String): String {
        val carrier = CoreService.pickedId(context)
        if (carrier.isBlank() || exitId.isBlank() || carrier == exitId) return "no-row"
        val carrierRow = runCatching { NativeSec.rowOf(carrier) }.getOrDefault(-1)
        if (carrierRow < 0 || runCatching { NativeSec.rowOf(exitId) }.getOrDefault(-1) < 0) return "no-row"
        val p = prefs(context)
        val port = pairPort()
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle: Int
        // The three armed switches are one shared pair of prefs, and the library reads them while it
        // builds this core's config. Writing them and starting the core therefore happen under one
        // lock: without it, a second test could arm its own exit between the first one's write and
        // its build, and a row would be measured through another row's pair.
        synchronized(pairLock) {
            p.edit()
                .putString("probe_carrier", carrier)
                .putString("probe_exit", exitId)
                .putString("probe_chain", "1")
                .commit()
            handle = runCatching { NativeSec.probeStart(carrierRow, port, quiet) }.getOrDefault(0)
            p.edit().putString("probe_chain", "0").commit()
        }
        if (handle == 0) return "no-core"
        return try {
            val seen = ask(TRACE, TRACE_KEYS, port)
            val word = verdictWord(seen)
            p.edit().putString("pc_" + exitId.take(64), word).commit()
            word
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }

    /** The library keeps the first exception of a call with its class and its message, and those
     *  words are what separates one failure from another without saying anything about a server. */
    private fun verdictWord(seen: Probe): String = when {
        seen.answered -> "carries"
        seen.step.contains("ConnectException") || seen.step.contains("connection refused") -> "refused"
        seen.step.contains("SSLHandshake") || seen.step.contains("reset by peer") ||
            seen.step.contains("closed by peer") || seen.step.contains("handshake") -> "handshake-reset"
        seen.step.contains("SocketTimeout") || seen.step.contains("ReadTimeout") -> "no-answer"
        seen.step.contains("NoRouteToHost") || seen.step.contains("NetworkUnreachable") ||
            seen.step.contains("ENETUNREACH") || seen.step.contains("UnknownHost") ||
            seen.step.contains("SocketException") -> "unreachable"
        seen.carried -> "page-refused"
        else -> "no-answer"
    }

    /** How a row was last measured as a second hop: "carries", "handshake-reset", "" if untested. */
    fun pairVerdict(context: Context, id: String): String = pairKnown(context, id)

    /**
     * A number standing for the machine a row is reached at: rows sharing one are the same box on
     * different ports, which makes a "second hop" a loop through one provider rather than a hop.
     *
     * The address is never stored or printed - this is a fold of it into four digits, kept so the
     * list can mark the rows that are really one choice.
     */
    fun hostGroup(context: Context, id: String): String {
        if (id.isBlank()) return ""
        val saved = prefs(context).getString("mg_" + id.take(64), "") ?: ""
        if (saved.isNotEmpty()) return saved
        val ip = hopIpOf(context, id)
        if (ip.isEmpty()) return ""
        var h = 7
        for (c in ip) h = (h * 31 + c.code) and 0x7fffffff
        val group = (h % 9000 + 1000).toString()
        prefs(context).edit().putString("mg_" + id.take(64), group).commit()
        return group
    }

    /** The pair verdicts this phone already has, by row id: "1" carries, "0" does not. */
    fun pairKnown(context: Context, id: String): String =
        if (id.isBlank()) "" else prefs(context).getString("pc_" + id.take(64), "") ?: ""

    /** All of them at once, for the picker to mark its rows without asking per row. */
    fun pairVerdicts(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in prefs(context).all) {
            if (k.startsWith("pc_") && v is String) out[k.removePrefix("pc_")] = v
        }
        return out
    }

    /** The machine marks this phone knows, by row id: rows sharing a number are one box. */
    fun hostGroups(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in prefs(context).all) {
            if (k.startsWith("mg_") && v is String) out[k.removePrefix("mg_")] = v
        }
        return out
    }

    /**
     * Test **every** row as a second hop through the server in use - in the background, one at a
     * time, one line of the log per answer.
     *
     * Trying pairs by hand costs a connect, eight seconds of a dead tunnel and a trip back to the
     * list, which is why this exists. Rows that resolve to the same machine as the carrier are named
     * without being dialed at all - they cannot be a second hop - and every other row gets exactly
     * the question a single test asks, with the reason it failed written down.
     *
     * Asking again stops it. What was measured until then is kept.
     */
    @Volatile
    private var surveyOn = false

    fun surveyRunning(): Boolean = surveyOn

    fun survey(context: Context) {
        if (surveyOn) {
            surveyOn = false
            CoreService.log(
                "survey: you asked it to stop - " + svDone.get() + " tested, " + svGood.get() +
                    " carry, and every one of them is kept",
            )
            return
        }
        val ctx = context.applicationContext
        val carrier = CoreService.pickedId(ctx)
        if (carrier.isBlank()) {
            CoreService.log("survey: pick a server first - the others are tested through it")
            return
        }
        surveyOn = true
        svDone.set(0); svTotal.set(0); svGood.set(0); svSame.set(0)
        Thread {
            val mine = hostGroup(ctx, carrier)
            val count = IrRows.count()
            val todo = ArrayList<String>()
            for (i in 0 until count) {
                if (!surveyOn) break
                val id = runCatching { IrRows.id(i) }.getOrDefault("")
                if (id.isEmpty() || id == carrier) continue
                val group = hostGroup(ctx, id)
                if (mine.isNotEmpty() && group == mine) {
                    svSame.incrementAndGet()
                    CoreService.log("survey: row $i - the same machine as your server, not a second hop")
                    continue
                }
                // a row that already proved itself is not dialed again; failures are, because they
                // are the flaky ones and a second answer is the information
                if (pairKnown(ctx, id) == "carries") continue
                todo.add(id)
            }
            svTotal.set(todo.size)
            CoreService.log(
                "survey: $count rows, " + svSame.get() + " are the same machine as your server, " +
                    todo.size + " to test through it",
            )
            // Three at a time: one pair test costs up to eleven seconds of waiting for a page, and
            // the phone already runs three flag cores side by side, so this is the proven ceiling.
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
            val workers = ArrayList<java.util.concurrent.Future<*>>()
            for (w in 0 until 3) {
                val list = ArrayList<String>()
                var k = w
                while (k < todo.size) { list.add(todo[k]); k += 3 }
                workers.add(pool.submit {
                    for (id in list) {
                        if (!surveyOn) break
                        val word = probePair(ctx, id)
                        svDone.incrementAndGet()
                        if (word == "carries") svGood.incrementAndGet()
                        CoreService.log("survey: " + svDone.get() + " of " + svTotal.get() + " - $word")
                    }
                })
            }
            pool.shutdown()
            while (!pool.isTerminated) {
                runCatching { pool.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
            }
            runCatching { workers.forEach { it.get() } }
            surveyOn = false
            CoreService.log(
                "survey: over - " + svGood.get() + " of " + svTotal.get() +
                    " can carry a second hop, " + svSame.get() + " are the same machine as your server",
            )
        }.start()
    }

    private val pairSeed = java.util.concurrent.atomic.AtomicInteger(0)

    /** Held while a pair's switches are armed and its core is started, so parallel tests cannot
     *  build each other's config. */
    private val pairLock = Any()

    /** Where the survey is, read by the page every couple of seconds while it runs. */
    private val svDone = java.util.concurrent.atomic.AtomicInteger(0)
    private val svTotal = java.util.concurrent.atomic.AtomicInteger(0)
    private val svGood = java.util.concurrent.atomic.AtomicInteger(0)
    private val svSame = java.util.concurrent.atomic.AtomicInteger(0)

    fun surveyState(): Map<String, Any?> = mapOf(
        "running" to surveyOn,
        "done" to svDone.get(),
        "total" to svTotal.get(),
        "good" to svGood.get(),
        "same" to svSame.get(),
    )

    /** The mark already stored for a row, without asking the resolver anything: the channel reads
     *  this on its own thread, and a name lookup does not belong there. */
    fun cachedGroup(context: Context, id: String): String =
        if (id.isBlank()) "" else prefs(context).getString("mg_" + id.take(64), "") ?: ""

    /** Away from the ports the flag probes use, so the two never fight over one. */
    private fun pairPort(): Int = 23000 + (pairSeed.getAndIncrement() % 500)

    /**
     * The two halves of the hardening, each on its own switch. The core answering lookups and the
     * open port being refused fail in different ways - one can leave the phone with no names at all,
     * the other breaks the sites that still speak plain http - so they are turned off separately
     * rather than losing both with one switch.
     */
    fun dnsOn(context: Context): Boolean = prefs(context).getString("priv_dns", "1") != "0"

    fun httpOn(context: Context): Boolean = prefs(context).getString("priv_http", "1") != "0"

    /** Text, and committed: the library reads every preference as a string, at dial time. */
    fun setParts(context: Context, dns: Boolean, http: Boolean) {
        prefs(context).edit()
            .putString("priv_dns", if (dns) "1" else "0")
            .putString("priv_http", if (http) "1" else "0")
            .commit()
        CoreService.log(
            "privacy: lookups " + (if (dns) "answered by the core" else "as before") +
                ", plain web " + (if (http) "refused" else "allowed"),
        )
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
        // The shape is seven facts; spelled out here so a paste says what it means without anyone
        // having to know the order of the digits.
        val fields = shape.split('-')
        if (fields.size >= 5) {
            if (fields[3].length == 2) {
                lines.add(
                    "transports: carrier " + fields[3][0] + ", exit " + fields[3][1] +
                        " (t=tcp w=ws g=grpc x=xhttp h=httpupgrade)",
                )
            }
            if (fields[4].length == 2) {
                lines.add(
                    "hardening halves in this dial: core-side lookups " +
                        (if (fields[4][0] == '1') "on" else "off") + ", plain web refused " +
                        (if (fields[4][1] == '1') "on" else "off"),
                )
            }
        }
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

        val seen = look(true)
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
        // "it connects but nothing moves" needs two numbers before anything else: what the core
        // counts as carried, and whether the open port gets through when the encrypted pages were
        // reset. Bytes that move with TLS dying is a different fault from a tunnel carrying nothing,
        // and the phone cannot tell them apart without being asked in that order.
        val t = CoreService.traffic
        // The card's session totals are the only byte numbers worth printing: the library's own
        // query returns what moved since the last query, and the one-second ticker has already read
        // that by the time a page asks - summing it here once printed "0 B" beside a card showing
        // kilobytes, and the contradiction belonged to the measuring, not to the tunnel.
        // What the raw text is good for is the names, and ours are fixed config tags, no server.
        val tags = LinkedHashSet<String>()
        for (chunk in CoreService.statsRaw().split(';')) {
            val p = chunk.split(',')
            if (p.size >= 3 && p[0].trim().isNotEmpty()) tags.add(p[0].trim())
        }
        lines.add(
            "this session carried " + human(t.up) + " sent and " + human(t.down) + " received" +
                if (t.up + t.down == 0L) " - nothing has crossed it" else "",
        )
        if (tags.isNotEmpty()) {
            lines.add("the core reports traffic for the tags " + tags.joinToString(", "))
        }
        if (!seen.arrives && seen.plain.step != NOT_ASKED) {
            lines.add(
                if (seen.httpArrives)
                    "the open port came through while both encrypted pages were reset - bytes get through the chain and TLS does not"
                else
                    "nothing comes through the chain at all, the open page included - the second hop is not carrying this tunnel",
            )
        }

        val picked = CoreService.pickedId(context)
        val id = chainId(context)
        val row = if (id.isNotEmpty()) runCatching { NativeSec.rowOf(id) }.getOrDefault(-1) else -1
        if (sameHost(context)) {
            lines.add(
                "the second server is the same machine as the first, on another port - " +
                    "a second hop there hides nothing from anyone, and the box itself may refuse it",
            )
        }
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
            lines.add(
                "the chain is in the running config and carries nothing - the tunnel drops it after this measurement " +
                    "and reconnects on one server; re-ask for the hop here when you want it tried again",
            )
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

    /**
     * One page's answer.
     *
     * `carried` is not the same fact as `answered`: the library keeps the **first** exception of a
     * call in its error text and clears it only when asked, so an answer with no `last=` in it means
     * the stream was read to its end and the fields simply were not in the body. That is a page
     * arriving - which is the difference between a chain carrying bytes and a chain carrying nothing.
     */
    private class Probe(val ip: String, val cc: String, val answered: Boolean, val carried: Boolean, val step: String) {
        override fun toString(): String = ip
    }

    /** A question this run had no reason to ask: the lines that read it say so instead of "no". */
    private val notAsked = Probe("", "", false, false, NOT_ASKED)

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

        /**
         * Whether the open port can be judged at all: it was asked of the tunnel, and the same page
         * came through outside it. A page this network never answers cannot be used to claim the
         * tunnel refused it - that is how a refusal of a site became a verdict about a rule.
         */
        val httpJudged: Boolean
            get() = plain.step != NOT_ASKED && (plainOutside.answered || plainOutside.carried)

        /**
         * Something came back through the open port. The fields need not be in it: a redirect page
         * is still a page carried, and the hardening rule is about the port, not about the body.
         */
        val httpArrives: Boolean get() = plain.answered || plain.carried
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
        // Asked whether or not the encrypted pages arrived. A page on the open port that came
        // through while both TLS pages were reset is a different fault - bytes move and handshakes
        // die - and it is only visible if the question is asked in that case rather than skipped.
        val plain = if (askHttp) ask(PLAINTEXT, PLAIN_KEYS, TUNNEL_SOCKS) else notAsked
        val plainOutside =
            if (plain.step != NOT_ASKED) ask(PLAINTEXT, PLAIN_KEYS, 0) else notAsked
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
        // the library names the first exception it saw, and only that one: no exception means the
        // stream was read, whatever came in it
        val carried = step.isNotEmpty() && !step.contains("last=")
        return Probe(ip, cc, ip.isNotEmpty(), carried, step.ifBlank { if (ip.isEmpty()) "no-answer" else "ok" })
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
