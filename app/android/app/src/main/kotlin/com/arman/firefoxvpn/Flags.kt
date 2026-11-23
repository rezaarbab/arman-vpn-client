package com.arman.firefoxvpn

import android.content.Context
import ir.v2rayng.compose.core.NativeSec
import libv2ray.CoreCallbackHandler
import kotlin.random.Random

/**
 * A server's country, the way the app of origin learned it: a short-lived core is started
 * from the row, the tunnel-down handset asks Cloudflare's trace page through it, and the two
 * letters are kept under the row's id. Nothing about the row's address is read here.
 *
 * Only while the VPN is down: a probe core beside a running tunnel is two cores on one phone.
 */
object Flags {
    private val cache = HashMap<String, String>()

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
        if (CoreService.on || id.isBlank()) return -1
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return -1
        val port = 20000 + Random.nextInt(2000)
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return -1
        return try {
            val t0 = System.nanoTime()
            val loc = NativeSec.probe("https://www.cloudflare.com/cdn-cgi/trace", "loc", port)
            if (loc.length == 2) {
                val raw = ((System.nanoTime() - t0) / 1000000L).toInt()
                // a round trip through a remote server is never 1 ms: that is a local
                // answer and would paint a dead row green
                val ms = if (raw < 10) -1 else raw
                context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
                    .edit().putInt("pd_" + id.take(64), ms).apply()
                ms
            } else {
                -1
            }
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }

    /** Everything already measured, id -> ms. */
    fun allDelays(context: Context): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((k, v) in context.getSharedPreferences("blob_core", Context.MODE_PRIVATE).all) {
            if (k.startsWith("pd_") && v is Int && v > 0) out[k.removePrefix("pd_")] = v
        }
        return out
    }

    /** The picked row's own answers: delay, exit country and exit IP. */
    fun info(context: Context, id: String): Map<String, Any?> {
        val p = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        val out = HashMap<String, Any?>()
        out["ms"] = delay(context, id)
        out["cc"] = p.getString("cc_" + id.take(64), "") ?: ""
        out["ip"] = p.getString("pi_" + id.take(64), "") ?: ""
        if (CoreService.on || id.isBlank()) return out
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return out
        val port = 20000 + Random.nextInt(2000)
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return out
        try {
            val trace = NativeSec.probe("https://www.cloudflare.com/cdn-cgi/trace", "loc,ip", port)
            var cc = ""
            var ip = ""
            for (line in trace.split('\n')) {
                if (line.startsWith("loc=")) cc = line.removePrefix("loc=").trim().uppercase()
                if (line.startsWith("ip=")) ip = line.removePrefix("ip=").trim()
            }
            if (cc.length == 2) {
                p.edit().putString("cc_" + id.take(64), cc).apply()
                out["cc"] = cc
            }
            if (ip.length in 7..45) {
                p.edit().putString("pi_" + id.take(64), ip).apply()
                out["ip"] = ip
            }
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
        return out
    }

    fun probe(context: Context, id: String): String {
        if (CoreService.on || id.isBlank()) return ""
        of(context, id).takeIf { it.isNotEmpty() }?.let { return it }
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) return ""
        val port = 20000 + Random.nextInt(2000)
        val quiet = object : CoreCallbackHandler {
            override fun startup(): Long = 0L
            override fun shutdown(): Long = 0L
            override fun onEmitStatus(p0: Long, p1: String?): Long = 0L
        }
        val handle = runCatching { NativeSec.probeStart(row, port, quiet) }.getOrDefault(0)
        if (handle == 0) return ""
        return try {
            val code = NativeSec.probe(
                "https://www.cloudflare.com/cdn-cgi/trace",
                "loc",
                port,
            ).trim().uppercase()
            if (code.length == 2) {
                cache[id] = code
                context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
                    .edit().putString("cc_" + id.take(64), code).apply()
                code
            } else {
                ""
            }
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }
}
