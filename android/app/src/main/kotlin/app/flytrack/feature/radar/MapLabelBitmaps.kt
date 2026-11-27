package app.flytrack.feature.radar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import org.maplibre.android.maps.Style

/**
 * Text labels for the offline maps, drawn as bitmaps. The offline styles have no glyph server (nothing may
 * be fetched), so MapLibre cannot draw `text-field`; a symbol layer shows these images with `icon-image`
 * instead. Used by the radar (callsigns) and the terminal map (gate numbers).
 *
 * @param background pill colour (ARGB); [text] text colour; [outline] 1 px ring, 0 for none
 */
class MapLabelBitmaps(
    context: Context,
    private val background: Int,
    private val text: Int,
    private val outline: Int = 0,
    textSizeSp: Float = 11f,
    bold: Boolean = true,
) {
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = density * context.resources.configuration.fontScale

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = textSizeSp * scaledDensity
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        // Tabular figures, like the rest of the app.
        fontFeatureSettings = "tnum"
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = outline
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    fun render(label: String): Bitmap {
        val padH = 5 * density
        val padV = 2.5f * density
        val metrics = textPaint.fontMetrics
        val textWidth = textPaint.measureText(label)
        val width = (textWidth + 2 * padH).toInt().coerceAtLeast(1)
        val height = (metrics.descent - metrics.ascent + 2 * padV).toInt().coerceAtLeast(1)
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val rect = RectF(0.5f * density, 0.5f * density, width - 0.5f * density, height - 0.5f * density)
        val radius = height / 2f
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        if (outline != 0) canvas.drawRoundRect(rect, radius, radius, strokePaint)
        canvas.drawText(label, padH, padV - metrics.ascent, textPaint)
        return bitmap
    }

    /** Adds the images that [style] does not have yet. [ids] maps image id to label text. */
    fun addMissing(style: Style, ids: Map<String, String>) {
        for ((id, label) in ids) {
            if (style.getImage(id) == null) style.addImage(id, render(label))
        }
    }
}
