package com.astra.ai

import android.content.Context

data class AstraAIConfiguration(
    val mode:String="auto",
    val automaticPrimary:String="online",
    val automaticFallback:String="offline",
    val onlineProvider:String="openai-compatible",
    val onlineModel:String="",
    val offlineProvider:String="ollama",
    val offlineModel:String="",
    val cloudProcessingAllowed:Boolean=true,
    val backgroundEnabled:Boolean=false,
    val lockScreenEnabled:Boolean=true,
    val lockScreenPrivacyMode:String="require_unlock",
    val voiceEnabled:Boolean=true
)

class AstraAIConfigurationStore(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("astra_ai_configuration",Context.MODE_PRIVATE)
    fun read():AstraAIConfiguration=AstraAIConfiguration(
        mode=prefs.getString("mode",prefs.getString("ai_mode","auto")) ?: "auto",
        automaticPrimary=prefs.getString("automatic_primary","online") ?: "online",
        automaticFallback=prefs.getString("automatic_fallback","offline") ?: "offline",
        onlineProvider=prefs.getString("online_provider","openai-compatible") ?: "openai-compatible",
        onlineModel=prefs.getString("online_model","") ?: "",
        offlineProvider=prefs.getString("offline_provider","ollama") ?: "ollama",
        offlineModel=prefs.getString("offline_model","") ?: "",
        cloudProcessingAllowed=prefs.getBoolean("cloud_processing_allowed",true),
        backgroundEnabled=prefs.getBoolean("background_enabled",false),
        lockScreenEnabled=prefs.getBoolean("lock_screen_enabled",true),
        lockScreenPrivacyMode=prefs.getString("lock_screen_privacy","require_unlock") ?: "require_unlock",
        voiceEnabled=prefs.getBoolean("voice_enabled",true)
    )
    fun update(block:(android.content.SharedPreferences.Editor)->Unit){prefs.edit().apply{block(this);apply()}}
    fun setMode(mode:String){update{it.putString("mode",mode).putString("ai_mode",mode)}}
}
