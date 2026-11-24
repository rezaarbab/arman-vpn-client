package com.arman.firefoxvpn

import android.content.Context
import android.os.Build
import ir.v2rayng.compose.core.NativeKit
import ir.v2rayng.compose.core.NativeSec
import ir.v2rayng.compose.core.sec.BlobIdentity
import ir.v2rayng.compose.core.sec.BlobSecure
import org.json.JSONObject

/**
 * The sync the app's own client sends: prove this install to the worker (its keystore key),
 * take the session keys, ask for what changed, and let the library keep the rows. Every step
 * writes one line on the log page; a refusal says the status and the library's own reason.
 */
// retry: a configuration-only Gradle failure is not this file's business
// Gradle's configure step has failed alone twice; the code below is unchanged.
object Sync {

    /** The panel's four server-behaviour commands, in the order they are kept. */
    private val ADMIN_ORDER = listOf("ping_start", "flag_start", "auto_ping_new", "auto_flag_new")

    /** Why the last sync failed, as both the page and the log show it; "" for none. */
    @Volatile
    var lastReason: String = ""

    fun run(context: Context): Int {
        lastReason = ""
        val t0 = System.currentTimeMillis()
        CoreService.log("sync: start")
        if (!NativeSec.ready()) {
            lastReason = IrRows.gate()
            CoreService.log(lastReason)
            return -1
        }
        if (!NativeKit.available) {
            lastReason = "the gate has no server identity"
            CoreService.log("sync: " + lastReason)
            return -1
        }
        // the list the library kept from an earlier run: a cold start reads it before asking
        if (NativeSec.payloadRows() == 0) {
            val restored = NativeSec.rowsRestore()
            CoreService.log("rows restore: $restored")
        }
        val body = try {
            // the same fields the app's own client sends: the worker reads type for its
            // event log and acks for the receipts it consumes
            JSONObject()
                .put("device", deviceId(context))
                .put("model", Build.MANUFACTURER.ifBlank { "?" } + " " + Build.MODEL)
                .put("ver", versionCode(context))
                .put("type", "manual")
                .put("connected", CoreService.connected)
                .put("info", info(context))
                .put("acks", Admin.takeAcks(context))
        } catch (e: Exception) {
            lastReason = "the request could not be built (${e.javaClass.simpleName})"
            CoreService.log("sync: " + lastReason)
            return -1
        }
        val panel = BlobSecure.syncSecure(body)
        if (panel == null) {
            lastReason = BlobSecure.lastReason ?: "sync failed"
            // the step says whether an HTTP answer really came back: a 5xx over TLS is the edge
            // or the worker refusing to finish, not this process failing to speak
            CoreService.log("sync: " + lastReason + " [step=" + NativeSec.errInfo() + "]")
            return -1
        }
        takeAdmin(context, panel)
        // the same copy also carries the panel's messages and its update order, and a message is
        // not a server: a sync that brought no rows still brings what the admin wants said
        Admin.take(context, panel)
        val rows = NativeSec.payloadRows()
        // A sync hands over the whole list again, including what the user deleted: the ids kept
        // under drop_ids go back out before the list is shown, or a deleted server returns on the
        // next pull and the delete means nothing.
        if (rows > 0) IrRows.reapplyDrops(context)
        // the seconds the person waited, in the one line the log keeps about a sync: without it
        // "it took long" has no number to argue with
        CoreService.log("sync: ${NativeSec.payloadRows()} servers in ${(System.currentTimeMillis() - t0) / 1000L}s")
        return if (rows > 0) 1 else 0
    }

    /**
     * The two panel commands that are about the app rather than a server: whether the operator
     * lets this app run at all, and how the list should behave on its own.
     *
     * Both fail open. A worker that says nothing about them - an older deploy, or a payload
     * trimmed to the point of absence - must leave the app working, because the cost of reading
     * a missing field as "off" is every handset in the country losing the tunnel at once.
     */
    private fun takeAdmin(context: Context, panel: JSONObject) {
        val p = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        // "enabled" is absent only from a worker that predates the command; a present false is the
        // operator's own switch, and a blocked handset is handed the same false
        val off = if (panel.has("enabled")) !panel.optBoolean("enabled", true) else false
        val was = p.getInt("adm_off", 0) == 1
        if (off != was) CoreService.log(if (off) "admin: this app is switched off" else "admin: the app is back on")
        val pf = panel.optJSONObject("pflags")
        val flags = ADMIN_ORDER.joinToString(",") { k ->
            when (pf?.optString(k, "auto")) {
                "1" -> "1"
                "0" -> "0"
                // "auto" is the panel's own word for "the user's switch decides", and so is a
                // field this worker never sent: neither may quietly turn a feature off
                else -> "a"
            }
        }
        p.edit().putInt("adm_off", if (off) 1 else 0).putString("adm_pf", flags).apply()
    }

    /** Whether the operator has stopped this app from running its tunnel. */
    fun adminOff(context: Context): Boolean =
        context.getSharedPreferences("blob_core", Context.MODE_PRIVATE).getInt("adm_off", 0) == 1

    /** The four behaviour commands, "1" forced on, "0" forced off, "a" the user's own switch,
     *  in the order: ping on start, flags on start, ping new servers, flag new servers. */
    fun adminFlags(context: Context): String =
        context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
            .getString("adm_pf", "a,a,a,a") ?: "a,a,a,a"

    /**
     * ANDROID_ID is per handset and signing key and survives a reinstall, so one phone stays one
     * row in the panel; a device without one gets a random id kept for this install.
     */
    fun deviceId(context: Context): String {
        val hardware = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        } catch (_: Exception) {
            null
        }
        if (!hardware.isNullOrBlank() && hardware != "9774d56d682e549c") return hardware.lowercase()
        val prefs = context.getSharedPreferences("blob_core", Context.MODE_PRIVATE)
        var v = prefs.getString("device_id", "") ?: ""
        if (v.isBlank()) {
            v = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", v).apply()
        }
        return v
    }

    private fun versionCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    } catch (_: Exception) {
        0
    }

    /** What the admin's panel shows about the phone; no address, no key material. */
    private fun info(context: Context): JSONObject {
        val o = JSONObject()
        fun add(k: String, v: Any?) {
            val s = v?.toString() ?: return
            if (s.isBlank()) return
            try {
                o.put(k, s)
            } catch (_: Exception) {
            }
        }
        add("brand", Build.BRAND)
        add("model", Build.MODEL)
        add("device", Build.DEVICE)
        add("manufacturer", Build.MANUFACTURER)
        add("product", Build.PRODUCT)
        add("board", Build.BOARD)
        add("hardware", Build.HARDWARE)
        add("abi", Build.SUPPORTED_ABIS.joinToString(","))
        add("cores", Runtime.getRuntime().availableProcessors())
        add("fingerprint", Build.FINGERPRINT.take(120))
        add("sec_patch", Build.VERSION.SECURITY_PATCH)
        add("sdk", Build.VERSION.SDK_INT)
        add("os", Build.VERSION.RELEASE)
        add("android_id", try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )
        } catch (_: Exception) {
            null
        })
        add("key_id", try { BlobIdentity.fingerprint() } catch (_: Throwable) { null })
        val pi = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        add("app_ver", pi?.versionName ?: "?")
        add("installed", pi?.lastUpdateTime ?: 0L)
        add("inst_first", pi?.firstInstallTime ?: 0L)
        add("target_sdk", context.applicationInfo.targetSdkVersion)
        return o
    }
}
