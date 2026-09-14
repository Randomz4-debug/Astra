package com.astra.ai

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.rememberInfiniteTransition as coreRememberInfiniteTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember

/** Dependency-safe wrappers used by Astra's lightweight UI animation. */
@Composable
fun rememberInfiniteTransition(label: String): InfiniteTransition = coreRememberInfiniteTransition(label)

@Composable
fun InfiniteTransition.animateFloat(initialValue: Float, targetValue: Float, animationSpec: Any, label: String): State<Float> = remember(label) { mutableFloatStateOf(initialValue) }

@Composable
fun InfiniteTransition.animateFloat(initialValue: Float, targetValue: Double, animationSpec: Any, label: String): State<Float> = remember(label) { mutableFloatStateOf(initialValue) }

fun sin(value: Float): Float = kotlin.math.sin(value.toDouble()).toFloat()
