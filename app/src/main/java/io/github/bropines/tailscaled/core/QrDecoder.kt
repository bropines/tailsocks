package io.github.bropines.tailscaled.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR codes read with ZXing's core (Apache-2.0, plain Java): a camera frame's
 * luminance or a picture's, on this device, nothing kept. QR only — the reader
 * is ZXing's QR reader itself, not the multi-format one, so the other formats'
 * code is never reached and R8 leaves it out.
 */
object QrDecoder {
    /**
     * Pictures are decoded no larger than this on their long side: a phone
     * screenshot whole, a 12–50 MP photo halved or quartered, a few MB of
     * memory either way.
     */
    private const val MAX_SIDE = 2560

    /**
     * UTF-8 for byte segments without an ECI, which is how this app's codes
     * (QrCode.kt) and nearly every other generator write text; left to
     * guess, ZXing takes short UTF-8 for Shift_JIS.
     */
    private val FAST = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8")
    private val HARD = FAST + (DecodeHintType.TRY_HARDER to true)

    /**
     * The text of the QR code in [source], or null. [tryHarder] is for a still
     * picture: a slower search, and a second binarizer for flat, low-contrast
     * images. A camera frame takes the fast path; the next frame is the retry.
     */
    fun decode(source: LuminanceSource, tryHarder: Boolean): String? {
        val reader = QRCodeReader()
        val binarizers = if (tryHarder) listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source)) else listOf(HybridBinarizer(source))
        for (binarizer in binarizers) {
            try {
                return reader.decode(BinaryBitmap(binarizer), if (tryHarder) HARD else FAST).text
            } catch (_: ReaderException) {
                // Not found, or found and unreadable: the next binarizer, or null.
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /**
     * The QR code in the picture at [uri], dark on light or light on dark, or
     * null when there is none or the picture cannot be read. Blocking: call it
     * off the main thread.
     */
    fun decodeImage(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } }.getOrNull()
            ?: return null
        try {
            decodeBitmap(bitmap)?.let { return it }
            // A photo of a screen: a smaller, smoothed copy averages out the
            // moiré and the noise that defeat the full-size one.
            if (minOf(bitmap.width, bitmap.height) >= 400) {
                val half = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                try {
                    return decodeBitmap(half)
                } finally {
                    if (half !== bitmap) half.recycle()
                }
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBitmap(bitmap: Bitmap): String? {
        val source = luminance(bitmap)
        return decode(source, tryHarder = true) ?: decode(source.invert(), tryHarder = true)
    }

    /**
     * [bitmap] as luminance, one byte a pixel, with transparency laid over
     * white: a code saved as a PNG on a transparent background has black
     * "transparent" pixels and would read as one dark square.
     */
    private fun luminance(bitmap: Bitmap): LuminanceSource {
        val w = bitmap.width
        val h = bitmap.height
        val row = IntArray(w)
        val lum = ByteArray(w * h)
        for (y in 0 until h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val a = c ushr 24
                val l = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                lum[y * w + x] = ((l * a + 255 * (255 - a)) / 255).toByte()
            }
        }
        return PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false)
    }
}

/**
 * Reads camera frames until one holds a QR code, then hands its text to
 * [onFound] — once, on the analysis thread — and skips every frame after.
 * Every other frame is read inverted, so a light-on-dark code (one drawn in a
 * dark theme's colours) is found as well.
 */
class QrFrameAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val found = AtomicBoolean(false)
    private var buffer = ByteArray(0)
    private var frame = 0L

    override fun analyze(image: ImageProxy) {
        image.use {
            if (found.get()) return
            // YUV_420_888: the Y plane is the luminance, one byte a pixel, rows
            // rowStride apart — wider than the image on many devices.
            val plane = it.planes[0]
            val stride = plane.rowStride
            val pixels = plane.buffer.apply { rewind() }
            val size = minOf(pixels.remaining(), stride * it.height)
            if (buffer.size < stride * it.height) buffer = ByteArray(stride * it.height)
            pixels.get(buffer, 0, size)
            val source = PlanarYUVLuminanceSource(buffer, stride, it.height, 0, 0, it.width, it.height, false)
            val text = QrDecoder.decode(if (frame++ % 2 == 0L) source else source.invert(), tryHarder = false)
            if (text != null && found.compareAndSet(false, true)) onFound(text)
        }
    }
}
