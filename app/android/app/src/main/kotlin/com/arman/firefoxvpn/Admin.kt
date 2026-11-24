package com.arman.firefoxvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * The panel's "messages and update" section, as the app keeps it.
 *
 * The sync reply already carries all of it - the popup with its per-handset counter, the
 * forced/optional update, the notifications the admin sent - and the app used to throw the copy
 * away after taking the servers. Nothing here is a secret: this is text the admin wrote for this
 * handset, and it never goes near the log, because a log line is read by whoever plugs the phone
 * in, not only by the person holding it.
 *
 * The receipts the worker counts (acknowledgements) are kept in prefs until a sync can carry
 * them: the person may dismiss a message with no network, and "how many devices saw it" is
 * wrong forever if that dismissal is dropped on the floor.
 */
object Admin {
    private const val FILE = "blob_admin"
    private const val MSG_CHANNEL = "blob_msg"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Take whatever the panel said about the app itself. Called for every sync that opened,
     * including a sync that carried no servers at all: a message is not a server.
     */
    fun take(context: Context, panel: JSONObject) {
        val p = prefs(context)
        val e = p.edit()
        val popup = panel.optJSONObject("popup")
        if (popup != null) {
            e.putString("popup", popup.toString())
        } else {
            // the worker stops sending it once this handset is done with it
            e.remove("popup")
        }
        val upd = panel.optJSONObject("update")
        if (upd != null) {
            e.putString("update", upd.toString())
        } else {
            e.remove("update")
        }
        // The dismissal belongs to one message: a new popup from the panel must not inherit the
        // silence of the last one.
        if (popup != null && p.getString("popup_dismissed", "") != popup.optString("id", "")) {
            e.remove("popup_dismissed")
        }
        e.apply()
        val nots = panel.optJSONArray("notifications")
        if (nots != null) showNotifications(context, nots)
    }

    /** The popup the panel wants this handset to see, or null when there is nothing pending. */
    fun popup(context: Context): Map<String, Any?> {
        val p = prefs(context)
        val raw = p.getString("popup", "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val o = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        // the worker counts the shows itself; the app only obeys its own "no again for this id"
        if (p.getString("popup_dismissed", "") == o.optString("id", "")) return emptyMap()
        return mapOf(
            "id" to o.optString("id", ""),
            "title" to o.optString("title", ""),
            "body" to o.optString("body", ""),
            "btnText" to o.optString("btnText", ""),
            "btnUrl" to o.optString("btnUrl", ""),
            "btnEnabled" to o.optBoolean("btnEnabled", true),
            "cancelEnabled" to o.optBoolean("cancelEnabled", true),
            "left" to o.optInt("left", 1),
        )
    }

    /** The update the panel asked for; null when this copy is fine as it is. */
    fun update(context: Context): Map<String, Any?> {
        val raw = prefs(context).getString("update", "") ?: ""
        if (raw.isEmpty()) return emptyMap()
        val o = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        return mapOf(
            "forced" to o.optBoolean("forced", false),
            "text" to o.optString("text", ""),
            "btnText" to o.optString("btnText", "Update"),
            "url" to o.optString("url", ""),
        )
    }

    /**
     * Whether the tunnel is refused until the app is updated. Only when the admin both forced it
     * and left a way out: a forced update with no download link is a door welded shut, and the
     * person holding the phone has no way to know the panel did that.
     */
    fun updateBlocks(context: Context): Boolean {
        val u = update(context)
        return u["forced"] == true && (u["url"] as? String)?.isNotBlank() == true
    }

    /** The person answered the message: remember it for this id and promise the receipt. */
    fun dismiss(context: Context, kind: String, id: String) {
        if (id.isBlank()) return
        val e = prefs(context).edit()
        if (kind == "popup") e.putString("popup_dismissed", id)
        e.apply()
        queueAck(context, kind, id)
    }

    /**
     * A device notification from the panel's list. Each id is posted once and once only, so a
     * person who syncs four times a day is not told the same thing four times, and the receipt
     * goes back so the worker stops sending it.
     */
    private fun showNotifications(context: Context, nots: JSONArray) {
        val p = prefs(context)
        val seen = (p.getString("notif_seen", "") ?: "").split(';').filter { it.isNotBlank() }.toMutableList()
        val edit = p.edit()
        var posted = 0
        for (i in 0 until nots.length()) {
            val o = nots.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isEmpty() || seen.contains(id)) continue
            val title = o.optString("title", "")
            val body = o.optString("body", "")
            if (title.isBlank() && body.isBlank()) continue
            postNotification(context, title.ifBlank { "Message" }, body)
            seen.add(id)
            queueAck(context, "notif", id)
            posted++
        }
        if (posted > 0) {
            edit.putString("notif_seen", seen.joinToString(";"))
            edit.apply()
            // the count, never the text: the log is read by other people than the owner
            CoreService.log("messages: $posted notices from the panel")
        }
    }

    private fun postNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            // A silent channel: the panel's notices are news on a screen, not an alarm, and the
            // tunnel's own ongoing notification is the one that must stay audible-free of noise.
            nm.createNotificationChannel(
                NotificationChannel(MSG_CHANNEL, "Messages", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n = NotificationCompat.Builder(context, MSG_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(120))
            .setContentText(body.take(600))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(2000)))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(title.hashCode(), n) }
    }

    /** Acks wait here (a plain "kind|id" list) until a sync can carry them. */
    private fun queueAck(context: Context, kind: String, id: String) {
        val p = prefs(context)
        val list = (p.getString("acks", "") ?: "").split(';').filter { it.isNotBlank() }.toMutableList()
        val one = "$kind|$id"
        if (!list.contains(one)) list.add(one)
        // a phone that never gets network again must not grow this forever
        val trimmed = list.takeLast(48)
        p.edit().putString("acks", trimmed.joinToString(";")).apply()
    }

    /** The receipts for the next sync body, and the promise is spent once handed over. */
    fun takeAcks(context: Context): JSONArray {
        val p = prefs(context)
        val out = JSONArray()
        for (one in (p.getString("acks", "") ?: "").split(';').filter { it.isNotBlank() }) {
            val at = one.indexOf('|')
            if (at <= 0) continue
            out.put(JSONObject().put("kind", one.substring(0, at)).put("id", one.substring(at + 1)))
        }
        if (out.length() > 0) p.edit().putString("acks", "").apply()
        return out
    }
}
