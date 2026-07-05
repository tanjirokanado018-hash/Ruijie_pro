package com.ruijiepro.utils

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var gatewayIp: String?
        get() = prefs.getString(KEY_GATEWAY_IP, null)
        set(value) = prefs.edit().putString(KEY_GATEWAY_IP, value).apply()

    var gatewayMac: String?
        get() = prefs.getString(KEY_GATEWAY_MAC, null)
        set(value) = prefs.edit().putString(KEY_GATEWAY_MAC, value).apply()

    var lastVoucher: String?
        get() = prefs.getString(KEY_LAST_VOUCHER, null)
        set(value) = prefs.edit().putString(KEY_LAST_VOUCHER, value).apply()

    fun hasSavedGateway(): Boolean = !gatewayIp.isNullOrBlank() && !gatewayMac.isNullOrBlank()

    fun clearGateway() {
        prefs.edit()
            .remove(KEY_GATEWAY_IP)
            .remove(KEY_GATEWAY_MAC)
            .apply()
    }

    companion object {
        private const val PREF_NAME = "ruijie_prefs"
        private const val KEY_GATEWAY_IP = "gateway_ip"
        private const val KEY_GATEWAY_MAC = "gateway_mac"
        private const val KEY_LAST_VOUCHER = "last_voucher"
    }
}
