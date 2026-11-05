package net.jakstech.openlimit

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen "blocked until HH:00" screen drawn as an accessibility overlay.
 * Unlike starting an Activity from the background, this needs no extra permission
 * (MIUI denies background activity starts by default).
 */
class BlockOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(pkg: String, limit: Int) {
        hide()
        val v = build(pkg, limit)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(v, lp); view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics)

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(service).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

    private fun space(h: Float) = View(service).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(h).toInt())
    }

    private fun build(pkg: String, limit: Int): View {
        val bg = Color.parseColor("#0E0E10")
        val text = Color.parseColor("#F2F2F3")
        val muted = Color.parseColor("#8A8A93")
        val accent = Color.parseColor("#C8F560")
        val line = Color.parseColor("#26262B")

        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            val pad = dp(40f).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = true // swallow touches so nothing behind is reachable

            appIcon(service, pkg, dp(72f).toInt())?.let { bmp ->
                addView(ImageView(service).apply {
                    setImageBitmap(bmp)
                    alpha = 0.45f
                    layoutParams = LinearLayout.LayoutParams(dp(72f).toInt(), dp(72f).toInt())
                })
                addView(space(28f))
            }
            addView(text(appLabel(service, pkg), 26f, text, bold = true))
            addView(space(10f))
            addView(
                text(
                    if (limit == 0) "Esta app está bloqueada."
                    else "Ya la abriste $limit ${if (limit == 1) "vez" else "veces"} esta hora.",
                    15f, muted,
                )
            )
            addView(space(36f))
            addView(text("Disponible a las", 13f, muted))
            addView(text(resetTimeLabel(), 56f, accent))
            addView(space(48f))
            addView(text("Entendido", 15f, text, bold = true).apply {
                val ph = dp(28f).toInt()
                val pv = dp(12f).toInt()
                setPadding(ph, pv, ph, pv)
                background = GradientDrawable().apply {
                    cornerRadius = dp(24f)
                    setStroke(dp(1f).toInt(), line)
                }
                setOnClickListener { hide() }
            })
        }
    }
}
