package com.example.voicetomidi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.voicetomidi.R

/** Police d'affiche façon flyer de concert — utilisée pour les titres et boutons. */
val DisplayFontFamily = FontFamily(Font(R.font.bebas_neue))

// Police système par défaut pour le contenu dense (notes, Hz) : reste lisible à petite taille.
val Typography = Typography(
    headlineSmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        letterSpacing = 2.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        letterSpacing = 1.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        letterSpacing = 1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.8.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DisplayFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = 1.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.3.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp
    )
)
