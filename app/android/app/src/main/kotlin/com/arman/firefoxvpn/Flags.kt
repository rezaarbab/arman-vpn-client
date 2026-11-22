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

    fun of(id: String): String = cache[id] ?: ""

    fun probe(context: Context, id: String): String {
        if (CoreService.on || id.isBlank()) return ""
        cache[id]?.let { return it }
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
                code
            } else {
                ""
            }
        } finally {
            runCatching { NativeSec.probeStop(handle) }
        }
    }
}
