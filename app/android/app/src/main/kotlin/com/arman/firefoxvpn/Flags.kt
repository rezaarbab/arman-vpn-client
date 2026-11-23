package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec
import libv2ray.CoreCallbackHandler
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server's country, the way the app of origin learned it: a short-lived core is started
 * from the row, the tunnel-down handset asks Cloudflare's trace page through it, and the two
 * letters are kept under the row's id. Nothing about the row's address is read here.
 *
 * Only while the VPN is down: a probe core beside a running tunnel is two cores on one phone.
 * Several rows are probed at once, so their local ports come from a counter, not from a
 * draw that can hand two of them the same one.
 */
object Flags {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val portSeed = AtomicInteger(0)

    private fun port(): Int = 20000 + ((portSeed.getAndIncrement() and 0x7fffffff) % 2000)

    private const val TRACE = "https://www.cloudflare.com/cdn-cgi/trace"

    fun of(context: Context, id: String): String {
        cache[id]?.let { return it }
        val v = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
            .getString("cc_" + id.take(64), "") ?: ""
        if (v.length == 2) cache[id] = v
        return v
    }

    /** Everything already learned in an earlier run, id -> country. */
    fun all(context: Context): Map<String, String> {
        val p = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        val out = HashMap<String, String>()
        for ((k, v) in p.all) {
            if (k.startsWith("cc_") && v is String && v.length == 2) out[k.removePrefix("cc_")] = v
        }
        return out
    }

    /** Milliseconds through the server itself; -1 when it does not answer. */
    fun delay(context: Context, id: String): Int {
        if (id.isBlank()) return -1
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return -1
        if (CoreService.on) {
            // the tunnel is up: the row is measured through the core that is already running,
            // because a probe core beside the tunnel would be a second one on the phone
            val ms = NativeSec.delayOf(row, TRACE).toInt()
            val v = if (ms < 10) -1 else ms
            if (v > 0) context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
                .edit().putInt("pd_" + id.take(64), v).apply()
            return v
        }
        val port = port()
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return -1
        return try {
            val t0 = System.nanoTime()
            // the same answer carries the country and the exit IP: a ping round
            // fills the flags for free, while a flag round never writes a ping
            val parts = runCatching { NativeSec.probe(TRACE, "ip;loc", port).split(';') }
                .getOrDefault(emptyList())
            val cc = parts.getOrNull(1).orEmpty().trim().uppercase()
            val ip = parts.getOrNull(0).orEmpty().trim()
            if (cc.length != 2) {
                -1
            } else {
                val raw = ((System.nanoTime() - t0) / 1000000L).toInt()
                // a round trip through a remote server is never 1 ms: that is a local
                // answer and would paint a dead row green
                val ms = if (raw < 10) -1 else raw
                val edit = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE).edit()
                if (ms > 0) edit.putInt("pd_" + id.take(64), ms)
                cache[id] = cc
                edit.putString("cc_" + id.take(64), cc)
                if (ip.length in 7..45) edit.putString("pi_" + id.take(64), ip)
                edit.apply()
                ms
            }
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }

    /** Everything already measured, id -> ms. Under 10 ms is a local answer, not a server. */
    fun allDelays(context: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((k, v) in context.getSharedPreferences("blob_core", Context.MODE_PRIVATE).all) {
            if (k.startsWith("pd_") && v is Int && v >= 10) out[k.removePrefix("pd_")] = v
        }
        return out
    }

    /**
     * The picked row's own answers: delay, exit country and exit IP - from one core. Two probes
     * would ask the same server twice, and the home card is the one place that shows the answer
     * right away.
     */
    fun info(context: Context, id: String): Map<String, Any?> {
        val p = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        val out = HashMap<String, Any?>()
        out["ms"] = -1
        out["cc"] = p.getString("cc_" + id.take(64), "") ?: ""
        out["ip"] = p.getString("pi_" + id.take(64), "") ?: ""
        if (id.isBlank()) return out
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return out
        if (CoreService.on) {
            // the tunnel is up: the round trip goes through the core that is already running,
            // and the country and the IP are what an earlier probe left in the store
            out["ms"] = delay(context, id)
            return out
        }
        val port = port()
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return out
        try {
            val t0 = System.nanoTime()
            // the library hands back only the asked-for words, in that order, ";"-separated
            val parts = runCatching {
                NativeSec.probe(TRACE, "ip;loc", port).split(';')
            }.getOrDefault(emptyList())
            val took = ((System.nanoTime() - t0) / 1000000L).toInt()
            val ip = parts.getOrNull(0).orEmpty().trim()
            val cc = parts.getOrNull(1).orEmpty().trim().uppercase()
            if (cc.length == 2) {
                cache[id] = cc
                p.edit().putString("cc_" + id.take(64), cc).apply()
                out["cc"] = cc
            }
            if (ip.length in 7..45) {
                p.edit().putString("pi_" + id.take(64), ip).apply()
                out["ip"] = ip
            }
            // a round trip through a remote server is never under 10 ms: that is a local
            // answer, and the card would paint a dead row green
            if (parts.isNotEmpty() && took >= 10) out["ms"] = took
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
        return out
    }

    /**
     * The row's country, measured now if no earlier run measured it. The same answer carries the
     * exit IP, so the home card gets both from one probe instead of two.
     */
    fun probe(context: Context, id: String): String {
        if (CoreService.on || id.isBlank()) return ""
        of(context, id).takeIf { it.isNotEmpty() }?.let { return it }
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return ""
        val port = port()
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return ""
        return try {
            val parts = runCatching {
                NativeSec.probe(TRACE, "ip;loc", port).split(';')
            }.getOrDefault(emptyList())
            val cc = parts.getOrNull(1).orEmpty().trim().uppercase()
            val ip = parts.getOrNull(0).orEmpty().trim()
            if (cc.length != 2) return ""
            cache[id] = cc
            val edit = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
                .edit().putString("cc_" + id.take(64), cc)
            if (ip.length in 7..45) edit.putString("pi_" + id.take(64), ip)
            edit.apply()
            cc
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }

    /**
     * This handset's own exit, over whatever path it is using right now: the page is fetched
     * directly, with no core of its own, so with the tunnel down it is the carrier's address and
     * with the tunnel up it is the one the server hands out.
     *
     * [ask] is the difference between opening a page and pressing its button: without it the
     * answer is what an earlier fetch left here, so Home costs no request every time it is built.
     */
    fun mine(context: Context, ask: Boolean): Map<String, Any?> {
        val p = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        val out = HashMap<String, Any?>()
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
