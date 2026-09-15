package com.astra.ai

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Voice-friendly file intake surface. It owns the picker so a voice command can continue into Chat. */
class AstraFilePickerActivity : ComponentActivity() {
    private lateinit var status: TextView
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            status.text = "No file selected."
            return@registerForActivityResult
        }
        process(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        status = TextView(this).apply { text = "Choose a file for Astra…"; textSize = 20f }
        root.addView(status)
        setContentView(root)
        picker.launch(arrayOf("*/*"))
    }

    private fun process(uris: List<android.net.Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val manager = AstraAttachmentManager(this@AstraFilePickerActivity)
            val names = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "attachment" } ?: "attachment"
                names += name
                withContext(Dispatchers.Main) { status.text = "Reading ${index + 1}/${uris.size}: $name…" }
                manager.importAndProcess(uri)
            }
            withContext(Dispatchers.Main) {
                status.text = "Ready: ${names.joinToString(", ")}."
                startActivity(android.content.Intent(this@AstraFilePickerActivity, AstraChatActivity::class.java).putStringArrayListExtra("astra_ready_files", ArrayList(names)).addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }
}
