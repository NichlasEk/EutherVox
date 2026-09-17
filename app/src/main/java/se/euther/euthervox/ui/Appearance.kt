package se.euther.euthervox.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.security.MessageDigest

const val CLASSIC = "classic"
const val SYSTEM_REGIS = "system_regis"
val RegisNavy = Color(0xFF071523)
val RegisGold = Color(0xFFD4B86A)
val RegisIce = Color(0xFF8BCDE8)
val LocalRegis = staticCompositionLocalOf { false }

class AppearanceCache(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("euthervox", Context.MODE_PRIVATE)
    private fun key(user: String) = "appearance_" + MessageDigest.getInstance("SHA-256")
        .digest(user.trim().lowercase(java.util.Locale.ROOT).toByteArray()).joinToString("") { "%02x".format(it) }
    fun theme(user: String): String = preferences.getString(key(user), CLASSIC).let { if (it == SYSTEM_REGIS) SYSTEM_REGIS else CLASSIC }
    fun pending(user: String) = preferences.getBoolean(key(user) + "_pending", false)
    fun save(user: String, theme: String, pending: Boolean) {
        if (theme !in setOf(CLASSIC, SYSTEM_REGIS)) return
        check(preferences.edit().putString(key(user), theme).putBoolean(key(user) + "_pending", pending).commit())
    }
}

private val RegisColors = darkColorScheme(
    primary = RegisGold, onPrimary = RegisNavy, primaryContainer = Color(0xFF263349), onPrimaryContainer = Color(0xFFF0DCA8),
    secondary = RegisIce, onSecondary = RegisNavy, secondaryContainer = Color(0xFF16334A), onSecondaryContainer = Color(0xFFC6E8F5),
    tertiary = RegisIce, onTertiary = RegisNavy,
    background = RegisNavy, onBackground = Color(0xFFE5EDF4),
    surface = Color(0xFF0D2033), onSurface = Color(0xFFE5EDF4),
    surfaceVariant = Color(0xFF183149), onSurfaceVariant = Color(0xFFB6C8D8),
    surfaceContainerLowest = RegisNavy, surfaceContainerLow = Color(0xFF0D2033),
    surfaceContainer = Color(0xFF11273C), surfaceContainerHigh = Color(0xFF183149), surfaceContainerHighest = Color(0xFF203D55),
    outline = Color(0xFF82764F), outlineVariant = Color(0xFF30485D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF601410),
)

@Composable
fun VoxTheme(activity: Activity, content: @Composable () -> Unit) {
    val cache = remember { AppearanceCache(activity) }
    fun current() = cache.theme(cache.preferences.getString("username", "").orEmpty())
    var theme by remember { mutableStateOf(current()) }
    DisposableEffect(cache) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> theme = current() }
        cache.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { cache.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val regis = theme == SYSTEM_REGIS
    val colors = if (regis) RegisColors else lightColorScheme()
    SideEffect {
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (regis) RegisNavy.toArgb() else 0xFFF2EBDD.toInt()))
        activity.window.statusBarColor = if (regis) RegisNavy.toArgb() else 0xFFF2EBDD.toInt()
        activity.window.navigationBarColor = if (regis) RegisNavy.toArgb() else 0xFFF2EBDD.toInt()
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !regis
            isAppearanceLightNavigationBars = !regis
        }
    }
    CompositionLocalProvider(LocalRegis provides regis) {
        MaterialTheme(colorScheme = colors, shapes = if (regis) Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(10.dp), extraLarge = RoundedCornerShape(12.dp),
        ) else Shapes(), content = content)
    }
}
