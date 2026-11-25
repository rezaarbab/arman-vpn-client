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
    private var statTick = 0

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
            // every five seconds the row's own line is written down, so the list has a
            // number to show whether or not this session is still running
            statTick = (statTick + 1) % 5
            if (statTick == 0) keepStatsRow()
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
            judgeChain()
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
        instance = this
        if (intent?.action == ACTION_DISCONNECT) {
            turnOff(this)
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (!wantOn) {
            // stopped on purpose: a stale start does not put the tunnel back up
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (controller == null && tunFd < 0) dial()
        return START_NOT_STICKY
    }

    private fun dial() {
        // the gate reads the store, so it needs this context first (the
        // service can run in a process the activity never touched)
        NativeSec.giveCtx(applicationContext)
        if (Sync.adminOff(this)) {
            // the panel's own command reaches every handset at once, so this is
            // not a failure of the server or of the phone
            dropTunnel("the operator has switched this app off")
            return
        }
        if (Admin.updateBlocks(this)) {
            dropTunnel("the panel asks for a newer version first")
            return
        }
        val id = pickedId(this)
        if (id.isBlank()) {
            dropTunnel("no server picked")
            return
        }
        pickedIdOfLastDial = id
        val row = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
        if (row < 0) {
            // the library does not have this server (yet): a sync brings it
            dropTunnel("the server is not in the store")
            return
        }
        shownName = runCatching { NativeSec.payloadText(row, 1) }.getOrDefault("")
        // The clock under the button starts here, not when the core says it is
        // up: the callback can come late, or not at all after a background, and
        // a running tunnel must not lose its own uptime.
        dialStarted = System.currentTimeMillis()
        log("dialing")
        try {
            val builder = Builder()
                .setSession("Blob")
                .setMtu(1500)
                .addAddress("10.9.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .addDisallowedApplication(packageName)
            var kept = 1
            for (pkg in appSet(this, BYPASS)) {
                try {
                    builder.addDisallowedApplication(pkg)
                    kept++
                } catch (_: Exception) {
                }
            }
            log("apps outside the tunnel: $kept")
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
        teardown()
        on = false
        wantOn = false
        stopForegroundCompat()
        stopSelf()
        ConnectWidget.updateAll(this)
        QuickTile.refresh(this)
    }

    /**
     * Everything the tunnel owns, closed now. A stop asked for by the UI may never reach
     * onDestroy (the log showed "stopping the service" with no "service stopped"), and an
     * fd left open is a VPN icon with nothing behind it, so the close does not wait.
     */
    fun teardown() {
        keepStats()
        connected = false
        chainJudged = false
        connectedSince = 0L
        dialStarted = 0L
        main.removeCallbacks(stats)
        main.removeCallbacks(onCoreUp)
        try {
            controller?.stopLoop()
        } catch (_: Exception) {
        }
        controller = null
        closeTun()
        traffic = Traffic(0, 0, 0, 0)
        stopForegroundCompat()
    }

    /** The device the tunnel borrows: detachFd handed the raw fd to the core, and
     *  an fd nobody closes keeps Android's VPN icon up with no tunnel behind it. */
    private fun closeTun() {
        if (tunFd >= 0) {
            try {
                ParcelFileDescriptor.adoptFd(tunFd).close()
            } catch (_: Exception) {
            }
            tunFd = -1
        }
    }

    /** What this session carried, kept per server, so the list can show it later. */
    private fun keepStats() {
        val p0 = prefs(this)
        p0.edit()
            .putLong("tot_up", p0.getLong("tot_up", 0L) + lastUp)
            .putLong("tot_down", p0.getLong("tot_down", 0L) + lastDown)
            .apply()
        keepStatsRow()
        log("session kept")
    }

    /**
     * The running session's line, written as it goes rather than only at the end.
     *
     * Two things used to blank a row: the number was stored only when the tunnel closed - so a
     * process that died, or a page read while the tunnel was still up, showed nothing - and it
     * was filed under whoever was picked at that moment, which after a delete or a switch is
     * not the server that carried the traffic.
     */
    private fun keepStatsRow() {
        val id = pickedIdOfLastDial
        if (id.isBlank() || connectedSince <= 0L) return
        val ms = System.currentTimeMillis() - connectedSince
        prefs(this).edit()
            .putString("stats_" + id.take(64), "$ms|$lastUp|$lastDown")
            .apply()
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
        log("service stopped")
        teardown()
        instance = null
        keepStats()
        closeTun()
        connected = false
        chainJudged = false
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

        /** When this dial was asked for. The uptime falls back to it when the
         *  core's up-callback is late, so the line under the button does not
         *  disappear on a visit to another app. */
        @Volatile
        var dialStarted = 0L
            internal set

        /** What the tunnel carried so far, and its last second's rate. */
        @Volatile
        var traffic = Traffic(0, 0, 0, 0)
            internal set

        /** The running service, so a stop from the UI closes the tunnel itself. */
        @Volatile
        private var instance: CoreService? = null

        /** An explicit stop wins: a start intent that arrives after it stays out. */
        @Volatile
        var wantOn = false
            internal set

        data class Traffic(val up: Long, val down: Long, val upRate: Long, val downRate: Long)

        /** Everything this app has carried, and this session's share of it. */
        fun totalMap(context: Context): Map<String, Any?> {
            val p = prefs(context)
            return mapOf(
                "up" to p.getLong("tot_up", 0L) + traffic.up,
                "down" to p.getLong("tot_down", 0L) + traffic.down,
            )
        }

        fun resetAllStats(context: Context) {
            val p = prefs(context)
            val e = p.edit()
            for (k in p.all.keys) if (k.startsWith("stats_")) e.remove(k)
            e.putLong("tot_up", 0L).putLong("tot_down", 0L).apply()
            log("all traffic reset")
        }

        fun lastDelay(context: Context, id: String): Int =
            if (id.isBlank()) -1 else prefs(context).getInt("pd_" + id.take(64), -1)

        fun resetTotal(context: Context) {
            prefs(context).edit().putLong("tot_up", 0L).putLong("tot_down", 0L).apply()
            log("traffic total reset")
        }

        /** The tunnel as the list shows it: which row, for how long, how much. */
        fun liveMap(): Map<String, Any?> = mapOf(
            "id" to pickedIdOfLastDial,
            "on" to connected,
            "ms" to if (connectedSince > 0L) System.currentTimeMillis() - connectedSince else 0L,
            "up" to traffic.up,
            "down" to traffic.down,
        )

        /** Every kept session, id -> "ms|up|down". */
        fun statsAll(context: Context): Map<String, String> {
            val out = HashMap<String, String>()
            for ((k, v) in prefs(context).all) {
                if (k.startsWith("stats_") && v is String) out[k.removePrefix("stats_")] = v
            }
            return out
        }

        /** The last session of each named row: "ms|up|down", or absent. */
        fun statsFor(context: Context, ids: List<String>): Map<String, String> {
            val p = prefs(context)
            val out = HashMap<String, String>()
            for (id in ids) {
                val v = p.getString("stats_" + id.take(64), null) ?: continue
                out[id] = v
            }
            return out
        }

        fun picked(context: Context): String = pickedId(context)

        @Volatile
        var pickedIdOfLastDial = ""
            internal set

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

        /** The apps the page ticked: they do not go through the tunnel. */
        fun appSet(context: Context, kind: String): Set<String> {
            if (kind != BYPASS) return emptySet()
            val raw = prefs(context).getString("apps_bypass", "") ?: ""
            if (raw.isBlank()) return emptySet()
            return raw.split('\n').filter { it.isNotBlank() }.toSet()
        }

        fun setAppRouting(context: Context, mode: String, sets: Map<String, Set<String>>) {
            // both layouts save their ticks in the bypass row; only that row is applied
            val bypass = sets[BYPASS] ?: sets[ONLY] ?: emptySet()
            prefs(context).edit().putString("apps_bypass", bypass.joinToString("\n")).apply()
            log("per-app: " + bypass.size + " apps stay outside the tunnel")
        }

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
            // up is up: the core's start may be reported late (or never after the app
            // returns from the background), and a green tunnel must not turn yellow for it
            val phase = if (on) "running" else "off"
            return mapOf(
                "state" to mapOf(
                    "phase" to phase,
                    "message" to if (on && !connected) lastFailure else "",
                ),
                "proxy" to if (on) {
                    // the tunnel is asked for whenever it is wanted and up: the
                    // core's own up-callback can be late, and a missing block
                    // here blanks the uptime and the pills under the button
                    mapOf(
                        "listen" to "",
                        "started_at" to isoTime(
                            if (connectedSince > 0L) connectedSince else dialStarted
                        ),
                    )
                } else {
                    null
                },
                "traffic" to mapOf(
                    "up" to (prefs(context).getLong("tot_up", 0L) + t.up),
                    "down" to (prefs(context).getLong("tot_down", 0L) + t.down),
                    "up_rate" to t.upRate,
                    "down_rate" to t.downRate,
                ),
                "connect_pending" to false,
                "connect_failure" to if (on) lastFailure else "",
                // the panel's own switch over this whole app: the page says so
                // rather than letting the button look like it failed
                "admin_off" to Sync.adminOff(context),
                "selection" to id,
                "selection_name" to name,
                "tun_supported" to true,
                "last_test" to lastTest,
            )
        }

        /**
         * The tunnel's own answer for the home page: up or not. The number beside it is a real
         * round trip to the server - a raw tcp connect answers 1-2 ms for a host that accepts the
         * socket and does nothing else, and that figure used to be shown as the delay.
         */
        fun testPing(context: Context): Map<String, Any?> {
            val id = pickedId(context)
            val ms = Flags.delay(context, id)
            val m = mapOf(
                "at" to isoTime(System.currentTimeMillis()),
                "ok" to (on || connected),
                "target" to "trace",
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
        fun turnOn(context: Context): Boolean {
            if (Sync.adminOff(context)) {
                // refused before anything is set: a button that goes to "connecting"
                // and comes back on its own says the tunnel failed, not that it was
                // stopped on purpose
                log("connect: the operator has switched this app off")
                ConnectWidget.updateAll(context)
                return false
            }
            if (Admin.updateBlocks(context)) {
                log("connect: the panel asks for a newer version first")
                ConnectWidget.updateAll(context)
                return false
            }
            lastFailure = ""
            wantOn = true
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
            return true
        }

        fun turnOff(context: Context) {
            wantOn = false
            log("stopping the service")
            // close the tunnel here: onDestroy has been seen not to arrive
            instance?.teardown()
            instance = null
            on = false
            connected = false
            log("disconnected")
            context.stopService(Intent(context, CoreService::class.java))
            QuickTile.refresh(context)
            ConnectWidget.updateAll(context)
        }

        /**
         * A second hop that cannot carry leaves the phone with no internet, and the only proof
         * that it did is a page asked through the tunnel. So the tunnel asks, once per session,
         * and gives up the hop rather than staying up over an empty path.
         *
         * Judged once and never again for that hop: dropping it re-dials with no second server,
         * and a second judgement could drop a working hop on one slow page.
         */
        @Volatile
        private var chainJudged = false

        private fun judgeChain() {
            val ctx = instance ?: return
            if (Privacy.chainId(ctx).isEmpty()) return
            if (chainJudged) return
            chainJudged = true
            Thread {
                when (Privacy.carries(ctx)) {
                    0 -> {
                        Privacy.set(ctx, Privacy.on(ctx), "")
                        log("chain: nothing arrived through the second hop - the tunnel is back on one server")
                        reconnect(ctx)
                    }
                    1 -> log("chain: a page arrived through the second hop")
                    else -> log("chain: not judged - the pages do not answer from this network either")
                }
            }.start()
        }

        /** A different server: the tunnel goes down and up again on the new pick. */
        fun reconnect(context: Context) {
            if (!on) return
            context.stopService(Intent(context, CoreService::class.java))
            // immediately: a phone asking for another server does not wait
            mainHandler.post({ turnOn(context) })
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
