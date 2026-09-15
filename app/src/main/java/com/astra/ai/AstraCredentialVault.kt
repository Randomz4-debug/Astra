package com.astra.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** n8n-style reusable credential vault. Secrets are encrypted with an Android Keystore AES key. */
class AstraCredentialVault(context: Context) {
    data class Credential(val id:String,val name:String,val type:String,val fields:Map<String,String>)
    private val prefs=context.applicationContext.getSharedPreferences("astra_credentials",Context.MODE_PRIVATE)
    private val alias="astra.credentials.v1"
    init { ensureKey() }
    private fun ensureKey(): SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(alias,null) as? SecretKey)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setUserAuthenticationRequired(false).build())
        return gen.generateKey()
    }
    private fun encrypt(value:String):String {
        val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,ensureKey()); val out=ByteArray(c.iv.size+c.doFinal(value.toByteArray(StandardCharsets.UTF_8)).size); System.arraycopy(c.iv,0,out,0,c.iv.size); System.arraycopy(c.doFinal(value.toByteArray(StandardCharsets.UTF_8)),0,out,c.iv.size,out.size-c.iv.size); return Base64.encodeToString(out,Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        val raw=Base64.decode(value,Base64.NO_WRAP); val iv=raw.copyOfRange(0,12); val data=raw.copyOfRange(12,raw.size); val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,ensureKey(),GCMParameterSpec(128,iv)); return String(c.doFinal(data),StandardCharsets.UTF_8)
    }
    fun save(name:String,type:String,fields:Map<String,String>,id:String?=null):String {
        val arr=JSONArray(prefs.getString("items","[]")); val key=id ?: java.util.UUID.randomUUID().toString(); val out=JSONArray(); var replaced=false
        for(i in 0 until arr.length()){val o=arr.getJSONObject(i); if(o.optString("id")==key){out.put(JSONObject().put("id",key).put("name",name).put("type",type).put("secret",encrypt(JSONObject(fields).toString()))); replaced=true}else out.put(o)}
        if(!replaced)out.put(JSONObject().put("id",key).put("name",name).put("type",type).put("secret",encrypt(JSONObject(fields).toString())))
        prefs.edit().putString("items",out.toString()).apply(); return key
    }
    fun list():List<Credential>{val arr=JSONArray(prefs.getString("items","[]"));return buildList{for(i in 0 until arr.length()){val o=arr.getJSONObject(i);val f=runCatching{JSONObject(decrypt(o.optString("secret")))}.getOrNull();val map=mutableMapOf<String,String>();f?.keys()?.forEach{map[it]=f.optString(it)};add(Credential(o.optString("id"),o.optString("name"),o.optString("type"),map))}}}
    fun delete(id:String){val arr=JSONArray(prefs.getString("items","[]"));val out=JSONArray();for(i in 0 until arr.length())if(arr.getJSONObject(i).optString("id")!=id)out.put(arr.getJSONObject(i));prefs.edit().putString("items",out.toString()).apply()}
    fun clear(){prefs.edit().remove("items").apply()}
    fun redactedJson():String{val out=JSONArray();list().forEach{out.put(JSONObject().put("id",it.id).put("name",it.name).put("type",it.type).put("fields",it.fields.keys.joinToString(",")))};return out.toString()}
}
