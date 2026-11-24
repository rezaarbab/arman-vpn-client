package com.arman.firefoxvpn

import ir.v2rayng.compose.core.NativeSec

/** The row store our C keeps, as far as this app may look at it.
 *  Field indices are the ones the C switch actually answers (0 id, 1 name, 3 category, 4 msg);
 *  anything not in that list is deliberately not exposed here rather than guessed. */
object IrRows {
    fun count(): Int = runCatching { NativeSec.payloadRows() }.getOrDefault(0)

    fun rowOf(id: String): Int = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)

    fun id(i: Int): String = field(i, 0)
    fun name(i: Int): String = field(i, 1)
    fun category(i: Int): String = field(i, 3)
    fun note(i: Int): String = field(i, 4)

    /** Every row in one answer: the store can hold thousands, and one call per
     *  field per row is a few thousand round trips through the channel. */
    fun all(): List<Map<String, String>> {
        val n = count()
        val out = ArrayList<Map<String, String>>(n)
        for (i in 0 until n) {
            out.add(
                mapOf(
                    "id" to id(i),
                    "name" to name(i),
                    "cat" to category(i),
                    "msg" to note(i),
                ),
            )
        }
        return out
    }

    /** The gate's own state: "sec ok", or why it did not come up. */
    fun gate(): String =
        if (NativeSec.ready()) {
            "sec ok"
        } else {
            "the gate did not load: " + NativeSec.loadWhy().ifBlank { "no reason" }
        }

    /** The ids the user deleted, kept so a later sync does not hand the dead server back. */
    fun dropped(context: android.content.Context): String =
        context.getSharedPreferences("blob_core", android.content.Context.MODE_PRIVATE)
            .getString("drop_ids", "") ?: ""

    /** How many removals the phone is holding. */
    fun dropCount(context: android.content.Context): Int =
        dropped(context).split(';').count { it.isNotBlank() }

    /**
     * Forget every removal, so the next sync hands the whole list back. Someone who marked a
     * batch dead and removed it has to be able to undo that without knowing where the marks are
     * kept - and a wrong verdict about "dead" must never be why a server is gone for good.
     */
    fun clearDrops(context: android.content.Context): Int {
        val n = dropCount(context)
        prefsFile(context).edit().remove("drop_ids").apply()
        CoreService.log("list: $n removals forgotten")
        return n
    }

    /**
     * Take these rows out of the store, out of the sealed copy the next start reads, and out of
     * every cache that measured them. One call for the whole batch: the store is sealed again
     * each time, so a hundred deletes must not mean a hundred seals.
     *
     * The ids are remembered because the next sync hands over the whole list again - without
     * them a deleted server would be back on the screen by the next pull.
     */
    fun drop(context: android.content.Context, ids: List<String>): Int {
        val clean = ids.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return -1
        // A delete that would leave nothing is refused. The rows are gone from the phone for
        // good, and the server only hands them over again when its own list changes, so a batch
        // judged dead in error - or a list whose ids the panel hands out again - empties the app
        // and no amount of refreshing brings anything back.
        val n = count()
        val left = n - held(clean)
        if (n > 0 && left <= 0) {
            CoreService.log("list: the removal was refused - every server would be gone")
            return -2
        }
        val p = context.getSharedPreferences("blob_core", android.content.Context.MODE_PRIVATE)
        val keptList = (dropped(context).split(';').filter { it.isNotBlank() } + clean).distinct()
        val edit = p.edit().putString("drop_ids", keptList.joinToString(";"))
        for (id in clean) {
            val t = id.take(64)
            edit.remove("cc_$t").remove("pd_$t").remove("pi_$t").remove("stats_$t")
        }
        // a pinned row that no longer exists must not keep its mark
        val stillPinned = pins(context).filter { !clean.contains(it) }
        edit.putString("pins", stillPinned.joinToString(";"))
        edit.apply()
        val kept = NativeSec.drop(clean.joinToString(";"))
        CoreService.log(
            if (kept < 0) "list: the delete was refused"
            else "list: ${clean.size} servers removed, ${kept} left"
        )
        return kept
    }

    fun drop(context: android.content.Context, id: String): Int = drop(context, listOf(id))

    /** How many of these ids the store holds right now - the ids nobody removed are not counted
     *  as a loss, or a stale list of removals would look like the whole store. */
    private fun held(ids: List<String>): Int {
        val want = ids.toHashSet()
        var got = 0
        for (i in 0 until count()) if (id(i) in want) got++
        return got
    }

    /** The rows the user pinned, in the order he pinned them. */
    fun pins(context: android.content.Context): List<String> =
        (prefsFile(context).getString("pins", "") ?: "")
            .split(';').filter { it.isNotBlank() }

    /** Marks one row, and hands back the whole list so the page never has to guess. */
    fun pin(context: android.content.Context, id: String, on: Boolean): List<String> {
        if (id.isBlank()) return pins(context)
        val now = pins(context).toMutableList()
        if (on) {
            if (!now.contains(id)) now.add(id)
        } else {
            now.remove(id)
        }
        prefsFile(context).edit().putString("pins", now.joinToString(";")).apply()
        return now
    }

    private fun prefsFile(context: android.content.Context) =
        context.getSharedPreferences("blob_core", android.content.Context.MODE_PRIVATE)

    /** After a sync replaced the store: everything the user deleted goes out again, in one call. */
    fun reapplyDrops(context: android.content.Context) {
        val ids = dropped(context).split(';').filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        val n = count()
        if (n == 0) return
        // The same rule as the delete: remembered removals are applied to a fresh list only as
        // long as something is left. A list the panel handed out under the same ids as the one
        // once marked dead would otherwise be emptied by memory alone, on every sync, forever.
        if (held(ids) >= n) {
            CoreService.log("list: the remembered removals would empty the list - not applied")
            return
        }
        if (NativeSec.drop(ids.joinToString(";")) < 0) CoreService.log("list: the delete was refused")
    }

    private fun field(i: Int, which: Int): String =
        runCatching { NativeSec.payloadText(i, which) }.getOrDefault("")
}
