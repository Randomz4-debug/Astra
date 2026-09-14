package com.astra.ai

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.rememberInfiniteTransition as coreRememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton

/** Dependency-safe wrappers used by Astra's lightweight UI. */
@Composable
fun rememberInfiniteTransition(label: String): InfiniteTransition = coreRememberInfiniteTransition(label)

@Composable
fun InfiniteTransition.animateFloat(initialValue: Float, targetValue: Float, animationSpec: InfiniteRepeatableSpec<Float>, label: String): State<Float> = remember(label) { mutableFloatStateOf(initialValue) }

@Composable
fun InfiniteTransition.animateFloat(initialValue: Float, targetValue: Double, animationSpec: InfiniteRepeatableSpec<Float>, label: String): State<Float> = remember(label) { mutableFloatStateOf(initialValue) }

fun sin(value: Float): Float = kotlin.math.sin(value.toDouble()).toFloat()

@Composable
fun NavigationDrawerItem(label: @Composable () -> Unit, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) { label() }
}

/** Compatibility overload for an older Astra UI call that used named color before modifier. */
@Composable
fun Text(text: String, color: Color, modifier: Modifier) {
    androidx.compose.material3.Text(text = text, color = color, modifier = modifier)
}
