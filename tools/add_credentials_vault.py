from pathlib import Path

ROOT = Path('app/src/main/java/com/astra/ai')

vault = r'''package com.astra.ai

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

/** n8n-like reusable credential vault. Secrets are encrypted with an Android Keystore AES key. */
class AstraCredentialVault(context: Context) {
    data class Credential(val id:String,val name:String,val service:String,val authType:String,val baseUrl:String,val publicValue:String,val secret:String,val extra:String)
    private val prefs = context.applicationContext.getSharedPreferences("astra_credentials", Context.MODE_PRIVATE)
    private val alias = "astra_credentials_aes_v1"
    private val store = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks=KeyStore.getInstance(store).apply{load(null)}
        val existing=ks.getKey(alias,null) as? SecretKey
        if(existing!=null)return existing
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,store)
        gen.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    private fun encrypt(value:String):String{val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());val iv=Base64.encodeToString(cipher.iv,Base64.NO_WRAP);val data=Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),Base64.NO_WRAP);return "$iv.$data"}
    private fun decrypt(value:String):String{val parts=value.split('.',limit=2);if(parts.size!=2)return "";val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));return String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8)}
    fun list():List<Credential>{val arr=JSONArray(prefs.getString("items","[]"));return buildList{for(i in 0 until arr.length()){val o=arr.getJSONObject(i);add(Credential(o.optString("id"),o.optString("name"),o.optString("service"),o.optString("authType"),o.optString("baseUrl"),o.optString("publicValue"),decrypt(o.optString("secret")),decrypt(o.optString("extra"))))}}}
    fun save(c:Credential){val old=JSONArray(prefs.getString("items","[]"));val out=JSONArray();var replaced=false;for(i in 0 until old.length()){val o=old.getJSONObject(i);if(o.optString("id")==c.id){out.put(toJson(c));replaced=true}else out.put(o)};if(!replaced)out.put(toJson(c));prefs.edit().putString("items",out.toString()).apply()}
    fun delete(id:String){val old=JSONArray(prefs.getString("items","[]"));val out=JSONArray();for(i in 0 until old.length())if(old.getJSONObject(i).optString("id")!=id)out.put(old.getJSONObject(i));prefs.edit().putString("items",out.toString()).apply()}
    private fun toJson(c:Credential)=JSONObject().put("id",c.id).put("name",c.name).put("service",c.service).put("authType",c.authType).put("baseUrl",c.baseUrl).put("publicValue",c.publicValue).put("secret",encrypt(c.secret)).put("extra",encrypt(c.extra))
}
'''
(ROOT/'AstraCredentialVault.kt').write_text(vault,encoding='utf-8')

activity = r'''package com.astra.ai

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AstraCredentialsActivity:ComponentActivity(){
    private lateinit var vault:AstraCredentialVault
    private lateinit var list:LinearLayout
    override fun onCreate(state:Bundle?){super.onCreate(state);vault=AstraCredentialVault(this);build();refresh()}
    private fun build(){val root=ScrollView(this);val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,20,20,28);setBackgroundColor(android.graphics.Color.rgb(5,6,10))};root.addView(box);val title=TextView(this).apply{text="CREDENTIALS & CONNECTIONS";textSize=23f;setTextColor(android.graphics.Color.WHITE)};box.addView(title);box.addView(TextView(this).apply{text="Encrypted reusable secrets for n8n-style app connections. Astra never needs you to paste secrets into chat.";setTextColor(android.graphics.Color.LTGRAY);setPadding(0,8,0,14)});val service=Spinner(this);service.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("Custom API","Instagram / Meta","Facebook / Meta","WhatsApp Cloud","Telegram Bot","Google","Webhook","OAuth 2.0"));box.addView(service);val name=field("Credential name");val auth=Spinner(this);auth.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("API Key","Bearer Token","Bot Token","Basic","OAuth 2.0","Webhook Secret"));val url=field("Base / API URL");val publicValue=field("Public ID / Client ID / Phone ID (optional)");val secret=field("Secret / API Key / Access Token",true);val extra=field("Client secret / extra value (optional)");box.addView(name);box.addView(auth);box.addView(url);box.addView(publicValue);box.addView(secret);box.addView(extra);val status=TextView(this).apply{setTextColor(android.graphics.Color.LTGRAY)};box.addView(status);val save=Button(this).apply{text="Save encrypted credential"};save.setOnClickListener{val n=name.text.toString().trim();if(n.isBlank()){status.text="Enter a credential name";return@setOnClickListener};vault.save(AstraCredentialVault.Credential(UUID.randomUUID().toString(),n,service.selectedItem.toString(),auth.selectedItem.toString(),url.text.toString().trim(),publicValue.text.toString().trim(),secret.text.toString(),extra.text.toString()));secret.setText("");extra.setText("");status.text="Saved securely on this device";refresh()};box.addView(save);list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};box.addView(list);setContentView(root)}
    private fun field(hint:String,password:Boolean=false)=EditText(this).apply{this.hint=hint;setSingleLine(false);setTextColor(android.graphics.Color.WHITE);setHintTextColor(android.graphics.Color.GRAY);setPadding(12,10,12,10);if(password)inputType=0x00000081}
    private fun refresh(){if(!::list.isInitialized)return;list.removeAllViews();vault.list().forEach{c->val row=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(12,12,12,12);setBackgroundColor(android.graphics.Color.rgb(18,20,28))};row.addView(TextView(this).apply{text="${c.name} • ${c.service}";textSize=16f;setTextColor(android.graphics.Color.WHITE)});row.addView(TextView(this).apply{text="${c.authType} • ${c.baseUrl.ifBlank{"No URL"}}";setTextColor(android.graphics.Color.LTGRAY)});val actions=LinearLayout(this);val test=Button(this).apply{text="Test"};test.setOnClickListener{testCredential(c)};val del=Button(this).apply{text="Delete"};del.setOnClickListener{vault.delete(c.id);refresh()};actions.addView(test);actions.addView(del);row.addView(actions);list.addView(row);val gap=Space(this).apply{minimumHeight=10};list.addView(gap)}}
    private fun testCredential(c:AstraCredentialVault.Credential){if(c.baseUrl.isBlank()){Toast.makeText(this,"Add a test/API URL first",Toast.LENGTH_SHORT).show();return};lifecycleScope.launch(Dispatchers.IO){val result=runCatching{val conn=URL(c.baseUrl).openConnection() as HttpURLConnection;conn.connectTimeout=5000;conn.readTimeout=8000;if(c.authType=="Bearer Token"||c.authType=="Bot Token")conn.setRequestProperty("Authorization","Bearer ${c.secret}") else if(c.authType=="API Key")conn.setRequestProperty("X-API-Key",c.secret);val code=conn.responseCode;conn.disconnect();"HTTP $code"}.getOrElse{"Connection failed: ${it.message}"};withContext(Dispatchers.Main){Toast.makeText(this@AstraCredentialsActivity,result,Toast.LENGTH_LONG).show()}}}
}
'''
(ROOT/'AstraCredentialsActivity.kt').write_text(activity,encoding='utf-8')

# Fix known compile errors from the previous failed build after the intelligence generator runs.
chat=ROOT/'AstraChatActivity.kt'
if chat.exists():
    s=chat.read_text(encoding='utf-8').replace('Message $assistantName…','Message Astra…').replace('assistantName','assistantName_unused')
    # The generated previous patch may have inserted an unresolved local. Remove it safely.
    s=s.replace('var assistantName_unused by remember { mutableStateOf("Astra") }','')
    s=s.replace('assistantName_unused = getSharedPreferences("astra_runtime", MODE_PRIVATE).getString("assistant_name", "Astra") ?: "Astra"','')
    chat.write_text(s,encoding='utf-8')

# Patch manifest exactly once.
manifest=Path('app/src/main/AndroidManifest.xml'); m=manifest.read_text(encoding='utf-8')
line='        <activity android:name=".AstraCredentialsActivity" android:exported="false" android:label="Astra Credentials"/>\n'
if 'AstraCredentialsActivity' not in m:m=m.replace('        <activity android:name=".AstraApiActivity" android:exported="false" android:label="Astra API Hub" />\n','        <activity android:name=".AstraApiActivity" android:exported="false" android:label="Astra API Hub" />\n'+line)
manifest.write_text(m,encoding='utf-8')

# Put the credential vault directly into the Settings page.
main=ROOT/'AstraMainActivity.kt'; s=main.read_text(encoding='utf-8')
needle='            if (page == 1) {\n                item {'
insert='''            if (page == 1) {\n                item {\n                    Text("CONNECTIONS", color = Color.White, style = MaterialTheme.typography.titleMedium)\n                    Text("Connect Astra to Instagram, Facebook, WhatsApp, Telegram, Google, webhooks and other services using reusable encrypted credentials.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)\n                    Button({ a.startActivity(Intent(a, AstraCredentialsActivity::class.java)) }, Modifier.fillMaxWidth()) { Text("🔐 Manage Credentials & Connections") }\n                }\n                item {'''
if needle in s and 'Manage Credentials & Connections' not in s:s=s.replace(needle,insert,1)
main.write_text(s,encoding='utf-8')
print('Credential vault and settings UI added')
