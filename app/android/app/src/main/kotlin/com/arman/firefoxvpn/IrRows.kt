package com.arman.firefoxvpn

import ir.v2rayng.compose.core.NativeSec

/** The row store our C keeps, as far as this app may look at it: a count and a row number.
 *  Field text stays behind [NativeSec.payloadText] until the field order is pinned from the C
 *  side; guessing an index here would put a category where a link belongs. */
object IrRows {
    fun count(): Int = runCatching { NativeSec.payloadRows() }.getOrDefault(0)
    fun rowOf(id: String): Int = runCatching { NativeSec.rowOf(id) }.getOrDefault(-1)
}
