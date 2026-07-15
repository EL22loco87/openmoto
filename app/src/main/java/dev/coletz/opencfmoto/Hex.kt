package dev.coletz.opencfmoto

/** Hex formatting for log/debug dumps of raw protocol bytes. */
object Hex {
    fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
