package tech.asahiart.luvia.ui.theme

import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import java.util.concurrent.atomic.AtomicBoolean
import tech.asahiart.luvia.R

private val prewarmed = AtomicBoolean(false)

private val prewarmSample: String = buildString {
    append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
    append("abcdefghijklmnopqrstuvwxyz")
    append("0123456789")
    append(" !@#\$%^&*()[]{}<>/\\|\"'`~_+=-.,;:?")
    ('\uE0A0'..'\uE0BF').forEach { append(it) }
    append("─│┌┐└┘├┤┬┴┼═║╔╗╚╝▀▄█░▒▓")
}

fun prewarmTerminalFont(context: Context) {
    if (!prewarmed.compareAndSet(false, true)) return
    val typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_nf) ?: return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            13f,
            context.resources.displayMetrics,
        )
    }
    paint.measureText(prewarmSample)
}
