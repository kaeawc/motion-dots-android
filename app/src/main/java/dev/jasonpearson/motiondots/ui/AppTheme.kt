package dev.jasonpearson.motiondots.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDark =
    darkColorScheme(
        primary = Color(0xFF7FD7C4),
        onPrimary = Color(0xFF00382F),
        secondary = Color(0xFFB4CCC5),
        background = Color(0xFF0F1513),
        surface = Color(0xFF0F1513),
        surfaceVariant = Color(0xFF3F4945),
    )

private val FallbackLight =
    lightColorScheme(
        primary = Color(0xFF006B5C),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF4A635D),
        background = Color(0xFFF5FBF8),
        surface = Color(0xFFF5FBF8),
    )

/** Material3 theme: dynamic color on API 31+, a teal fallback palette otherwise. */
@Composable
fun MotionDotsAppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> FallbackDark
            else -> FallbackLight
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
