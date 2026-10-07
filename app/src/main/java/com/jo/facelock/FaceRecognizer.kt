package com.jo.facelock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

/**
 * FaceNet: input 1x160x160x3 float32, output 1x512 float32 (L2-normalized here).
 * Pipeline: ML Kit finds the largest face box -> crop -> 160x160 -> embed.
 */
class FaceRecognizer(ctx: Context) {
    companion object {
        private const val TAG = "FaceLock/Face"
        private const val SIZE = 160
        private const val EMB = 512
    }

    private val interpreter: Interpreter
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.15f)
            .build()
    )

    init {
        val afd = ctx.assets.openFd("facenet.tflite")
        val channel = FileInputStream(afd.fileDescriptor).channel
        val model = channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        val opts = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(model, opts)
    }

    /** detect -> crop -> embed. Returns normalized 512-d vector, or null if no face. */
    fun process(bitmap: Bitmap): FloatArray? {
        val box = detectLargestFace(bitmap) ?: return null
        val face = crop(bitmap, box) ?: return null
        val scaled = Bitmap.createScaledBitmap(face, SIZE, SIZE, true)
        return embed(scaled)
    }

    private fun detectLargestFace(bitmap: Bitmap): Rect? {
        return try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val faces = Tasks.await(detector.process(input))
            faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }?.boundingBox
        } catch (e: Exception) {
            Log.e(TAG, "detect failed: ${e.message}")
            null
        }
    }

    private fun crop(src: Bitmap, box: Rect): Bitmap? {
        // expand box ~20% then clamp
        val mx = (box.width() * 0.2f).toInt()
        val my = (box.height() * 0.2f).toInt()
        val l = (box.left - mx).coerceAtLeast(0)
        val t = (box.top - my).coerceAtLeast(0)
        val r = (box.right + mx).coerceAtMost(src.width)
        val b = (box.bottom + my).coerceAtMost(src.height)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(src, l, t, w, h)
    }

    private fun embed(bmp: Bitmap): FloatArray {
        val n = SIZE * SIZE
        val px = IntArray(n)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)

        // per-image standardization (FaceNet prewhitening)
        var sum = 0f
        var sum2 = 0f
        val r = FloatArray(n); val g = FloatArray(n); val bl = FloatArray(n)
        for (i in 0 until n) {
            val p = px[i]
            val rr = ((p shr 16) and 0xFF).toFloat()
            val gg = ((p shr 8) and 0xFF).toFloat()
            val bb = (p and 0xFF).toFloat()
            r[i] = rr; g[i] = gg; bl[i] = bb
            sum += rr + gg + bb
            sum2 += rr * rr + gg * gg + bb * bb
        }
        val total = (n * 3).toFloat()
        val mean = sum / total
        val variance = (sum2 / total) - mean * mean
        val std = max(sqrt(max(variance, 0f)), 1f / sqrt(total))

        val inBuf = ByteBuffer.allocateDirect(1 * SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) {
            inBuf.putFloat((r[i] - mean) / std)
            inBuf.putFloat((g[i] - mean) / std)
            inBuf.putFloat((bl[i] - mean) / std)
        }
        inBuf.rewind()

        val out = Array(1) { FloatArray(EMB) }
        interpreter.run(inBuf, out)

        // L2 normalize so cosine == dot product
        val v = out[0]
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    fun close() {
        try { interpreter.close() } catch (_: Exception) {}
        try { detector.close() } catch (_: Exception) {}
    }
}

/** cosine similarity of two L2-normalized vectors (== dot product) */
fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return -1f
    var d = 0f
    for (i in a.indices) d += a[i] * b[i]
    return d
}
