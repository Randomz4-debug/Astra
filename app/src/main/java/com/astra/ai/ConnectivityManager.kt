package com.astra.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Reports real internet validation separately from local/LAN connectivity. */
class AstraConnectivityManager(context: Context) {
    private val appContext = context.applicationContext

    fun hasInternet(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun hasNetwork(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return manager.activeNetwork != null
    }
}
