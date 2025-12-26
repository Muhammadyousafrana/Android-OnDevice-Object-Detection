package com.example.objectdetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google. ai.edge.litert. Accelerator
import kotlin.math. max
import kotlin.math.min
import androidx.core.graphics.scale

data class Detection(
    val bbox: RectF,
    val label: String,
    val confidence: Float,
    val classId: Int
)

class ModelHelper(private val context: Context) {
    private var model: CompiledModel? = null

    companion object {
        private const val MODEL_NAME = "yolo11n_float32.tflite"
        private const val INPUT_SIZE = 640
        private const val NUM_CLASSES = 80
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.25f
        private const val CAMERA_CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f

        private val CLASS_NAMES = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )

        private val COLORS = listOf(
            Color.RED, Color.GREEN, Color.BLUE, Color. YELLOW, Color.CYAN,
            Color.MAGENTA, Color.rgb(255, 165, 0), Color.rgb(128, 0, 128),
            Color.rgb(255, 192, 203), Color.rgb(0, 128, 128)
        )
    }

    fun initModel() {
        try {
            model = CompiledModel.create(
                context.assets,
                MODEL_NAME,
                CompiledModel.Options(Accelerator.CPU)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Failed to load model: ${e.message}")
        }
    }

    fun detectObjects(bitmap: Bitmap, isLiveCamera: Boolean = false): Pair<Bitmap, List<Detection>> {
        val model = this.model ?: throw IllegalStateException("Model not initialized")

        val threshold = if (isLiveCamera) CAMERA_CONFIDENCE_THRESHOLD else DEFAULT_CONFIDENCE_THRESHOLD

        val resizedBitmap = bitmap.scale(INPUT_SIZE, INPUT_SIZE)
        val inputArray = preprocessImage(resizedBitmap)

        // Recycle resized bitmap after preprocessing if it's from camera
        if (isLiveCamera && resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }

        val inputBuffers = model.createInputBuffers()
        val outputBuffers = model.createOutputBuffers()

        inputBuffers[0].writeFloat(inputArray)
        model.run(inputBuffers, outputBuffers)

        val output = outputBuffers[0].readFloat()
        val detections = postprocess(output, bitmap.width, bitmap.height, threshold, isLiveCamera)

        // Don't draw boxes for live camera (we draw on Canvas overlay instead)
        val resultBitmap = if (isLiveCamera) {
            bitmap
        } else {
            drawBoundingBoxes(bitmap, detections)
        }

        return Pair(resultBitmap, detections)
    }
    private fun preprocessImage(bitmap: Bitmap): FloatArray {
        val inputArray = FloatArray(1 * INPUT_SIZE * INPUT_SIZE * 3)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var index = 0
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = pixels[y * INPUT_SIZE + x]
                inputArray[index++] = ((pixel shr 16) and 0xFF) / 255.0f
                inputArray[index++] = ((pixel shr 8) and 0xFF) / 255.0f
                inputArray[index++] = (pixel and 0xFF) / 255.0f
            }
        }
        return inputArray
    }

    private fun postprocess(
        output: FloatArray,
        originalWidth: Int,
        originalHeight: Int,
        threshold: Float,
        isLiveCamera:  Boolean
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val numDetections = 8400

        for (i in 0 until numDetections) {
            val x = output[i]
            val y = output[numDetections + i]
            val w = output[2 * numDetections + i]
            val h = output[3 * numDetections + i]

            var maxConf = 0f
            var maxClassId = -1

            for (c in 0 until NUM_CLASSES) {
                val conf = output[(4 + c) * numDetections + i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClassId = c
                }
            }

            if (maxConf > threshold) {
                val centerX = x * originalWidth
                val centerY = y * originalHeight
                val width = w * originalWidth
                val height = h * originalHeight

                val left = centerX - width / 2f
                val top = centerY - height / 2f
                val right = centerX + width / 2f
                val bottom = centerY + height / 2f

                val bbox = RectF(
                    max(0f, left),
                    max(0f, top),
                    min(originalWidth. toFloat(), right),
                    min(originalHeight.toFloat(), bottom)
                )

                val label = if (maxClassId >= 0 && maxClassId < CLASS_NAMES.size) {
                    CLASS_NAMES[maxClassId]
                } else {
                    "Unknown"
                }

                detections.add(Detection(bbox, label, maxConf, maxClassId))
            }
        }

        val nmsResult = applyNMS(detections)

        if (isLiveCamera) {
            Log. d("ModelHelper", "Camera:  ${nmsResult.size} detections")
        } else {
            Log. d("ModelHelper", "Image: ${nmsResult.size} detections")
        }

        return nmsResult
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        val sortedDetections = detections.sortedByDescending { it.confidence }
        val selectedDetections = mutableListOf<Detection>()

        for (detection in sortedDetections) {
            var shouldSelect = true

            for (selectedDetection in selectedDetections) {
                if (detection.classId == selectedDetection.classId) {
                    val iou = calculateIoU(detection. bbox, selectedDetection.bbox)
                    if (iou > IOU_THRESHOLD) {
                        shouldSelect = false
                        break
                    }
                }
            }

            if (shouldSelect) {
                selectedDetections.add(detection)
            }
        }

        return selectedDetections
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = max(box1.left, box2.left)
        val intersectionTop = max(box1.top, box2.top)
        val intersectionRight = min(box1.right, box2.right)
        val intersectionBottom = min(box1.bottom, box2.bottom)

        val intersectionWidth = max(0f, intersectionRight - intersectionLeft)
        val intersectionHeight = max(0f, intersectionBottom - intersectionTop)
        val intersectionArea = intersectionWidth * intersectionHeight

        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }

    private fun drawBoundingBoxes(bitmap: Bitmap, detections: List<Detection>): Bitmap {
        val mutableBitmap = bitmap. copy(Bitmap.Config. ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        val strokeWidth = max(mutableBitmap.width, mutableBitmap.height) / 200f
        val textSize = max(mutableBitmap.width, mutableBitmap.height) / 25f

        val boxPaint = Paint().apply {
            style = Paint.Style. STROKE
            this.strokeWidth = strokeWidth
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color. WHITE
            this.textSize = textSize
            isAntiAlias = true
            style = Paint.Style.FILL
            isFakeBoldText = true
        }

        val textBackgroundPaint = Paint().apply {
            style = Paint.Style. FILL
            isAntiAlias = true
        }

        detections.forEachIndexed { index, detection ->
            val color = COLORS[index % COLORS.size]
            boxPaint.color = color
            textBackgroundPaint.color = color

            canvas.drawRect(detection. bbox, boxPaint)

            val confidencePercent = (detection.confidence * 100).toInt()
            val label = "${detection.label} $confidencePercent%"

            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)

            val textX = detection. bbox.left
            val textY = max(detection.bbox.top - 10, textBounds.height().toFloat() + 10)

            val padding = 10f

            canvas.drawRect(
                textX - padding,
                textY - textBounds.height() - padding,
                textX + textBounds.width() + padding * 2,
                textY + padding,
                textBackgroundPaint
            )

            canvas. drawText(label, textX + padding / 2, textY - padding / 2, textPaint)
        }

        return mutableBitmap
    }

    fun close() {
        model?.close()
    }
}