package net.jakstech.openlimit

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LimitStore(this).migrateFromPrefs(this)
        // Lets the "OpenLimit activo" notification be visible (Android 13+).
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        setContent { OpenLimitTheme { MainScreen(resumeTick.intValue) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private enum class ServiceState { OFF, STOPPED, RUNNING }

/**
 * OFF: not enabled in settings. STOPPED: enabled but not bound (it was killed and Android
 * marked it as crashed: it needs to be toggled off/on). RUNNING: working.
 */
private fun serviceState(ctx: android.content.Context): ServiceState {
    val me = ComponentName(ctx, LimitAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )?.split(':')?.any { ComponentName.unflattenFromString(it) == me } == true
    if (!enabled) return ServiceState.OFF
    val running = ctx.getSystemService(AccessibilityManager::class.java)
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == me.packageName && s.name == me.className } }
    return if (running) ServiceState.RUNNING else ServiceState.STOPPED
}

@Composable
private fun MainScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    val store = remember { LimitStore(ctx) }

    // Counts are written by the watcher process: re-read the files every second while visible.
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            version++
        }
    }

    val apps by produceState(emptyList<AppInfo>()) {
        value = withContext(Dispatchers.IO) { launchableApps(ctx) }
    }
    val limits = remember(version, resumeTick) { store.limits() }
    val counts = remember(version, resumeTick) { store.counts() }
    val service = remember(version, resumeTick) { serviceState(ctx) }
    val resetAt = remember(version, resumeTick) { resetTimeLabel() }
    var query by remember { mutableStateOf("") }

    val filtered = apps.filter { query.isBlank() || it.label.contains(query.trim(), true) }
    val (limited, rest) = filtered.partition { it.pkg in limits }

    LazyColumn(
        Modifier.fillMaxSize().background(Palette.Bg).safeDrawingPadding()
            .padding(horizontal = 20.dp),
    ) {
        item {
            Spacer(Modifier.height(28.dp))
            Text("Límites", color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Aperturas por hora · se reinicia a las $resetAt",
                color = Palette.Muted, fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
            if (service != ServiceState.RUNNING) {
                ServiceBanner(stopped = service == ServiceState.STOPPED) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            SearchField(query) { query = it }
            Spacer(Modifier.height(8.dp))
        }

        if (limited.isNotEmpty()) {
            item { SectionLabel("Con límite") }
            items(limited, key = { "l_" + it.pkg }) { app ->
                AppRow(app, limits[app.pkg], counts[app.pkg] ?: 0) {
                    store.setLimit(app.pkg, it)
                    version++
                }
            }
        }
        item { SectionLabel(if (apps.isEmpty()) "Cargando…" else "Apps") }
        items(rest, key = { "r_" + it.pkg }) { app ->
            AppRow(app, null, counts[app.pkg] ?: 0) {
                store.setLimit(app.pkg, it)
                version++
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun ServiceBanner(stopped: Boolean, onClick: () -> Unit) {
    val bg = if (stopped) Palette.Danger else Palette.Accent
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(bg).clickable(onClick = onClick).padding(18.dp)
    ) {
        Text(
            if (stopped) "El servicio se detuvo" else "Activa el servicio",
            color = Palette.Bg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (stopped) "El sistema lo cerró y no está contando. Accesibilidad → OpenLimit → desactívalo y vuelve a activarlo."
            else "Accesibilidad → OpenLimit → Activar. Es necesario para detectar y bloquear apps.",
            color = Palette.Bg.copy(alpha = 0.75f), fontSize = 13.sp,
        )
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        if (value.isEmpty()) Text("Buscar app", color = Palette.Muted, fontSize = 15.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = Palette.Text, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(), color = Palette.Muted, fontSize = 11.sp, letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun AppRow(app: AppInfo, limit: Int?, used: Int, onLimit: (Int?) -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<Bitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) { appIcon(ctx, app.pkg, 96) }
    }
    val blocked = limit != null && used >= limit

    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Card)) {
            icon?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = Palette.Text, fontSize = 16.sp, maxLines = 1)
            Text(
                when {
                    limit == null -> "Sin límite"
                    limit == 0 -> "Bloqueada"
                    blocked -> "$used / $limit · bloqueada · fijo hasta las ${resetTimeLabel()}"
                    else -> "$used / $limit esta hora"
                },
                color = if (blocked) Palette.Danger else Palette.Muted, fontSize = 13.sp,
            )
        }
        Stepper(limit, locked = blocked, onLimit)
    }
}

/**
 * − value + ; going below 0 removes the limit ("—").
 * When [locked] (limit used up this hour) it can only get stricter: no "+", no removing.
 */
@Composable
private fun Stepper(limit: Int?, locked: Boolean, onLimit: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton("−", enabled = limit != null && !(locked && limit == 0)) {
            onLimit(if (limit == null || limit == 0) null else limit - 1)
        }
        Text(
            limit?.toString() ?: "—",
            color = if (limit == null) Palette.Muted else Palette.Accent,
            fontSize = 17.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.width(34.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+", enabled = !locked && (limit ?: 0) < 99) {
            onLimit(if (limit == null) 3 else limit + 1)
        }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .border(1.dp, if (enabled) Palette.Line else Palette.Card, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Palette.Text else Palette.Line, fontSize = 18.sp)
    }
}
