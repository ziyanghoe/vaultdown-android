package dev.vaultdown.app

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Mint = Color(0xFF86DFC5)
val Violet = Color(0xFFB7A1FF)
private val Colors = darkColorScheme(
    primary = Violet, onPrimary = Color(0xFF211445), secondary = Mint,
    background = Color(0xFF111217), surface = Color(0xFF191B23),
    surfaceVariant = Color(0xFF242630), onSurface = Color(0xFFE8E7ED),
    onSurfaceVariant = Color(0xFFAAA8B8), outline = Color(0xFF393B49),
    error = Color(0xFFFFB4AB)
)
@Composable fun VaultdownTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
