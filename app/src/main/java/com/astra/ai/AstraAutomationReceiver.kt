package com.astra.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AstraAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getStringExtra("workflow_id") ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val engine = AstraAutomationEngine(context.applicationContext)
                val workflow = engine.get(id)
                if (workflow?.enabled == true) engine.run(id)
            } finally { pending.finish() }
        }
    }
}
