package cl.iac33.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IAC33Black = Color(0xFF050608)
private val IAC33Surface = Color(0xFF0B0F12)
private val IAC33Surface2 = Color(0xFF11171C)
private val IAC33Neon = Color(0xFF00FF9D)
private val IAC33NeonBlue = Color(0xFF00D9FF)
private val IAC33Purple = Color(0xFFB388FF)
private val IAC33Orange = Color(0xFFFFB74D)
private val IAC33Text = Color(0xFFE8FFF7)
private val IAC33Muted = Color(0xFF8AA39B)

private val IAC33Colors = darkColorScheme(
    primary = IAC33Neon,
    onPrimary = IAC33Black,
    primaryContainer = Color(0xFF003D2B),
    onPrimaryContainer = IAC33Text,
    secondary = IAC33NeonBlue,
    onSecondary = IAC33Black,
    secondaryContainer = Color(0xFF00333D),
    onSecondaryContainer = IAC33Text,
    background = IAC33Black,
    onBackground = IAC33Text,
    surface = IAC33Surface,
    onSurface = IAC33Text,
    surfaceVariant = IAC33Surface2,
    onSurfaceVariant = IAC33Muted,
    outline = Color(0xFF2A4039),
    error = Color(0xFFFF5577),
    onError = IAC33Black
)

@Composable
fun IAC33Theme(accent: String = "green", content: @Composable () -> Unit) {
    val primary = when (accent.lowercase()) {
        "blue" -> IAC33NeonBlue
        "purple" -> IAC33Purple
        "orange" -> IAC33Orange
        else -> IAC33Neon
    }
    val scheme = IAC33Colors.copy(
        primary = primary,
        onPrimary = IAC33Black
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
