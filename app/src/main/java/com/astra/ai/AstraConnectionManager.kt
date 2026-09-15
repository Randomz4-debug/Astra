package com.astra.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Central, capability-driven connection registry. It never treats a service as connected without a real connection record. */
class AstraConnectionManager(private val context: Context) {
    enum class State { CONNECTED, CONNECTING, DISCONNECTED, AUTHENTICATING, EXPIRED, ERROR, REVOKED }
    enum class Method { OAUTH, OAUTH_PKCE, QR, DEVICE_CODE, APP_TO_APP, INTENT, API_KEY, NOTIFICATION, ACCESSIBILITY, CONTACTS, PHONE, BLUETOOTH, LOCAL_NETWORK, REST, WEBSOCKET, BROWSER }
    enum class Capability { READ_MESSAGES, SEND_MESSAGES, READ_NOTIFICATIONS, SEND_EMAIL, READ_EMAIL, CREATE_POST, READ_POST, SEARCH, CALL, OPEN_APP, CONTROL_MEDIA, READ_CONTACTS, SEND_FILE, READ_CALENDAR, CREATE_CALENDAR_EVENT }
    data class Integration(val id:String,val name:String,val packageName:String?=null,val website:String?=null,val methods:List<Method> = emptyList(),val capabilities:List<Capability> = emptyList())
    data class Account(val id:String,val integrationId:String,val accountName:String,val providerUserId:String?,val authType:String,val scopes:List<String>,val secretRef:String?,val connectedAt:Long,val lastUsed:Long)
    data class Record(val integration:Integration,val state:State,val accounts:List<Account>)

    private val prefs = context.getSharedPreferences("astra_connections_v2", Context.MODE_PRIVATE)
    private val vault = AstraCredentialVault(context)

    private val builtIns = listOf(
        Integration("whatsapp","WhatsApp","com.whatsapp","https://www.whatsapp.com",listOf(Method.APP_TO_APP,Method.INTENT,Method.NOTIFICATION,Method.ACCESSIBILITY,Method.BROWSER),listOf(Capability.READ_MESSAGES,Capability.SEND_MESSAGES,Capability.READ_NOTIFICATIONS,Capability.OPEN_APP)),
        Integration("telegram","Telegram","org.telegram.messenger","https://telegram.org",listOf(Method.APP_TO_APP,Method.INTENT,Method.NOTIFICATION,Method.ACCESSIBILITY,Method.BROWSER,Method.API_KEY),listOf(Capability.READ_MESSAGES,Capability.SEND_MESSAGES,Capability.READ_NOTIFICATIONS,Capability.OPEN_APP)),
        Integration("gmail","Gmail","com.google.android.gm","https://mail.google.com",listOf(Method.OAUTH_PKCE,Method.BROWSER,Method.INTENT),listOf(Capability.READ_EMAIL,Capability.SEND_EMAIL,Capability.SEARCH,Capability.SEND_FILE)),
        Integration("google","Google",null,"https://accounts.google.com",listOf(Method.OAUTH_PKCE,Method.BROWSER),listOf(Capability.READ_EMAIL,Capability.READ_CALENDAR,Capability.CREATE_CALENDAR_EVENT)),
        Integration("spotify","Spotify","com.spotify.music","https://www.spotify.com",listOf(Method.OAUTH_PKCE,Method.APP_TO_APP,Method.INTENT,Method.BROWSER),listOf(Capability.CONTROL_MEDIA,Capability.SEARCH,Capability.READ_POST)),
        Integration("instagram","Instagram","com.instagram.android","https://www.instagram.com",listOf(Method.APP_TO_APP,Method.INTENT,Method.NOTIFICATION,Method.ACCESSIBILITY,Method.BROWSER),listOf(Capability.READ_NOTIFICATIONS,Capability.OPEN_APP)),
        Integration("discord","Discord","com.discord","https://discord.com",listOf(Method.OAUTH_PKCE,Method.APP_TO_APP,Method.INTENT,Method.NOTIFICATION,Method.BROWSER),listOf(Capability.READ_MESSAGES,Capability.SEND_MESSAGES,Capability.READ_NOTIFICATIONS,Capability.OPEN_APP)),
        Integration("custom_api","Custom API",null,null,listOf(Method.REST,Method.WEBSOCKET,Method.API_KEY,Method.OAUTH),emptyList()),
        Integration("custom_web","Custom Web Service",null,null,listOf(Method.OAUTH,Method.DEVICE_CODE,Method.BROWSER,Method.API_KEY),emptyList())
    )

    fun integrations(): List<Integration> = builtIns + loadCustomIntegrations()
    fun find(id:String): Integration? = integrations().firstOrNull { it.id == id }

    fun isInstalled(integration: Integration): Boolean = integration.packageName?.let {
        runCatching { context.packageManager.getApplicationInfo(it, 0); true }.getOrDefault(false)
    } ?: false

    fun openApp(integration: Integration): Boolean {
        val pkg = integration.packageName ?: return openWebsite(integration)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun openWebsite(integration: Integration): Boolean = integration.website?.let {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    } ?: false

    fun records(): List<Record> = integrations().map { integration ->
        val accounts = accounts(integration.id)
        Record(integration, state(integration.id), accounts)
    }

    fun state(integrationId:String): State = runCatching { State.valueOf(prefs.getString("state_$integrationId", State.DISCONNECTED.name)!!) }.getOrDefault(State.DISCONNECTED)
    fun setState(integrationId:String,state:State) { prefs.edit().putString("state_$integrationId",state.name).apply() }

    fun accounts(integrationId:String): List<Account> {
        val arr = runCatching { JSONArray(prefs.getString("accounts_$integrationId","[]")) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(Account(o.optString("id"),integrationId,o.optString("name"),o.optString("providerUserId").ifBlank{null},o.optString("authType"),o.optJSONArray("scopes").toStringList(),o.optString("secretRef").ifBlank{null},o.optLong("connectedAt"),o.optLong("lastUsed")))
            }
        }
    }

    fun saveConnectedAccount(integrationId:String,accountName:String,authType:String,scopes:List<String>,secrets:Map<String,String> = emptyMap(),providerUserId:String?=null): Account {
        val id=UUID.randomUUID().toString()
        val secretRef=if(secrets.isEmpty()) null else vault.save("$integrationId:$accountName",authType,secrets)
        val now=System.currentTimeMillis()
        val account=Account(id,integrationId,accountName,providerUserId,authType,scopes,secretRef,now,now)
        val arr=JSONArray(prefs.getString("accounts_$integrationId","[]")); arr.put(JSONObject().apply {
            put("id",id);put("name",accountName);put("providerUserId",providerUserId ?: "");put("authType",authType);put("scopes",JSONArray(scopes));put("secretRef",secretRef ?: "");put("connectedAt",now);put("lastUsed",now)
        })
        prefs.edit().putString("accounts_$integrationId",arr.toString()).putString("state_$integrationId",State.CONNECTED.name).apply()
        return account
    }

    fun disconnect(integrationId:String,accountId:String?=null) {
        val arr=JSONArray(prefs.getString("accounts_$integrationId","[]")); val out=JSONArray()
        for(i in 0 until arr.length()) { val o=arr.optJSONObject(i) ?: continue; if(accountId==null || o.optString("id")==accountId){o.optString("secretRef").takeIf{it.isNotBlank()}?.let{vault.delete(it)} } else out.put(o) }
        if(accountId==null) { prefs.edit().remove("accounts_$integrationId").putString("state_$integrationId",State.DISCONNECTED.name).apply() } else { prefs.edit().putString("accounts_$integrationId",out.toString()).putString("state_$integrationId",if(out.length()>0)State.CONNECTED.name else State.DISCONNECTED.name).apply() }
    }

    fun capabilities(integrationId:String): List<Capability> = find(integrationId)?.capabilities.orEmpty()

    suspend fun discoverInstalledApps(): List<Integration> = withContext(Dispatchers.Default) {
        val pm=context.packageManager
        val intent=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent,PackageManager.MATCH_ALL).mapNotNull { info ->
            val pkg=info.activityInfo.packageName
            val existing=integrations().firstOrNull { it.packageName==pkg } ?: Integration("android:$pkg",info.loadLabel(pm).toString(),pkg,null,listOf(Method.APP_TO_APP,Method.INTENT),listOf(Capability.OPEN_APP))
            existing
        }.distinctBy{it.id}.sortedBy{it.name.lowercase()}
    }

    fun addCustomIntegration(name:String,baseUrl:String,authType:String): Integration {
        val id="custom:"+UUID.randomUUID().toString(); val arr=JSONArray(prefs.getString("custom_integrations","[]"))
        arr.put(JSONObject().put("id",id).put("name",name).put("baseUrl",baseUrl).put("authType",authType))
        prefs.edit().putString("custom_integrations",arr.toString()).apply()
        return Integration(id,name,null,baseUrl,listOf(when(authType){"oauth"->Method.OAUTH;"api_key"->Method.API_KEY;"websocket"->Method.WEBSOCKET;else->Method.REST}),emptyList())
    }

    private fun loadCustomIntegrations(): List<Integration> = runCatching { JSONArray(prefs.getString("custom_integrations","[]")) }.getOrElse{JSONArray()}.let { arr -> buildList { for(i in 0 until arr.length()){val o=arr.optJSONObject(i)?:continue;add(Integration(o.optString("id"),o.optString("name"),null,o.optString("baseUrl"),listOf(Method.REST),emptyList()))} } }

    private fun JSONArray?.toStringList():List<String>{ if(this==null)return emptyList(); return buildList{for(i in 0 until length())add(optString(i))} }
}
