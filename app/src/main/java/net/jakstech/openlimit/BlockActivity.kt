package net.jakstech.openlimit

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

/** Full-screen "this app is blocked until HH:00" screen. */
class BlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        val limit = intent.getIntExtra(EXTRA_LIMIT, 0)
        setContent { OpenLimitTheme { BlockScreen(pkg, limit) { goHome() } } }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    @Deprecated("Back always goes home")
    override fun onBackPressed() = goHome()

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LIMIT = "limit"
    }
}

@Composable
private fun BlockScreen(pkg: String, limit: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val label = remember(pkg) { appLabel(ctx, pkg) }
    val icon = remember(pkg) { appIcon(ctx, pkg, 192) }
    Column(
        Modifier.fillMaxSize().background(Palette.Bg).padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Image(
                it.asImageBitmap(), null,
                Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).alpha(0.45f),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(label, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            if (limit == 0) "Esta app está bloqueada."
            else "Ya la abriste $limit ${if (limit == 1) "vez" else "veces"} esta hora.",
            color = Palette.Muted, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        Text("Disponible a las", color = Palette.Muted, fontSize = 13.sp)
        Text(
            resetTimeLabel(), color = Palette.Accent,
            fontSize = 56.sp, fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = onClose) {
            Text("Entendido", color = Palette.Text, fontSize = 15.sp)
        }
    }
}
