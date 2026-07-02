package club.orden.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Platinum = Color(0xFFC9CED6)  // brand silver — matches the steel shield logo
private val SteelBlue = Color(0xFF3FA9E0) // accent from the logo (padlock / VPN)

private val DarkColors = darkColorScheme(
    primary = Platinum,
    onPrimary = Color(0xFF0B0C10),
    secondary = SteelBlue,
    onSecondary = Color(0xFF04121C),
    tertiary = SteelBlue,
    background = Color(0xFF0B0C10),
    surface = Color(0xFF14161C),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B6470),
)

@Composable
fun TunnelTheme(
    // Brand is steel/silver on near-black; force dark for a consistent premium look.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
