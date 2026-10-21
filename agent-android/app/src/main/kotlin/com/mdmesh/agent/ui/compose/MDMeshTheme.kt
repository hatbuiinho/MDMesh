package com.mdmesh.agent.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AgentColors = lightColorScheme(
    primary = Color(0xFF075CCB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EFFF), onPrimaryContainer = Color(0xFF143D75),
    secondary = Color(0xFF075CCB), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EFFF), onSecondaryContainer = Color(0xFF143D75),
    background = Color(0xFFF4F7FC), onBackground = Color(0xFF172B4D),
    surface = Color.White, onSurface = Color(0xFF172B4D),
    surfaceVariant = Color(0xFFEDF2F8), onSurfaceVariant = Color(0xFF52647A),
    outline = Color(0xFF64748B), outlineVariant = Color(0xFFDCE3EC),
    error = Color(0xFFB3261E), onError = Color.White,
)
private val AgentTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
)

/** Shared light palette and density for the Agent and its management screens. */
@Composable
internal fun MDMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AgentColors,
        typography = AgentTypography,
        shapes = Shapes(small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp)),
        content = content,
    )
}
