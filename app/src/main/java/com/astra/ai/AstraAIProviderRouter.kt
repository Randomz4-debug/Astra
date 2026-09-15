package com.astra.ai

import android.content.Context

/** Single provider-selection policy shared by every Astra client. */
class AstraAIProviderRouter(context:Context) {
    enum class Provider { ONLINE, OFFLINE }
    data class Decision(val provider:Provider,val mode:String,val reason:String)
    private val app=context.applicationContext
    private val config=AstraAIConfigurationStore(app)
    private val connectivity=AstraConnectivityManager(app)

    fun decide():Decision {
        val c=config.read()
        if(c.mode=="offline") return Decision(Provider.OFFLINE,"offline","Offline mode was explicitly selected.")
        if(c.mode=="online") return Decision(Provider.ONLINE,"online","Online mode was explicitly selected.")
        val online=connectivity.hasInternet() && c.cloudProcessingAllowed
        return if(c.automaticPrimary=="online" && online) Decision(Provider.ONLINE,"auto","Automatic selected Online because network/cloud processing is available.")
        else if(c.automaticFallback=="online" && online) Decision(Provider.ONLINE,"auto","Automatic fallback selected Online.")
        else Decision(Provider.OFFLINE,"auto","Automatic selected Offline because Online is unavailable or disallowed.")
    }
}
