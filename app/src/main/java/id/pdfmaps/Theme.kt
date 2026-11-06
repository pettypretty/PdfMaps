package id.pdfmaps

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF0F6B57),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDEFDD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4A635B),
    secondaryContainer = Color(0xFFCDE8DF),
    onSecondaryContainer = Color(0xFF07201A),
    tertiary = Color(0xFFB4541A),
    errorContainer = Color(0xFFFFDCC2),
    onErrorContainer = Color(0xFF3A1600),
    background = Color(0xFFF4FAF7),
    surface = Color(0xFFF4FAF7),
    surfaceVariant = Color(0xFFDCE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD8BE),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF00513F),
    onPrimaryContainer = Color(0xFFBDEFDD),
    secondaryContainer = Color(0xFF314B43),
    onSecondaryContainer = Color(0xFFCDE8DF),
    tertiary = Color(0xFFFFB68A),
    errorContainer = Color(0xFF5C2A00),
    onErrorContainer = Color(0xFFFFDCC2),
    background = Color(0xFF0E1512),
    surface = Color(0xFF0E1512),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
)

@Composable
fun PdfMapsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
