package com.lipanampesa.shop

import android.content.Context

/** SharedPreferences-backed app session: auth token, role, server URL, display currency. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()

    var role: String?
        get() = prefs.getString("role", null)
        set(v) = prefs.edit().putString("role", v).apply()

    var displayName: String?
        get() = prefs.getString("display_name", null)
        set(v) = prefs.edit().putString("display_name", v).apply()

    /** Name of the shop this device belongs to (shown in the app bar). */
    var shopName: String?
        get() = prefs.getString("shop_name", null)
        set(v) = prefs.edit().putString("shop_name", v).apply()

    /** Backend base URL. Default works on the Android emulator (10.0.2.2 = host machine). */
    var baseUrl: String
        get() = prefs.getString("base_url", "http://10.0.2.2:3000/") ?: "http://10.0.2.2:3000/"
        set(v) {
            val b = if (v.endsWith("/")) v else "$v/"
            prefs.edit().putString("base_url", b).apply()
        }

    /** Display currency for the cashier UI (M-Pesa itself charges KES). */
    var currency: String
        get() = prefs.getString("currency", "KES") ?: "KES"
        set(v) = prefs.edit().putString("currency", v).apply()

    /** Last downloaded item catalog — used as an offline fallback. */
    var cachedItemsJson: String?
        get() = prefs.getString("cached_items", null)
        set(v) = prefs.edit().putString("cached_items", v).apply()

    val isLoggedIn: Boolean get() = !token.isNullOrEmpty()

    /** Clears auth but keeps server URL, currency and the offline catalog. */
    fun clearAuth() {
        prefs.edit().remove("token").remove("role").remove("display_name").remove("shop_name").apply()
    }
}
