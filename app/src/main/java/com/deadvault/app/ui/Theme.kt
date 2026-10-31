package com.deadvault.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Mint = Color(0xFF6EE7B7)
val Danger = Color(0xFFFF7A7A)
val Amber = Color(0xFFFFC857)

private val Scheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F3D31),
    onPrimaryContainer = Mint,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF121821),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1B2330),
    onSurfaceVariant = Color(0xFF9FB0C0),
    error = Danger,
    onError = Color(0xFF3B0000),
    outline = Color(0xFF3A4757),
)

@Composable
fun DeadVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
