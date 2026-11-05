package net.jakstech.openlimit

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.createBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Palette {
    val Bg = Color(0xFF0E0E10)
    val Card = Color(0xFF18181B)
    val Line = Color(0xFF26262B)
    val Text = Color(0xFFF2F2F3)
    val Muted = Color(0xFF8A8A93)
    val Accent = Color(0xFFC8F560)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun OpenLimitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Palette.Bg,
            surface = Palette.Bg,
            primary = Palette.Accent,
            onPrimary = Palette.Bg,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
        ),
        content = content,
    )
}

fun resetTimeLabel(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(LimitStore.nextResetMillis()))

fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

fun appIcon(context: Context, pkg: String, sizePx: Int): Bitmap? = runCatching {
    val d = context.packageManager.getApplicationIcon(pkg)
    createBitmap(sizePx, sizePx).also { bmp ->
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(Canvas(bmp))
    }
}.getOrNull()

data class AppInfo(val pkg: String, val label: String)

fun launchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
        .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { AppInfo(it, appLabel(context, it)) }
        .sortedBy { it.label.lowercase() }
}
