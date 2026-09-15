package com.astra.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Provider-neutral OAuth 2 authorization helper. Provider registration remains explicit; Astra never collects passwords. */
class AstraOAuthManager(private val context: Context) {
    data class Session(val state:String,val verifier:String,val challenge:String,val redirectUri:String,val createdAt:Long)
    private val prefs=context.getSharedPreferences("astra_oauth_sessions",Context.MODE_PRIVATE)

    fun createSession(redirectUri:String):Session {
        val verifier=randomUrlSafe(48)
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier))
        val state=randomUrlSafe(32)
        val s=Session(state,verifier,challenge,redirectUri,System.currentTimeMillis())
        prefs.edit().putString("state",state).putString("verifier",verifier).putString("redirect",redirectUri).putLong("created",s.createdAt).apply()
        return s
    }

    fun authorizationUri(base:String,clientId:String,redirectUri:String,scope:String,session:Session,extra:Map<String,String> = emptyMap()):Uri {
        return Uri.parse(base).buildUpon().apply {
            appendQueryParameter("response_type","code")
            appendQueryParameter("client_id",clientId)
            appendQueryParameter("redirect_uri",redirectUri)
            appendQueryParameter("scope",scope)
            appendQueryParameter("state",session.state)
            appendQueryParameter("code_challenge",session.challenge)
            appendQueryParameter("code_challenge_method","S256")
            extra.forEach{(k,v)->appendQueryParameter(k,v)}
        }.build()
    }

    fun openAuthorization(uri:Uri):Boolean = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    fun validateCallback(uri:Uri):Boolean {
        val expected=prefs.getString("state",null) ?: return false
        val received=uri.getQueryParameter("state") ?: return false
        return MessageDigest.isEqual(expected.toByteArray(),received.toByteArray()) && System.currentTimeMillis()-prefs.getLong("created",0L) <= 10*60*1000L
    }

    fun verifier():String?=prefs.getString("verifier",null)
    fun clearSession(){prefs.edit().clear().apply()}
    private fun randomUrlSafe(bytes:Int):String { val b=ByteArray(bytes);SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b) }
    private fun sha256(s:String):ByteArray=MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
}
