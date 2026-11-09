package com.cloudmusic.core.data

import android.content.Context
import android.util.Log

/**
 * Login session persistence (login cookie), backed by SharedPreferences.
 *
 * This is deliberately NOT DataStore: the payload is a single opaque cookie
 * string written once per login, so DataStore would add a dependency and an
 * async API for no benefit here.
 */
class SessionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasCookie(): Boolean = !prefs.getString(KEY_COOKIE, null).isNullOrBlank()

    /** Persists the raw cookie header captured from [android.webkit.CookieManager]. */
    fun saveCookie(cookie: String) {
        prefs.edit()
            .putString(KEY_COOKIE, cookie)
            .putString(KEY_ACCOUNT_HINT, maskCookie(cookie))
            .apply()
    }

    fun readCookie(): String? = prefs.getString(KEY_COOKIE, null)

    /** Masked account hint for the settings page, e.g. `MUSIC_U ****a1b2`. */
    fun accountHint(): String? = prefs.getString(KEY_ACCOUNT_HINT, null)

    fun clear() {
        prefs.edit()
            .remove(KEY_COOKIE)
            .remove(KEY_ACCOUNT_HINT)
            .apply()
    }

    private companion object {
        const val TAG = "SessionStore"
        const val PREFS_NAME = "cloudmusic_session"
        const val KEY_COOKIE = "login_cookie"
        const val KEY_ACCOUNT_HINT = "account_hint"

        fun maskCookie(cookie: String): String {
            val entry = cookie.split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith("${MUSIC_U}=") }
            val value = entry?.substringAfter("${MUSIC_U}=")
            if (value.isNullOrBlank()) {
                Log.w(TAG, "cookie saved without $MUSIC_U entry, cannot build account hint")
                return "已登录"
            }
            return "$MUSIC_U ****${value.takeLast(4)}"
        }

        const val MUSIC_U = "MUSIC_U"
    }
}
