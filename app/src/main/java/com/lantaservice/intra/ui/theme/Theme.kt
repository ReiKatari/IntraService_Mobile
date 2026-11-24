package com.lantaservice.intra.ui.theme

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

object IntraColors {
    val BrandBlueDeep = Color(0xFF1A6FB5)
    val BrandBlueMid = Color(0xFF2894D1)
    val BrandCyan = Color(0xFF38B3DF)
    val BrandPrimary = Color(0xFF1E7EC8)
    val BrandSteelBorder = Color(0xFF6186A8)
    val SubtleBorder = Color(0xFFDCE7F2)

    val EmeraldSuccess = Color(0xFF1FA866)
    val EmeraldDeep = Color(0xFF137C49)
    val EmeraldTint = Color(0xFFE7F8EF)

    val SkyTint = Color(0xFFE7F3FD)
    val SkyText = Color(0xFF16619C)

    val WarningAmber = Color(0xFFD97706)
    val WarningTint = Color(0xFFFEF3C7)

    val ErrorRed = Color(0xFFE5484D)
    val ErrorTint = Color(0xFFFDECEC)

    val BackgroundIce = Color(0xFFF3F7FB)
    val SurfaceWhite = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF8FBFE)

    val TextPrimary = Color(0xFF152536)
    val TextSecondary = Color(0xFF52687D)
    val TextTertiary = Color(0xFF7D92A6)
}

private val IntraLightColorScheme = lightColorScheme(
    primary = IntraColors.BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = IntraColors.SkyTint,
    onPrimaryContainer = IntraColors.SkyText,
    secondary = IntraColors.EmeraldSuccess,
    onSecondary = Color.White,
    secondaryContainer = IntraColors.EmeraldTint,
    onSecondaryContainer = IntraColors.EmeraldDeep,
    background = IntraColors.BackgroundIce,
    onBackground = IntraColors.TextPrimary,
    surface = IntraColors.SurfaceWhite,
    onSurface = IntraColors.TextPrimary,
    surfaceVariant = IntraColors.SurfaceMuted,
    onSurfaceVariant = IntraColors.TextSecondary,
    outline = IntraColors.SubtleBorder,
    error = IntraColors.ErrorRed,
    onError = Color.White,
    errorContainer = IntraColors.ErrorTint,
    onErrorContainer = IntraColors.ErrorRed
)

private val IntraTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
)

private val IntraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp)
)

@Composable
fun IntraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IntraLightColorScheme,
        typography = IntraTypography,
        shapes = IntraShapes,
        content = content
    )
}
