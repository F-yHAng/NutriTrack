package com.example.nutritrack.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.nutritrack.R

@OptIn(ExperimentalTextApi::class)
private val Geist = FontFamily(
    Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.geist, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.geist, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

private fun editorialStyle(size: Int, line: Int, weight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = Geist, fontWeight = weight, fontSize = size.sp,
    lineHeight = line.sp, letterSpacing = tracking.sp
)

val Typography = Typography(
    displayLarge = editorialStyle(48, 54, FontWeight.SemiBold, -1.8f),
    displayMedium = editorialStyle(40, 46, FontWeight.SemiBold, -1.4f),
    displaySmall = editorialStyle(34, 40, FontWeight.SemiBold, -1.1f),
    headlineLarge = editorialStyle(30, 36, FontWeight.SemiBold, -0.8f),
    headlineMedium = editorialStyle(26, 32, FontWeight.SemiBold, -0.7f),
    headlineSmall = editorialStyle(22, 28, FontWeight.SemiBold, -0.4f),
    titleLarge = editorialStyle(20, 26, FontWeight.SemiBold, -0.3f),
    titleMedium = editorialStyle(16, 22, FontWeight.SemiBold, -0.1f),
    titleSmall = editorialStyle(14, 20, FontWeight.SemiBold),
    bodyLarge = editorialStyle(16, 24, FontWeight.Normal),
    bodyMedium = editorialStyle(14, 21, FontWeight.Normal),
    bodySmall = editorialStyle(12, 18, FontWeight.Normal),
    labelLarge = editorialStyle(14, 20, FontWeight.SemiBold),
    labelMedium = editorialStyle(12, 16, FontWeight.Medium),
    labelSmall = editorialStyle(11, 16, FontWeight.Medium, 0.2f)
)
