package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec
import libv2ray.CoreCallbackHandler
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server's country, the way the app of origin learned it: a short-lived core is started
 * from the row, the handset asks Cloudflare's trace page through it, and the two letters are
 * kept under the row's id. Nothing about the row's address is read here.
 *
 * The probe core has its own local port and the app's own sockets are outside the VPN by
 * choice, so this works with the tunnel up as well as down. Several rows are probed at once,
 * so their local ports come from a counter, not from a draw that can hand two of them the
 * same one.
 */
object Flags {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val portSeed = AtomicInteger(0)

    private fun port(): Int = 20000 + ((portSeed.getAndIncrement() and 0x7fffffff) % 2000)

    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

    private fun prefs(context: Context) =
        context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)

    fun of(context: Context, id: String): String {
        cache[id]?.let { return it }
        val v = prefs(context).getString("cc_" + id.take(64), "") ?: ""
        if (v.length == 2) cache[id] = v
        return v
    }

    /** Everything already learned in an earlier run, id -> country. */
    fun all(context: Context): Map<String, String> {
        val out = HashMap<String, String>()
        for ((k, v) in prefs(context).all) {
            if (k.startsWith("cc_") && v is String && v.length == 2) out[k.removePrefix("cc_")] = v
        }
        return out
    }

    /**
     * Everything already measured, id -> ms, with -1 for a row that was measured and did not
     * answer. A figure between 0 and 9 is a local answer, not a server's, and is not served.
     */
    fun allDelays(context: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((k, v) in prefs(context).all) {
            if (k.startsWith("pd_") && v is Int && (v == -1 || v >= 10)) {
                out[k.removePrefix("pd_")] = v
            }
        }
        return out
    }

    /**
     * One short-lived core from this row, asked for the three things the app shows: how long
     * the round trip took, the address it came from, and that address's country. The country and
     * the address are kept under the row's id; the delay is the caller's to keep or drop.
     *
     * The delay comes back three ways, and telling two of them apart is the whole point:
     *   -1  the server answered nothing on the page - it is dead
     *   -2  nothing could be measured at all (no core started, no page read) - unknown
     *   n   the round trip in ms
     * Folding "could not measure" into "dead" is what emptied a list: with the tunnel up a second
     * core sometimes cannot start, every row then looks dead, and "remove the dead ones" takes
     * the whole store with it.
     *
     * A round trip under 10 ms is a local answer, not a server's, and counts as dead.
     */
    private fun readThrough(context: Context, id: String, row: Int): Triple<Int, String, String> {
        val port = port()
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return Triple(-2, "", "")
        try {
            val t0 = System.nanoTime()
            // the library hands back only the asked-for words, in that order, ";"-separated
            val parts = runCatching { NativeSec.probe(TRACE, "ip;loc", port).split(';') }
                .getOrDefault(emptyList())
            val took = ((System.nanoTime() - t0) / 1000000L).toInt()
            val ip = parts.getOrNull(0).orEmpty().trim()
            val cc = parts.getOrNull(1).orEmpty().trim().uppercase()
            if (parts.isEmpty()) return Triple(-2, "", "")
            // a round trip counts only when the page really answered through this server: a
            // handshake that finishes without carrying anything is not a delay, and the core's
            // own outbound measurement did report exactly that as a fine number
            val ms = if (cc.length != 2 || took < 10) -1 else took
            if (cc.length == 2) {
                cache[id] = cc
                val edit = prefs(context).edit().putString("cc_" + id.take(64), cc)
                if (ip.length in 7..45) edit.putString("pi_" + id.take(64), ip)
                edit.apply()
            }
            return Triple(ms, ip, cc)
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }

    private fun rowOf(id: String): Int =
        if (id.isBlank()) -1 else runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)

    /**
     * Where the server is, without a core: its resolved address and that address's country,
     * read by the library. The country is kept under the row's id, which is what the list paints.
     * "CC|address" when it knows, "" when it does not.
     */
    fun locate(context: Context, id: String): String {
        if (id.isBlank()) return ""
        val row = rowOf(id)
        if (row < 0) return ""
        val seen = NativeSec.locate(row).split(';')
        val ip = seen.getOrNull(0).orEmpty().trim()
        val cc = seen.getOrNull(1).orEmpty().trim().uppercase()
        if (cc.length != 2) return ""
        cache[id] = cc
        prefs(context).edit().putString("cc_" + id.take(64), cc).apply()
        return "$cc|$ip"
    }

    /**
     * The row's country by whichever way answers: the address lookup first, because a DNS answer
     * and a page are a fraction of what a core costs, and the trace page through a short-lived
     * core when the lookup says nothing (a host behind a network that has no country of its own).
     */
    fun country(context: Context, id: String): String {
        of(context, id).takeIf { it.isNotEmpty() }?.let { return it }
        locate(context, id).takeIf { it.isNotEmpty() }?.let { return it.substringBefore('|') }
        return probe(context, id)
    }

    /**
     * Milliseconds through the server itself; -1 when it does not answer.
     *
     * Measured the same way with the tunnel up and down, on purpose. The core's own outbound
     * measurement finishes when the handshake finishes, and a server that accepts a connection
     * and carries nothing is exactly what the user calls dead - so it kept answering a fine
     * number for rows that cannot reach anything.
     */
    fun delay(context: Context, id: String): Int {
        val row = rowOf(id)
        if (row < 0) return -2
        val ms = readThrough(context, id, row).first
        val tail = id.take(64)
        if (ms == -2) return -2
        // the verdict is kept both ways: -1 says "this one did not answer", and without it the
        // row looks merely unmeasured on the next visit, which is how a dead server came back
        // with a flag and a green figure beside it
        prefs(context).edit().putInt("pd_$tail", if (ms >= 10) ms else -1).apply()
        return if (ms >= 10) ms else -1
    }

    /**
     * The picked row's own answers: delay, exit country and exit IP, from one core. The home
     * card is the one place that shows the answer right away, so it is asked once.
     */
    fun info(context: Context, id: String): Map<String, Any?> {
        val p = prefs(context)
        val out = HashMap<String, Any?>()
        out["ms"] = -1
        out["cc"] = p.getString("cc_" + id.take(64), "") ?: ""
        out["ip"] = p.getString("pi_" + id.take(64), "") ?: ""
        val row = rowOf(id)
        if (row < 0) return out
        val seen = readThrough(context, id, row)
        out["ms"] = seen.first
        if (seen.second.isNotEmpty()) out["ip"] = seen.second
        if (seen.third.isNotEmpty()) out["cc"] = seen.third
        return out
    }

    /**
     * The row's country, measured now if no earlier run measured it. The same answer carries the
     * exit address, so the home card gets both from one probe instead of two.
     */
    fun probe(context: Context, id: String): String {
        if (id.isBlank()) return ""
        of(context, id).takeIf { it.isNotEmpty() }?.let { return it }
        val row = rowOf(id)
        if (row < 0) return ""
        return readThrough(context, id, row).third
    }

    /**
     * This handset's own exit. With the tunnel down that is a direct read - the carrier's
     * address. With it up a direct read would still answer the carrier, because the service
     * leaves this app outside its own VPN, so the exit is taken from the row the tunnel dials.
     *
     * [ask] is the difference between opening a page and pressing its button: without it the
     * answer is what an earlier fetch left here, so Home costs no request every time it is built.
     */
    fun mine(context: Context, ask: Boolean): Map<String, Any?> {
        val p = prefs(context)
        val out = HashMap<String, Any?>()
        if (ask && CoreService.on) {
            val id = CoreService.picked(context)
            val row = rowOf(id)
            if (row < 0) {
                out["ip"] = p.getString("pi_" + id.take(64), "") ?: ""
                out["cc"] = p.getString("cc_" + id.take(64), "") ?: ""
                return out
            }
            val seen = readThrough(context, id, row)
            out["ip"] = seen.second
            out["cc"] = seen.third
            return out
        }
        out["ip"] = p.getString("my_ip", "") ?: ""
        out["cc"] = p.getString("my_cc", "") ?: ""
        if (!ask) return out
        val parts = runCatching { NativeSec.probe(TRACE, "ip;loc", 0).split(';') }
            .getOrDefault(emptyList())
        val ip = parts.getOrNull(0).orEmpty().trim()
        val cc = parts.getOrNull(1).orEmpty().trim().uppercase()
        val edit = p.edit()
        if (ip.length in 7..45) {
            edit.putString("my_ip", ip)
            out["ip"] = ip
        }
        if (cc.length == 2) {
            edit.putString("my_cc", cc)
            out["cc"] = cc
        }
        edit.apply()
        return out
    }
}
