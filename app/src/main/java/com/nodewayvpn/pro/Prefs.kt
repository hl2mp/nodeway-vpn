package com.nodewayvpn.pro

import android.content.Context

/**
 * Tiny persistent store for the user's VLESS link.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastLink: String
        get() = sp.getString(KEY_LINK, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LINK, value.trim()).apply()

    private companion object {
        const val NAME = "nodeway_vpn"
        const val KEY_LINK = "last_link"
    }
}