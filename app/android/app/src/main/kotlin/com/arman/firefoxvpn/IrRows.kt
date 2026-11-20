package com.arman.firefoxvpn

import ir.v2rayng.compose.core.NativeSec

/** The row store our C keeps, as far as this app may look at it.
 *  Field indices are the ones the C switch actually answers (0 id, 1 name, 3 category, 4 msg);
 *  anything not in that list is deliberately not exposed here rather than guessed. */
object IrRows {
    fun count(): Int = runCatching { NativeSec.payloadRows() }.getOrDefault(0)
    /** One refresh through the gate; the exchange itself stays in C. */
    fun refresh(): Int = runCatching { NativeSec.syncPanel(); NativeSec.syncResult() }.getOrDefault(-1)

    fun rowOf(id: String): Int = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)

    fun id(i: Int): String = field(i, 0)
    fun name(i: Int): String = field(i, 1)
    fun category(i: Int): String = field(i, 3)
    fun note(i: Int): String = field(i, 4)

    private fun field(i: Int, which: Int): String =
        runCatching { NativeSec.payloadText(i, which) }.getOrDefault("")
}
