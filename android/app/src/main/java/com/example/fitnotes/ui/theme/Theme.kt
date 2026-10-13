package com.example.fitnotes.ui.theme

import android.content.Context
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode { System, Light, Dark }

@Stable
class Appearance(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var mode by mutableStateOf(runCatching { ThemeMode.valueOf(preferences.getString("theme_mode", "System")!!) }.getOrDefault(ThemeMode.System))
        private set
    var favourites by mutableStateOf(preferences.getStringSet("favourite_exercises", emptySet())!!.toSet())
        private set
    fun choose(mode: ThemeMode) { this.mode = mode; preferences.edit().putString("theme_mode", mode.name).apply() }
    fun setFavourite(keys:List<String>,nameKey:String,wanted:Boolean) {
        favourites=(favourites-keys.toSet())+(if(wanted)setOf(nameKey) else emptySet())
        preferences.edit().putStringSet("favourite_exercises",favourites).apply()
    }
    fun refreshFavourites() { favourites=preferences.getStringSet("favourite_exercises",emptySet())!!.toSet() }
    fun toggleFavourite(nameKey: String) {
        favourites = if (nameKey in favourites) favourites - nameKey else favourites + nameKey
        preferences.edit().putStringSet("favourite_exercises", favourites).apply()
    }
}
val LocalAppearance = staticCompositionLocalOf<Appearance> { error("Appearance is unavailable") }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0866F5), onPrimary = Color.White, primaryContainer = Color(0xFFE0EBFF), onPrimaryContainer = Color(0xFF074799),
    secondary = Color(0xFF58667B), secondaryContainer = Color(0xFFEAF0F9), onSecondaryContainer = Color(0xFF173356),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF121B2C),
    surface = Color(0xFFFFFEFC), onSurface = Color(0xFF121B2C), surfaceVariant = Color(0xFFF0F3F7), onSurfaceVariant = Color(0xFF59667B),
    surfaceContainer = Color(0xFFF0F3F7), surfaceContainerLow = Color(0xFFFFFEFC), surfaceContainerHigh = Color(0xFFF0F3F7),
    outline = Color(0xFFBAC3CF), outlineVariant = Color(0xFFE1E5EB)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BC0FF), onPrimary = Color(0xFF00316F), primaryContainer = Color(0xFF173E72), onPrimaryContainer = Color(0xFFDEE9FF),
    secondary = Color(0xFFB2C4DF), secondaryContainer = Color(0xFF24354F), onSecondaryContainer = Color(0xFFDCE7FA),
    background = Color(0xFF11151B), onBackground = Color(0xFFE7EBF3),
    surface = Color(0xFF1B2028), onSurface = Color(0xFFE7EBF3), surfaceVariant = Color(0xFF242C38), onSurfaceVariant = Color(0xFFB3BDCD),
    surfaceContainer = Color(0xFF242C38), surfaceContainerLow = Color(0xFF1B2028), surfaceContainerHigh = Color(0xFF242C38),
    outline = Color(0xFF667287), outlineVariant = Color(0xFF343E4D)
)
@Composable
fun FitNotesTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val appearance = remember { Appearance(context.applicationContext) }
    val dark = when (appearance.mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    SideEffect {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
    }
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography,
            shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(12.dp)), content = content)
    }
}
