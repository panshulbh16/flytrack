package app.flytrack.feature.importticket

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.flytrack.core.model.TrackSource
import app.flytrack.di.IoDispatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Text already in hand, or content Uris to read. The file itself is never uploaded. */
data class ExtractRequest(
    val uris: List<String> = emptyList(),
    val mimeType: String? = null,
    val text: String? = null,
)

data class ExtractedTicket(
    val text: String,
    val source: TrackSource,
    val truncated: Boolean,
)

interface TicketTextExtractor {
    suspend fun extract(request: ExtractRequest): ExtractedTicket
}

@Singleton
class AndroidTicketTextExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : TicketTextExtractor {

    override suspend fun extract(request: ExtractRequest): ExtractedTicket = withContext(io) {
        val parts = mutableListOf<String>()
        val kinds = mutableListOf<TicketMedia>()
        request.text?.takeIf { it.isNotBlank() }?.let {
            parts += it
            kinds += TicketMedia.TEXT
        }
        for (raw in request.uris) {
            val uri = Uri.parse(raw)
            val mime = context.contentResolver.getType(uri) ?: request.mimeType
            val name = uri.lastPathSegment
            when (mediaKind(mime, name)) {
                TicketMedia.PDF -> {
                    kinds += TicketMedia.PDF
                    parts += readPdf(uri)
                }
                TicketMedia.IMAGE -> {
                    kinds += TicketMedia.IMAGE
                    parts += readImage(uri)
                }
                TicketMedia.TEXT -> {
                    kinds += TicketMedia.TEXT
                    parts += readText(uri)
                }
                TicketMedia.UNKNOWN -> {
                    // A share can arrive as */*. Try a PDF, then a picture, then text.
                    val pdf = runCatching { readPdf(uri) }.getOrNull()
                    if (!pdf.isNullOrBlank()) {
                        kinds += TicketMedia.PDF
                        parts += pdf
                    } else {
                        val image = runCatching { readImage(uri) }.getOrNull()
                        if (!image.isNullOrBlank()) {
                            kinds += TicketMedia.IMAGE
                            parts += image
                        } else {
                            kinds += TicketMedia.TEXT
                            parts += readText(uri)
                        }
                    }
                }
            }
        }
        val joined = parts.joinToString("\n").trim()
        val truncated = joined.length > MAX_TICKET_CHARS
        ExtractedTicket(
            text = joined.take(MAX_TICKET_CHARS),
            source = trackSourceFor(kinds),
            truncated = truncated,
        )
    }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader().use { it.readText() }
        }.orEmpty()

    private suspend fun readImage(uri: Uri): String {
        val bitmap = decodeSampled(uri) ?: return ""
        return try {
            recognize(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun readPdf(uri: Uri): String {
        val (descriptor, temp) = openSeekable(uri)
        return try {
            PdfRenderer(descriptor).use { renderer ->
                val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                buildString {
                    for (index in 0 until pages) {
                        renderer.openPage(index).use { page ->
                            val (width, height) = scaledSize(page.width, page.height)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                Canvas(bitmap).drawColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                append(recognize(bitmap))
                                append('\n')
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            descriptor.close()
            temp?.delete()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            recognizer.close()
        }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_BITMAP_EDGE * 2 || bounds.outHeight / sample > MAX_BITMAP_EDGE * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val (width, height) = scaledSize(decoded.width, decoded.height)
        if (width == decoded.width && height == decoded.height) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }

    /** PdfRenderer needs a seekable descriptor. Copy to a cache file when the provider cannot give one. */
    private fun openSeekable(uri: Uri): Pair<ParcelFileDescriptor, File?> {
        val direct = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
        if (direct != null) return direct to null
        val temp = File.createTempFile("ticket-", ".bin", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: error("unreadable")
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY) to temp
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }
}
