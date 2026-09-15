package com.astra.ai

import android.content.Context

/** Controlled tool facade exposed to Astra's reasoning layer; it never exposes secrets to the model. */
class AstraConnectionTools(context:Context) {
    private val manager=AstraConnectionManager(context.applicationContext)
    fun getConnectedApps():String=manager.records().filter{it.state==AstraConnectionManager.State.CONNECTED}.joinToString(", "){it.integration.name}
    fun getStatus(app:String):String { val i=manager.integrations().firstOrNull{it.name.equals(app,true)||it.id.equals(app,true)}?:return "App not found.";return "${i.name}: ${manager.state(i.id)}; accounts=${manager.accounts(i.id).size}; capabilities=${manager.capabilities(i.id).joinToString()}" }
    fun getCapabilities(app:String):List<AstraConnectionManager.Capability>{val i=manager.integrations().firstOrNull{it.name.equals(app,true)||it.id.equals(app,true)}?:return emptyList();return manager.capabilities(i.id)}
    fun openApp(app:String):String { val i=manager.integrations().firstOrNull{it.name.equals(app,true)||it.id.equals(app,true)}?:return "App not found.";return if(manager.openApp(i))"Opened ${i.name}." else "Could not open ${i.name}." }
    fun disconnect(app:String):String { val i=manager.integrations().firstOrNull{it.name.equals(app,true)||it.id.equals(app,true)}?:return "App not found.";if(manager.state(i.id)!=AstraConnectionManager.State.CONNECTED)return "${i.name} is not connected.";manager.disconnect(i.id);return "Disconnected ${i.name} from Astra." }
}
