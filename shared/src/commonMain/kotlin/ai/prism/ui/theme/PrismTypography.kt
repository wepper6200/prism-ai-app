package ai.prism.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BaseTypography = Typography()

internal val ExpressiveTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 26.sp),
)

internal val GlassTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(lineHeight = 22.sp),
)
