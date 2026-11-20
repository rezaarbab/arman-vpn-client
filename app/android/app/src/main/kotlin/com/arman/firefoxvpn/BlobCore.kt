package com.arman.firefoxvpn

/** Loads the two libraries the port brought: the C gate and the xray core.
 *  Nothing calls it yet; a failed load stays a false flag instead of a crash at launch. */
object BlobCore {
    @Volatile var gate: Boolean = false
        private set
    @Volatile var xray: Boolean = false
        private set

    init {
        gate = runCatching { System.loadLibrary("blobgate"); true }.getOrDefault(false)
        xray = runCatching { System.loadLibrary("gojni"); true }.getOrDefault(false)
    }

    fun status(): String = "gate=$gate xray=$xray"
}
