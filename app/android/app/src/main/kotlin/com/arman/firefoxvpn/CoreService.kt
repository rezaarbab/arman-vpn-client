package com.arman.firefoxvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ir.v2rayng.compose.core.NativeSec
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File
import java.util.Locale

/**
 * The VPN: this app's own core (libv2ray, driven through the C gate in
 * blobgate.c) sits behind a TUN device, and the store decides which server
 * the tunnel dials. The service exists while the VPN is on; its notification
 * carries the clock and the speed, and its Disconnect action ends it.
 *
 * The server is named by the admin's id, never by row number: a sync can
 * rewrite the store under us, so the row is looked up again at dial time.
 */
class CoreService : VpnService() {
    private val main = Handler(Looper.getMainLooper())

    /** The admin's name for the server on the wire (the notification's title). */
    @Volatile
    private var shownName = ""

    private var controller: CoreController? = null
    private var tunFd = -1
    private var lastUp = 0L
    private var lastDown = 0L
    private var rateUp = 0L
    private var rateDown = 0L
    private var lastTick = -1L

    private val notifier by lazy { getSystemService(NotificationManager::class.java) }

    private val stats = object : Runnable {
        override fun run() {
            val c = controller
            if (c == null) return
            try {
                val (up, down) = trafficDeltas(c.queryAllOutboundTrafficStats())
                if (up > 0 || down > 0) {
                    lastUp += up
                    lastDown += down
                    rateUp = up
                    rateDown = down
                } else {
                    rateUp = 0
                    rateDown = 0
                }
                traffic = Traffic(lastUp, lastDown, rateUp, rateDown)
            } catch (_: Exception) {
            }
            tick()
            main.postDelayed(this, 1000L)
        }
    }

    private val onCoreUp = Runnable {
        if (controller?.getIsRunning() == true) {
            connectedSince = System.currentTimeMillis()
            connected = true
            lastFailure = ""
            log("connected")
            notifier.notify(NOTIFICATION_ID, notification().build())
            ConnectWidget.updateAll(this)
            QuickTile.refresh(this)
        }
    }

    private val callback = object : CoreCallbackHandler {
        override fun onEmitStatus(p0: Long, p1: String?): Long {
            // the raw core text can carry the endpoint: it is not written anywhere
            main.removeCallbacks(onCoreUp)
            main.postDelayed(onCoreUp, 900L)
            return 0L
        }

        override fun startup(): Long {
            main.post(onCoreUp)
            return 0L
        }

        override fun shutdown(): Long {
            main.post {
                // the core ended on its own: the VPN cannot carry anything
                if (on) dropTunnel("the core stopped")
            }
            return 0L
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            turnOff(this)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (controller == null && tunFd < 0) dial()
        return START_NOT_STICKY
    }

    private fun dial() {
        // the gate reads the store, so it needs this context first (the
        // service can run in a process the activity never touched)
        NativeSec.giveCtx(applicationContext)
        val id = pickedId(this)
        if (id.isBlank()) {
            dropTunnel("no server picked")
            return
        }
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) {
            // the library does not have this server (yet): a sync brings it
            dropTunnel("the server is not in the store")
            return
        }
        shownName = runCatching { NativeSec.payloadText(row, 1) }.getOrDefault("")
        log("dialing")
        try {
            val builder = Builder()
                .setSession("Blob")
                .setMtu(1500)
                .addAddress("10.9.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .addDisallowedApplication(packageName)
            val tun = builder.setBlocking(true).establish()
            if (tun == null) {
                dropTunnel("the VPN device was refused")
                return
            }
            tunFd = tun.detachFd()
            Libv2ray.initCoreEnv(File(filesDir, "core").apply { mkdirs() }.absolutePath, "")
            val ctl = Libv2ray.newCoreController(callback)
            controller = ctl
            // the config is assembled inside the library: this process never
            // holds the address, the port or the id as text
            if (ctl == null || NativeSec.coreStartWith(ctl, row, tunFd) != 1) {
                val why = runCatching { NativeSec.errInfo() }.getOrDefault("")
                dropTunnel("the core refused the device [$why]")
                return
            }
            lastFailure = ""
            notifier.notify(NOTIFICATION_ID, notification().build())
            main.postDelayed(stats, 1000L)
        } catch (e: Exception) {
            // the user needs "it failed", not the exception text, which can carry the endpoint
            dropTunnel("the tunnel could not be set up (${e.javaClass.simpleName})")
        }
    }

    private fun dropTunnel(reason: String) {
        lastFailure = reason
        log(reason)
        connected = false
        connectedSince = 0L
        main.removeCallbacks(stats)
        main.removeCallbacks(onCoreUp)
        try {
            controller?.stopLoop()
        } catch (_: Exception) {
        }
        controller = null
        if (tunFd >= 0) {
            try {
                ParcelFileDescriptor.adoptFd(tunFd).close()
            } catch (_: Exception) {
            }
            tunFd = -1
        }
        traffic = Traffic(0, 0, 0, 0)
        on = false
        stopForegroundCompat()
        stopSelf()
        ConnectWidget.updateAll(this)
        QuickTile.refresh(this)
    }

    private fun tick() {
        if (connectedSince <= 0L) return
        val sec = (System.currentTimeMillis() - connectedSince) / 1000L
        if (sec == lastTick) return
        lastTick = sec
        try {
            notifier.notify(NOTIFICATION_ID, notification().build())
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            notifier.createNotificationChannel(
                NotificationChannel(CHANNEL, "Blob VPN", NotificationManager.IMPORTANCE_LOW),
            )
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification().build(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun stopForegroundCompat() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    /** No address in the notification: it is drawn on the lock screen. */
    private fun notification(): NotificationCompat.Builder {
        val title = shownName.ifBlank { "Blob VPN" }
        val live = if (connectedSince > 0L) {
            clock(System.currentTimeMillis() - connectedSince) +
                " · ↑ " + speed(rateUp.toDouble()) + " · ↓ " + speed(rateDown.toDouble())
        } else {
            "connecting"
        }
        val totals = "↑ " + human(lastUp) + "  ↓ " + human(lastDown)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setOngoing(true)
            .setContentText(live)
            .setStyle(NotificationCompat.BigTextStyle().setBigContentTitle(title).bigText("$totals\n$live"))
        val stop = android.app.PendingIntent.getService(
            this,
            7,
            Intent(this, CoreService::class.java).setAction(ACTION_DISCONNECT),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        b.addAction(0, "Disconnect", stop)
        return b
    }

    override fun onRevoke() {
        turnOff(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        connected = false
        main.removeCallbacks(stats)
        main.removeCallbacks(onCoreUp)
        try {
            controller?.stopLoop()
        } catch (_: Exception) {
        }
        controller = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "blob_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_DISCONNECT = "com.arman.firefoxvpn.DISCONNECT"
        private const val PREFS = "blob_core"

        const val MODE_VPN = "vpn"
        const val MODE_EXCLUDE = "exclude"
        const val BYPASS = "bypass"
        const val ONLY = "only"
        const val DECLINED = "declined"

        private val mainHandler = Handler(Looper.getMainLooper())

        /** The VPN is on: the service runs. */
        @Volatile
        var on = false
            internal set

        /** On and through: the core answered and the tunnel carries traffic. */
        @Volatile
        var connected = false
            internal set

        /** The last tunnel's failure, as one sentence; "" when none. */
        @Volatile
        var lastFailure = ""
            internal set

        /** When the tunnel came up, for the clock and for a test's freshness. */
        @Volatile
        var connectedSince = 0L
            internal set

        /** What the tunnel carried so far, and its last second's rate. */
        @Volatile
        var traffic = Traffic(0, 0, 0, 0)
            internal set

        data class Traffic(val up: Long, val down: Long, val upRate: Long, val downRate: Long)

        fun dataDir(context: Context): File = File(context.filesDir, "core")

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The row (by the admin's id) the tunnel dials; "" when none is picked. */
        fun pickedId(context: Context): String = prefs(context).getString("picked", "") ?: ""

        fun pick(context: Context, id: String) {
            prefs(context).edit().putString("picked", id).apply()
        }

        /** A phone, a tablet: the VPN is the only path (no system proxy mode). */
        fun usesVpn(context: Context): Boolean = true

        fun connectionMode(context: Context): String = MODE_VPN

        fun setConnectionMode(context: Context, mode: String) {}

        fun showSpeed(context: Context): Boolean = prefs(context).getBoolean("show_speed", false)

        fun setShowSpeed(context: Context, on: Boolean) {
            prefs(context).edit().putBoolean("show_speed", on).apply()
        }

        fun routingEnabled(context: Context): Boolean = false

        fun setRoutingEnabled(context: Context, on: Boolean) {}

        fun appMode(context: Context): String = MODE_EXCLUDE

        fun appSet(context: Context, kind: String): Set<String> = emptySet()

        fun setAppRouting(context: Context, mode: String, sets: Map<String, Set<String>>) {}

        fun suggest(context: Context, force: Boolean): Boolean = false

        fun regionAppList(): List<String> = emptyList()

        /** Recent one-sentence events for the app's log page; no address, no id. */
        private val logLines = ArrayDeque<Map<String, Any?>>()

        @Volatile
        private var lastTest: Map<String, Any?>? = null

        fun log(message: String) {
            synchronized(logLines) {
                val next = (logLines.lastOrNull()?.get("id") as? Int ?: 0) + 1
                logLines.addLast(mapOf("id" to next, "time" to isoTime(System.currentTimeMillis()), "level" to "INFO", "message" to message))
                while (logLines.size > 80) logLines.removeFirst()
            }
        }

        fun logsSince(after: Int): List<Map<String, Any?>> = synchronized(logLines) {
            logLines.filter { (it["id"] as Int) > after }
        }

        /**
         * The channel's view of the tunnel for the pages: the same fields the
         * core's dashboard used to answer with, filled from this process.
         */
        fun statusMap(context: Context): Map<String, Any?> {
            val id = pickedId(context)
            var name = ""
            if (id.isNotBlank()) {
                val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
                if (row >= 0) name = runCatching { NativeSec.payloadText(row, 1) }.getOrDefault("")
            }
            val t = traffic
            val phase = when {
                !on -> "off"
                !connected -> "starting"
                else -> "running"
            }
            return mapOf(
                "state" to mapOf(
                    "phase" to phase,
                    "message" to if (on && !connected) lastFailure else "",
                ),
                "proxy" to if (connected) {
                    mapOf("listen" to "", "started_at" to isoTime(connectedSince))
                } else {
                    null
                },
                "traffic" to mapOf(
                    "up" to t.up,
                    "down" to t.down,
                    "up_rate" to t.upRate,
                    "down_rate" to t.downRate,
                ),
                "connect_pending" to (on && !connected),
                "connect_failure" to if (on) lastFailure else "",
                "selection" to id,
                "selection_name" to name,
                "tun_supported" to true,
                "last_test" to lastTest,
            )
        }

        /**
         * The tunnel's own answer for the home page: up or not. The ping is a latency
         * sample beside it, never the verdict - a host that drops ICMP/TCP probes
         * would otherwise paint a working tunnel yellow forever.
         */
        fun testPing(context: Context): Map<String, Any?> {
            val id = pickedId(context)
            val row = if (id.isBlank()) -1 else runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
            val ms = if (row < 0) -1 else NativeSec.tcpPing(row, 5000)
            val m = mapOf(
                "at" to isoTime(System.currentTimeMillis()),
                "ok" to connected,
                "target" to "tcp",
                "response_ms" to ms,
                "total_ms" to ms,
            )
            lastTest = m
            return m
        }

        private fun isoTime(ms: Long): String {
            if (ms <= 0L) return ""
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date(ms))
        }

        /** Turns the VPN on; the caller has the Android permission by now. */
        fun turnOn(context: Context) {
            lastFailure = ""
            on = true
            connected = false
            // a store nobody has chosen from yet dials its first row
            NativeSec.giveCtx(context.applicationContext)
            if (pickedId(context).isBlank() && IrRows.count() > 0) pick(context, IrRows.id(0))
            val intent = Intent(context, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            QuickTile.refresh(context)
            ConnectWidget.updateAll(context)
        }

        fun turnOff(context: Context) {
            on = false
            connected = false
            log("disconnected")
            context.stopService(Intent(context, CoreService::class.java))
            QuickTile.refresh(context)
            ConnectWidget.updateAll(context)
        }

        /** A different server: the tunnel goes down and up again on the new pick. */
        fun reconnect(context: Context) {
            if (!on) return
            context.stopService(Intent(context, CoreService::class.java))
            mainHandler.postDelayed({ turnOn(context) }, 700L)
        }

        fun stopCore() {
            on = false
            connected = false
        }

        /** Kept for the channel's "startCore": our core starts with the tunnel. */
        fun startCore(context: Context, on: Boolean): String? = null
    }
}

/** "12:03" for a session's length. */
internal fun clock(ms: Long): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** "1.4 MB/s" for a rate. */
internal fun speed(bytes: Double): String = human(bytes.toLong()) + "/s"

internal fun human(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "$bytes ${units[0]}" else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** The core reports "tag,dir,bytes;" chunks; only the proxy's count here. */
internal fun trafficDeltas(raw: String): Pair<Long, Long> {
    var up = 0L
    var down = 0L
    for (chunk in raw.split(';')) {
        val parts = chunk.split(',')
        if (parts.size < 3) continue
        if (parts[0].trim() != "proxy") continue
        val value = parts[2].trim().toLongOrNull() ?: continue
        when (parts[1].trim()) {
            "uplink" -> up += value
            "downlink" -> down += value
        }
    }
    return up to down
}
