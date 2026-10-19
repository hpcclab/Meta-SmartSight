package edu.unt.smartsight.metaglassestest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.min
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Ultralytics YOLO26n (COCO, w8a32) bundled in assets.
 * Calls are serialized. One detection runs at a time.
 */
internal class YoloDetector(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var interpreter: Interpreter? = null
    private var failedMessage: String? = null

    fun detect(bitmap: Bitmap): Result<Set<String>> {
        val engine = interpreter() ?: return Result.failure(
            IllegalStateException(failedMessage ?: "Object detection model failed to load."),
        )
        return try {
            Result.success(run(engine, bitmap))
        } catch (error: Exception) {
            Log.e(TAG, "YOLO inference failed", error)
            Result.failure(error)
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun interpreter(): Interpreter? {
        interpreter?.let { return it }
        if (failedMessage != null) {
            return null
        }
        return try {
            val options = Interpreter.Options().apply {
                setNumThreads(INFERENCE_THREADS)
                setUseXNNPACK(true)
            }
            Interpreter(loadModel(), options).also { created ->
                interpreter = created
                val input = created.getInputTensor(0)
                val output = created.getOutputTensor(0)
                Log.i(
                    TAG,
                    "YOLO input ${input.shape().contentToString()} ${input.dataType()} " +
                        "output ${output.shape().contentToString()} ${output.dataType()}",
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "YOLO model failed to load", error)
            failedMessage = "Object detection model failed to load."
            null
        }
    }

    private fun loadModel(): ByteBuffer {
        appContext.assets.openFd(MODEL_ASSET).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    }

    private fun run(engine: Interpreter, bitmap: Bitmap): Set<String> {
        val inputTensor = engine.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val channelsFirst = inputShape.size == 4 && inputShape[1] == 3 && inputShape[3] != 3
        val height = if (channelsFirst) inputShape[2] else inputShape[1]
        val width = if (channelsFirst) inputShape[3] else inputShape[2]
        val letterboxed = letterbox(bitmap, width, height)
        try {
            val input = imageToBuffer(letterboxed, channelsFirst, inputTensor.dataType())
            val outputTensor = engine.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            require(outputShape.size == 3) {
                "Unexpected YOLO output shape ${outputShape.contentToString()}"
            }
            val flat = FloatArray(outputShape[1] * outputShape[2])
            when (outputTensor.dataType()) {
                DataType.FLOAT32 -> {
                    val nested = Array(outputShape[0]) {
                        Array(outputShape[1]) { FloatArray(outputShape[2]) }
                    }
                    engine.run(input, nested)
                    var cursor = 0
                    for (row in nested[0]) {
                        row.copyInto(flat, cursor)
                        cursor += row.size
                    }
                }
                DataType.UINT8, DataType.INT8 -> {
                    val bytes = ByteBuffer.allocateDirect(flat.size).order(ByteOrder.nativeOrder())
                    engine.run(input, bytes)
                    bytes.rewind()
                    val scale = outputTensor.quantizationParams().scale
                    val zero = outputTensor.quantizationParams().zeroPoint
                    val signed = outputTensor.dataType() == DataType.INT8
                    for (index in flat.indices) {
                        val quantized = if (signed) bytes.get().toInt() else bytes.get().toInt() and 0xFF
                        flat[index] = (quantized - zero) * scale
                    }
                }
                else -> error("Unsupported YOLO output type ${outputTensor.dataType()}")
            }
            return YoloPostprocess.labels(
                flat = flat,
                dim1 = outputShape[1],
                dim2 = outputShape[2],
                classNames = COCO_LABELS,
                confidenceThreshold = CONFIDENCE_THRESHOLD,
            )
        } finally {
            if (letterboxed !== bitmap) {
                letterboxed.recycle()
            }
        }
    }

    private fun imageToBuffer(bitmap: Bitmap, channelsFirst: Boolean, dataType: DataType): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return when (dataType) {
            DataType.FLOAT32 -> floatBuffer(pixels, width, height, channelsFirst)
            DataType.UINT8 -> byteBuffer(pixels, width, height, channelsFirst)
            else -> error("Unsupported YOLO input type $dataType")
        }
    }

    private fun floatBuffer(
        pixels: IntArray,
        width: Int,
        height: Int,
        channelsFirst: Boolean,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 3 * FLOAT_BYTES).order(ByteOrder.nativeOrder())
        if (!channelsFirst) {
            for (pixel in pixels) {
                buffer.putFloat(red(pixel) / 255f)
                buffer.putFloat(green(pixel) / 255f)
                buffer.putFloat(blue(pixel) / 255f)
            }
        } else {
            writePlanar(buffer, pixels, ::red)
            writePlanar(buffer, pixels, ::green)
            writePlanar(buffer, pixels, ::blue)
        }
        buffer.rewind()
        return buffer
    }

    private fun writePlanar(buffer: ByteBuffer, pixels: IntArray, channel: (Int) -> Int) {
        for (pixel in pixels) {
            buffer.putFloat(channel(pixel) / 255f)
        }
    }

    private fun byteBuffer(
        pixels: IntArray,
        width: Int,
        height: Int,
        channelsFirst: Boolean,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.nativeOrder())
        if (!channelsFirst) {
            for (pixel in pixels) {
                buffer.put(red(pixel).toByte())
                buffer.put(green(pixel).toByte())
                buffer.put(blue(pixel).toByte())
            }
        } else {
            for (pixel in pixels) buffer.put(red(pixel).toByte())
            for (pixel in pixels) buffer.put(green(pixel).toByte())
            for (pixel in pixels) buffer.put(blue(pixel).toByte())
        }
        buffer.rewind()
        return buffer
    }

    private fun letterbox(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val scale = min(
            targetWidth.toFloat() / source.width.coerceAtLeast(1),
            targetHeight.toFloat() / source.height.coerceAtLeast(1),
        )
        val scaledWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(
            scaled,
            (targetWidth - scaledWidth) / 2f,
            (targetHeight - scaledHeight) / 2f,
            null,
        )
        if (scaled !== source) {
            scaled.recycle()
        }
        return output
    }

    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF

    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF

    private fun blue(pixel: Int): Int = pixel and 0xFF

    private companion object {
        const val TAG = "MetaGlassesTest"
        const val MODEL_ASSET = "yolo26n_w8a32.tflite"
        const val CONFIDENCE_THRESHOLD = 0.45f
        const val INFERENCE_THREADS = 4
        const val FLOAT_BYTES = 4
    }
}
