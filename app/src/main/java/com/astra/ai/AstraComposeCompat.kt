package com.astra.ai

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/** Keeps the wave renderer dependency-light; the phase is intentionally stable if animation-core is unavailable. */
@Composable
fun InfiniteTransition.animateFloat(initialValue: Float, targetValue: Float, animationSpec: Any, label: String): State<Float> {
    return remember(label) { mutableFloatStateOf(initialValue) }
}
