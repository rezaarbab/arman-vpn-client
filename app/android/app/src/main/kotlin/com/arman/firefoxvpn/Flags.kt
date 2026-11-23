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
            if (loc.length == 2) ((System.nanoTime() - t0) / 1000000L).toInt() else -1
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
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
