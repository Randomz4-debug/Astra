package com.astra.ai

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AstraScreen()
            }
        }
    }
}

@Composable
fun AstraScreen(vm: AstraViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    val pulse = rememberInfiniteTransition(label = "pulse")
        .animateFloat(
            initialValue = 1f, targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "scale"
        )

    Column(
        Modifier.fillMaxSize().background(Color(0xFF08090D)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        Text("ASTRA", style = MaterialTheme.typography.displaySmall)
        Text(ui.state.name, color = Color.LightGray)

        Spacer(Modifier.weight(1f))

        Box(
            Modifier.size(180.dp).scale(pulse.value).background(
                MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape
            ),
            contentAlignment = Alignment.Center
        ) {
            Text("A", style = MaterialTheme.typography.displayLarge)
        }

        Spacer(Modifier.height(24.dp))
        Text(ui.transcript, color = Color.LightGray)
        Text(ui.response, style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.weight(1f))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Talk to Astra") },
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { vm.ask(input); input = "" },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Send") }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(ui.localOnly, onCheckedChange = vm::setLocalOnly)
                Text("Local only")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(ui.background, onCheckedChange = vm::setBackground)
                Text("Background")
            }
        }
    }
}
