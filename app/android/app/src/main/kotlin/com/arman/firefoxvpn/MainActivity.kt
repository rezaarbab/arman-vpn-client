package com.arman.firefoxvpn

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.OpenableColumns
import android.service.quicksettings.TileService
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result

/**
 * The Flutter UI. Channel "app/android": dataDir, startCore (with the proxy
 * off), setOn, isRunning, openUrl. The UI talks to the core itself through
 * its HTTP API on loopback, as on the desktop.
 */
class MainActivity : FlutterActivity() {
    private var androidChannel: MethodChannel? = null
    /** Waits for the user's answer to the VPN permission. */
    private var pendingOn: Result? = null
    // A backup waiting for the place the user picks to save it.
    private var pendingSave: Pair<ByteArray, Result>? = null
    // A backup being picked to open.
    private var pendingOpen: Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        holdPhoneUpright(resources.configuration)
        super.onCreate(savedInstanceState)
        // the C gate holds the keystore, the prefs and the sync: it needs the
        // context before the store is read
        ir.v2rayng.compose.core.NativeSec.giveCtx(applicationContext)
        removeOtherCards()
    }

    /** A tile long-press returns an already open UI to its home page. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == TileService.ACTION_QS_TILE_PREFERENCES) {
            androidChannel?.invokeMethod("openHome", null)
        }
    }

    /**
     * One card in the recent apps. A card stays there after Back, and with no
     * task affinity (the manifest's, against task hijacking) Android keeps it
     * beside a new task opened with another intent: from the notification,
     * the tile, the widget, or the installer's Open.
     */
    private fun removeOtherCards() {
        for (task in getSystemService(ActivityManager::class.java).appTasks) {
            try {
                @Suppress("DEPRECATION") // taskId came only with Android 10.
                if (task.taskInfo.persistentId != taskId) task.finishAndRemoveTask()
            } catch (e: IllegalArgumentException) {
                // That task went by itself meanwhile.
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        holdPhoneUpright(newConfig)
    }

    /**
     * A phone stays upright (its layout is made for it); a tablet, whose
     * shorter side is 600 dp or more, turns freely. Asked again when the
     * screen changes, so a foldable follows its folding. A TV (960 x 540 dp,
     * so "short" too) cannot turn: it keeps its own orientation.
     */
    private fun holdPhoneUpright(config: Configuration) {
        val wanted = if (config.smallestScreenWidthDp < 600 && !isTv(config)) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    /** Whether this is a TV (Android TV, Google TV), worked with a remote. */
    private fun isTv(config: Configuration) =
        config.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "app/android")
        androidChannel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "dataDir" -> result.success(CoreService.dataDir(this).absolutePath)
                "startCore" -> result.success(CoreService.startCore(this, on = false))
                // The UI reads status through the channel now; there is no
                // dashboard server in this app.
                "dashboardAddress" -> result.success("")
                "setOn" -> {
                    if (call.argument<Boolean>("on") != true) {
                        CoreService.turnOff(this)
                        result.success(null)
                    } else {
                        askForNotifications()
                        // The first time, Android asks the user to allow the
                        // VPN; proxy mode needs no VPN.
                        val consent = if (CoreService.usesVpn(this)) VpnService.prepare(this) else null
                        if (consent == null) {
                            turnOn(result)
                        } else {
                            pendingOn?.error("vpn_denied", "superseded", null)
                            pendingOn = result
                            startActivityForResult(consent, VPN_REQUEST)
                        }
                    }
                }
                "blobStatus" -> result.success(CoreService.statusMap(this))
                "blobPower" -> {
                    val want = call.argument<Boolean>("on") == true
                    CoreService.log(if (want) "power on requested" else "power off requested")
                    if (!want) {
                        CoreService.turnOff(this)
                        result.success(null)
                    } else {
                        askForNotifications()
                        val consent = VpnService.prepare(this)
                        if (consent == null) {
                            turnOn(result)
                        } else {
                            pendingOn?.error("vpn_denied", "superseded", null)
                            pendingOn = result
                            startActivityForResult(consent, VPN_REQUEST)
                        }
                    }
                }
                "blobTest" -> inAsk(result) { CoreService.testPing(this) }
                "blobLogs" -> result.success(CoreService.logsSince(call.argument<Int>("after") ?: 0))
                "irPick" -> {
                    CoreService.pick(this, call.argument<String>("id") ?: "")
                    if (CoreService.on) CoreService.reconnect(this)
                    result.success(null)
                }
                "irPicked" -> result.success(CoreService.pickedId(this))
                "irPins" -> result.success(IrRows.pins(this))
                "irDropCount" -> result.success(IrRows.dropCount(this))
                "irUndrop" -> result.success(IrRows.clearDrops(this))
                // the panel's own switch over the whole app, in both directions
                "irAdmFlags" -> result.success(Sync.adminFlags(this))
                "blobPrivacy" -> {
                    // healed before it is read, so the page and the library see the same thing
                    Privacy.migrate(this)
                    result.success(
                        mapOf(
                            "on" to Privacy.on(this),
                            "chainId" to Privacy.chainId(this),
                            "chainName" to Privacy.chainName(this),
                            "dropped" to Privacy.dropped(this),
                            "chainInStore" to (Privacy.chainId(this).isNotEmpty() &&
                                runCatching {
                                    ir.v2rayng.compose.core.NativeSec.rowOf(Privacy.chainId(this))
                                }.getOrDefault(-1) >= 0),
                        ),
                    )
                }
                "blobPrivacySet" -> {
                    Privacy.set(
                        this,
                        call.argument<Boolean>("on") == true,
                        call.argument<String>("chainId") ?: "",
                    )
                    result.success(null)
                }
                "blobPrivacyTest" -> inAsk(result) { Privacy.test(this) }
                // every question the network can answer, in one tap
                "blobPrivacyDebug" -> inAsk(result) { Privacy.debug(this) }
                "irAdmin" -> result.success(
                    mapOf(
                        "popup" to Admin.popup(this),
                        "update" to Admin.update(this),
                        "ad" to Admin.ad(this),
                    ),
                )
                "irAdminSeen" -> {
                    Admin.dismiss(
                        this,
                        call.argument<String>("kind") ?: "",
                        call.argument<String>("id") ?: "",
                    )
                    result.success(null)
                }
                "irPin" -> result.success(
                    IrRows.pin(
                        this,
                        call.argument<String>("id") ?: "",
                        call.argument<Boolean>("on") == true,
                    )
                )
                // The row leaves the store, the sealed copy and the caches. The tunnel is not
                // dropped with it: it is already running on a config that was made, and stopping a
                // working connection because one entry left the list would be its own surprise.
                "irRowDrop" -> {
                    val ids = (call.argument<List<String>>("ids") ?: emptyList()) +
                        (call.argument<String>("id") ?: "")
                    val kept = IrRows.drop(this, ids.filter { it.isNotBlank() })
                    val picked = CoreService.pickedId(this)
                    if (kept >= 0 && picked.isNotBlank() && ids.contains(picked)) {
                        CoreService.pick(this, "")
                    }
                    result.success(kept)
                }
                "isRunning" -> result.success(CoreService.on)
                "connectionMode" -> result.success(CoreService.connectionMode(this))
                "showSpeed" -> result.success(CoreService.showSpeed(this))
                "setShowSpeed" -> {
                    CoreService.setShowSpeed(this, call.argument<Boolean>("on") == true)
                    result.success(null)
                }
                "routingEnabled" -> result.success(CoreService.routingEnabled(this))
                "setRoutingEnabled" -> {
                    CoreService.setRoutingEnabled(this, call.argument<Boolean>("on") == true)
                    result.success(null)
                }
                "setConnectionMode" -> {
                    val mode = call.argument<String>("mode") ?: CoreService.MODE_VPN
                    // A running proxy switched to the VPN needs the permission
                    // first; the app turns it off and on again to ask.
                    if (CoreService.on && mode == CoreService.MODE_VPN && VpnService.prepare(this) != null) {
                        result.error("vpn_denied", "the VPN is not allowed yet", null)
                    } else {
                        CoreService.setConnectionMode(this, mode)
                        result.success(null)
                    }
                }
                "installedApps" -> inBackground(result) { installedApps() }
                "appIcon" -> {
                    val pkg = call.argument<String>("package") ?: ""
                    inBackground(result) { appIcon(pkg) }
                }
                "appRouting" -> inBackground(result) {
                    // The page shows the region's apps installed since the
                    // last look checked; the VPN takes them at once.
                    if (CoreService.appMode(this) == CoreService.MODE_EXCLUDE &&
                        CoreService.suggest(this, force = true)
                    ) {
                        CoreService.reconnect(this)
                    }
                    mapOf(
                        "mode" to CoreService.appMode(this),
                        "bypass" to CoreService.appSet(this, CoreService.BYPASS).toList(),
                        "only" to CoreService.appSet(this, CoreService.ONLY).toList(),
                        "declined" to CoreService.appSet(this, CoreService.DECLINED).toList(),
                        "region" to CoreService.regionAppList(),
                    )
                }
                "setAppRouting" -> {
                    fun set(key: String) = (call.argument<List<String>>(key) ?: emptyList()).toSet()
                    CoreService.setAppRouting(
                        this,
                        call.argument<String>("mode") ?: CoreService.MODE_EXCLUDE,
                        mapOf(
                            CoreService.BYPASS to set("bypass"),
                            CoreService.ONLY to set("only"),
                            CoreService.DECLINED to set("declined"),
                        ),
                    )
                    if (call.argument<Boolean>("apply") == true) CoreService.reconnect(this)
                    result.success(null)
                }
                // The browser sign-in's WebView goes through the core's
                // sign-in proxy (an address), or straight out again (null).
                // The override covers every WebView of this app, and takes
                // effect once the callback runs.
                "setWebViewProxy" -> {
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    val address = call.argument<String>("address")
                    val proxies = ProxyController.getInstance()
                    val done = Runnable { result.success(true) }
                    val onMain = Executor { main.post(it) }
                    if (address.isNullOrEmpty()) {
                        proxies.clearProxyOverride(onMain, done)
                    } else {
                        proxies.setProxyOverride(
                            ProxyConfig.Builder().addProxyRule(address).build(),
                            onMain,
                            done,
                        )
                    }
                }
                // Saves a file (a backup) where the user picks, with the
                // system's own dialog: no storage permission. Answers the
                // file's name, or null when the user backs out.
                "saveFile" -> {
                    val bytes = call.argument<ByteArray>("bytes")
                    if (bytes == null) {
                        result.error("no_data", "nothing to save", null)
                        return@setMethodCallHandler
                    }
                    pendingSave?.second?.success(null)
                    pendingSave = bytes to result
                    val create = Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(call.argument<String>("mime") ?: "application/octet-stream")
                        .putExtra(Intent.EXTRA_TITLE, call.argument<String>("name") ?: "backup")
                    try {
                        startActivityForResult(create, SAVE_REQUEST)
                    } catch (e: ActivityNotFoundException) {
                        pendingSave = null
                        result.error("no_picker", "this device has no file picker", null)
                    }
                }
                // Opens a file (a backup) the user picks, with the system's
                // own dialog, and answers its bytes, or null when the user
                // backs out. It reads to the end: the size a provider
                // reports can be wrong (0 for a file written through it).
                "openFile" -> {
                    pendingOpen?.success(null)
                    pendingOpen = result
                    val open = Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, call.argument<List<String>>("mimes")?.toTypedArray())
                    try {
                        startActivityForResult(open, OPEN_REQUEST)
                    } catch (e: ActivityNotFoundException) {
                        pendingOpen = null
                        result.error("no_picker", "this device has no file picker", null)
                    }
                }
                // The phone's main processor type (arm64-v8a, armeabi-v7a, ...).
                // The exchange is a network call, and this handler runs on the
                // UI thread: it goes to the worker and comes back with the answer.
                // A failure is one sentence in the in-app log, naming its cause.
                "irRefresh" -> inBackground(result) { Sync.run(this) }
                "irRowsAll" -> inBackground(result) { IrRows.all() }
                "irSyncWhy" -> result.success(Sync.lastReason)
                "irGate" -> result.success(IrRows.gate())
                "irLive" -> result.success(CoreService.liveMap())
                "irTotalReset" -> {
                    CoreService.resetTotal(this)
                    result.success(null)
                }
                "irBoot" -> {
                    CoreService.log("build " + (call.argument<String>("stamp") ?: "?"))
                    result.success(null)
                }
                "irStatsResetAll" -> {
                    CoreService.resetAllStats(this)
                    result.success(null)
                }
                "irPickPing" -> result.success(CoreService.lastDelay(this, CoreService.picked(this)))
                "irPingsAll" -> result.success(Flags.allDelays(this))
                "irPickInfo" -> inAsk(result) {
                    Flags.info(this, CoreService.picked(this))
                }
                "irMine" -> inAsk(result) {
                    Flags.mine(this, call.argument<Boolean>("ask") == true)
                }
                "irFlagOf" -> inAsk(result) {
                    val id = CoreService.picked(this)
                    Flags.of(this, id).ifEmpty { Flags.country(this, id) }
                }
                "irFlagsAll" -> result.success(Flags.all(this))
                "irPing" -> inProbe(result) {
                    val ids = call.argument<List<String>>("ids") ?: emptyList()
                    val out = HashMap<String, Int>()
                    // one short-lived core each: a handful per call, and the
                    // calls themselves run side by side on the probe threads
                    for (id in ids.take(6)) out[id] = Flags.delay(this, id)
                    out
                }
                "irStatsAll" -> result.success(CoreService.statsAll(this))
                "irStatReset" -> {
                    val id = call.argument<String>("id") ?: ""
                    if (id.isNotBlank()) {
                        getSharedPreferences("blob_core", MODE_PRIVATE).edit()
                            .remove("stats_" + id.take(64)).apply()
                    }
                    result.success(null)
                }
                "irFlag" -> inProbe(result) {
                    Flags.country(this, call.argument<String>("id") ?: "")
                }
                // "where is this server": the address lookup, which needs no core and so
                // runs three rows at a time on the probe threads
                "irLocate" -> inProbe(result) {
                    Flags.locate(this, call.argument<String>("id") ?: "")
                }
                "irStats" -> inBackground(result) {
                    CoreService.statsFor(this, call.argument<List<String>>("ids") ?: emptyList())
                }
                "irRows" -> result.success(IrRows.count())
                "irRow" -> {
                    val i = call.argument<Int>("i") ?: -1
                    result.success(mapOf("id" to IrRows.id(i), "name" to IrRows.name(i), "cat" to IrRows.category(i), "msg" to IrRows.note(i)))
                }
                "abi" -> result.success(Build.SUPPORTED_ABIS.firstOrNull() ?: "")
                "isTv" -> result.success(isTv(resources.configuration))
                "routingCountries" -> inBackground(result) { routingCountries() }
                "openUrl" -> {
                    val url = call.argument<String>("url") ?: ""
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    try {
                        // A TV without a browser does not throw: its stub
                        // "browser" only shows Android's "no app" message.
                        val handlers = packageManager.queryIntentActivities(view, 0)
                            .filter { it.activityInfo.packageName != "com.android.tv.frameworkpackagestubs" }
                        if (handlers.isEmpty()) {
                            result.success(false)
                            return@setMethodCallHandler
                        }
                        startActivity(view)
                        result.success(true)
                    } catch (e: ActivityNotFoundException) {
                        result.success(false)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor()

    /// A probe is a short-lived core and takes seconds. It gets its own small
    /// pool so that a list of flags never queues the app's own answers (the
    /// installed apps, the row store) behind it.
    private val probeWorker = Executors.newFixedThreadPool(3)

    /// What the user pressed - the home card's refresh, its delay pill - must
    /// answer now, not after a hundred rows learned their country. One thread,
    /// because two of these at once is already more than a tap asked for.
    private val askWorker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Country codes for first-run routing; no phone or location permissions. */
    private fun routingCountries(): Map<String, String> {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return emptyMap()
        }
        val service = getSystemService(TelephonyManager::class.java) ?: return emptyMap()
        // On dual-SIM devices use the subscription selected for mobile data.
        val phone = runCatching {
            val subscription = SubscriptionManager.getDefaultDataSubscriptionId()
            if (subscription >= 0) {
                service.createForSubscriptionId(subscription)
            } else {
                service
            }
        }.getOrDefault(service)
        return mapOf(
            "network_country" to runCatching { phone.networkCountryIso ?: "" }.getOrDefault(""),
            "sim_country" to runCatching { phone.simCountryIso ?: "" }.getOrDefault("")
        )
    }

    /** Runs [work] off the UI thread and answers with its value. */
    private fun inBackground(result: Result, work: () -> Any?) {
        worker.execute {
            val value = try {
                work()
            } catch (e: Exception) {
                null
            }
            main.post { result.success(value) }
        }
    }

    /** Runs a probe on the probe pool, where several rows can answer at once. */
    private fun inProbe(result: Result, work: () -> Any?) {
        probeWorker.execute {
            val value = try {
                work()
            } catch (e: Exception) {
                null
            }
            main.post { result.success(value) }
        }
    }

    /** Runs what the user pressed, on a thread the bulk pass never occupies. */
    private fun inAsk(result: Result, work: () -> Any?) {
        askWorker.execute {
            val value = try {
                work()
            } catch (e: Exception) {
                null
            }
            main.post { result.success(value) }
        }
    }

    /**
     * The installed apps but this one: package, name, and whether it has a
     * launcher icon, on a phone's home screen or a TV's (the others are
     * mostly the system's). Packages without code (overlays, resources) use
     * no network and are left out.
     */
    private fun installedApps(): List<Map<String, Any>> {
        val pm = packageManager
        val withIcon = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
            .map { it.activityInfo.packageName }
            .toSet()
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_HAS_CODE) != 0 }
            .map {
                mapOf(
                    "package" to it.packageName,
                    "label" to it.loadLabel(pm).toString(),
                    "launcher" to (it.packageName in withIcon),
                )
            }
    }

    /** An app's icon as a small PNG. */
    private fun appIcon(pkg: String): ByteArray {
        val drawable = packageManager.getApplicationIcon(pkg)
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun turnOn(result: Result) {
        try {
            CoreService.turnOn(this)
            result.success(null)
        } catch (e: Exception) {
            result.error("start_failed", e.message, null)
        }
    }

    @Deprecated("The VPN permission is asked with startActivityForResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SAVE_REQUEST) {
            saved(resultCode, data?.data)
            return
        }
        if (requestCode == OPEN_REQUEST) {
            opened(resultCode, data?.data)
            return
        }
        if (requestCode != VPN_REQUEST) return
        val result = pendingOn ?: return
        pendingOn = null
        if (resultCode == RESULT_OK) turnOn(result) else result.error("vpn_denied", "the VPN was not allowed", null)
    }

    /** Writes the pending file to [uri], the place the user picked. */
    private fun saved(resultCode: Int, uri: Uri?) {
        val (bytes, result) = pendingSave ?: return
        pendingSave = null
        if (resultCode != RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        worker.execute {
            val name = try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IllegalStateException("the file could not be opened")
                displayName(uri)
            } catch (e: Exception) {
                main.post { result.error("write_failed", e.message, null) }
                return@execute
            }
            main.post { result.success(name) }
        }
    }

    /** Reads the picked file at [uri], to the end. */
    private fun opened(resultCode: Int, uri: Uri?) {
        val result = pendingOpen ?: return
        pendingOpen = null
        if (resultCode != RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        worker.execute {
            val bytes = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        // A backup is a few kilobytes; nothing sensible is this big.
                        if (out.size() > MAX_OPEN_BYTES) throw IllegalStateException("the file is too large")
                    }
                    out.toByteArray()
                } ?: throw IllegalStateException("the file could not be opened")
            } catch (e: Exception) {
                main.post { result.error("read_failed", e.message, null) }
                return@execute
            }
            main.post { result.success(bytes) }
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment ?: ""

    override fun onDestroy() {
        // Off, nothing needs the core once the UI is gone.
        if (isFinishing && !CoreService.on) CoreService.stopCore()
        super.onDestroy()
    }

    companion object {
        private const val VPN_REQUEST = 7
        private const val SAVE_REQUEST = 8
        private const val OPEN_REQUEST = 9
        private const val MAX_OPEN_BYTES = 16 * 1024 * 1024
    }

    /** The service's notification needs this on Android 13 and later. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
