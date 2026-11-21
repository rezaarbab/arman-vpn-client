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

    private fun field(i: Int, which: Int): String =
        runCatching { NativeSec.payloadText(i, which) }.getOrDefault("")
}
