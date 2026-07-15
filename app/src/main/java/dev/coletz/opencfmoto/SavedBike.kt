package dev.coletz.opencfmoto

import android.content.Context

/**
 * Remembers the bike from the last successful QR scan so the rider doesn't have to re-scan
 * before every ride. We persist the QR's fields verbatim — that's everything the connect flow
 * needs (ssid/pwd to join, modelId to pick the BikeProfile).
 *
 * Android separately remembers the WifiNetworkSpecifier approval per (app, access point), so a
 * repeat join of an already-approved bike associates without showing the network picker again.
 */
object SavedBike {
    private const val PREFS = "saved_bike"
    private const val KEY_SSID = "ssid"
    private const val KEY_PWD = "pwd"
    private const val KEY_AUTH = "auth"
    private const val KEY_MAC = "mac"
    private const val KEY_NAME = "name"
    private const val KEY_ACTION = "action"
    private const val KEY_MODEL_ID = "modelId"
    private const val KEY_SN = "sn"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_ALIAS = "alias"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, qr: QrData) {
        val p = prefs(context)
        val editor = p.edit()
        // Scanning a *different* bike invalidates the rider's custom name; re-scanning the
        // same bike (e.g. after a stale-Wi-Fi forget-and-rescan) keeps it.
        if (p.getString(KEY_SSID, null) != qr.ssid) editor.remove(KEY_ALIAS)
        editor
            .putString(KEY_SSID, qr.ssid)
            .putString(KEY_PWD, qr.pwd)
            .putString(KEY_AUTH, qr.auth)
            .putString(KEY_MAC, qr.mac)
            .putString(KEY_NAME, qr.name)
            .putInt(KEY_ACTION, qr.action)
            .putString(KEY_MODEL_ID, qr.modelId)
            .putString(KEY_SN, qr.sn)
            .putString(KEY_CHANNEL, qr.channel)
            .apply()
    }

    fun load(context: Context): QrData? {
        val p = prefs(context)
        val ssid = p.getString(KEY_SSID, null) ?: return null
        val pwd = p.getString(KEY_PWD, null) ?: return null
        return QrData(
            ssid = ssid,
            pwd = pwd,
            auth = p.getString(KEY_AUTH, null),
            mac = p.getString(KEY_MAC, null),
            name = p.getString(KEY_NAME, null),
            action = p.getInt(KEY_ACTION, 0),
            modelId = p.getString(KEY_MODEL_ID, null),
            sn = p.getString(KEY_SN, null),
            channel = p.getString(KEY_CHANNEL, null),
        )
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /** Rider-chosen name, if any. */
    fun alias(context: Context): String? =
        prefs(context).getString(KEY_ALIAS, null)?.takeIf { it.isNotBlank() }

    /** Sets the rider-chosen name; null or blank restores the QR-derived label. */
    fun rename(context: Context, name: String?) {
        val trimmed = name?.trim()
        prefs(context).edit().apply {
            if (trimmed.isNullOrEmpty()) remove(KEY_ALIAS) else putString(KEY_ALIAS, trimmed)
        }.apply()
    }

    /** Human-readable label for the UI, e.g. "CFMOTO-244E67". */
    fun label(qr: QrData): String = qr.name ?: qr.ssid

    /** Label preferring the rider's custom name, e.g. "THUNDER" over "CFMOTO-244E67". */
    fun displayLabel(context: Context, qr: QrData): String = alias(context) ?: label(qr)
}
