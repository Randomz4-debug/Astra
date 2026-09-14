package com.astra.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

class CallManager(private val context: Context) {
    fun placeCall(number: String, confirmed: Boolean): ToolResult {
        if (!confirmed) return ToolResult(false, "CONFIRMATION_REQUIRED: place call to $number")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return ToolResult(false, "Astra needs Phone permission before it can place a call.")
        }
        val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(number)
        if (normalized.isBlank()) return ToolResult(false, "I need a valid phone number.")
        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalized")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult(true, "Calling $normalized.")
        } catch (_: SecurityException) { ToolResult(false, "Android denied the phone-call permission.") }
    }
}
