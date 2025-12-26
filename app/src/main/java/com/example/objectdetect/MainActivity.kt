package com.example.objectdetect

import androidx.compose.runtime.DisposableEffect
import android.util.Log
import androidx.compose.ui. unit.IntSize
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose. ui.graphics.nativeCanvas
import androidx.compose. ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui. graphics.drawscope.Stroke
import android.graphics. Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os. Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose. setContent
import androidx.activity.enableEdgeToEdge
import androidx. activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation. Image
import androidx.compose.foundation. layout.*
import androidx.compose.foundation. lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx. compose.ui.platform.LocalContext
import androidx.compose.ui. unit.dp
import com.example.objectdetect.ui.theme.ObjectDetectTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google. accompanist.permissions.rememberPermissionState
import kotlinx.coroutines. Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io. InputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var modelHelper: ModelHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize model
        modelHelper = ModelHelper(this)
        modelHelper.initModel()

        enableEdgeToEdge()
        setContent {
            ObjectDetectTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ObjectDetectionScreen(
                        modelHelper = modelHelper,
                        modifier = Modifier. padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        modelHelper.close()
    }
}

enum class DetectionMode {
    IMAGE, CAMERA
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ObjectDetectionScreen(
    modelHelper: ModelHelper,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var detectionMode by remember { mutableStateOf(DetectionMode.IMAGE) }
    var processedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cameraPermissionState = rememberPermissionState(android. Manifest.permission.CAMERA)

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isProcessing = true
                try {
                    val bitmap = uriToBitmap(context.contentResolver.openInputStream(uri))

                    // Run object detection (not live camera)
                    withContext(Dispatchers.Default) {
                        val (resultBitmap, resultDetections) = modelHelper.detectObjects(bitmap, isLiveCamera = false)
                        processedImageBitmap = resultBitmap
                        detections = resultDetections
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "YOLO Object Detection",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Mode selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement. spacedBy(8.dp)
        ) {
            FilterChip(
                selected = detectionMode == DetectionMode.IMAGE,
                onClick = {
                    detectionMode = DetectionMode.IMAGE
                    processedImageBitmap = null
                    detections = emptyList()
                },
                label = { Text("Image") },
                modifier = Modifier.weight(1f)
            )

            FilterChip(
                selected = detectionMode == DetectionMode.CAMERA,
                onClick = {
                    if (cameraPermissionState.status. isGranted) {
                        detectionMode = DetectionMode. CAMERA
                        processedImageBitmap = null
                        detections = emptyList()
                    } else {
                        cameraPermissionState.launchPermissionRequest()
                    }
                },
                label = { Text("Camera") },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier. height(16.dp))

        when (detectionMode) {
            DetectionMode.IMAGE -> {
                ImageDetectionMode(
                    imagePickerLauncher = { imagePickerLauncher. launch("image/*") },
                    processedImageBitmap = processedImageBitmap,
                    detections = detections,
                    isProcessing = isProcessing
                )
            }
            DetectionMode.CAMERA -> {
                if (cameraPermissionState.status.isGranted) {
                    CameraDetectionMode(
                        modelHelper = modelHelper,
                        onDetectionsUpdate = { bitmap, newDetections ->
                            processedImageBitmap = bitmap
                            detections = newDetections
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier. fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Camera permission is required")
                        Spacer(modifier = Modifier. height(8.dp))
                        Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                            Text("Grant Permission")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageDetectionMode(
    imagePickerLauncher: () -> Unit,
    processedImageBitmap: Bitmap?,
    detections: List<Detection>,
    isProcessing: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = imagePickerLauncher,
            modifier = Modifier.fillMaxWidth(),
            enabled = ! isProcessing
        ) {
            Text(if (isProcessing) "Processing..." else "Select Image")
        }

        Spacer(modifier = Modifier. height(16.dp))

        if (isProcessing) {
            CircularProgressIndicator()
        }

        processedImageBitmap?.let { bitmap ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp)
            ) {
                Image(
                    bitmap = bitmap. asImageBitmap(),
                    contentDescription = "Detected Objects",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (detections.isNotEmpty()) {
            DetectionsList(detections = detections)
        }
    }
}

@Composable
fun CameraDetectionMode(
    modelHelper: ModelHelper,
    onDetectionsUpdate: (Bitmap, List<Detection>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var currentDetections by remember { mutableStateOf<List<Detection>>(emptyList()) }
    var lastFrameSize by remember { mutableStateOf<IntSize? >(null) }
    var frameCount by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            // Cleanup when leaving camera mode
            isProcessing = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Camera preview in background
        CameraPreview(
            modifier = Modifier. fillMaxSize(),
            onFrameAnalyzed = { bitmap ->
                // Skip frames - only process every 5th frame
                frameCount++
                if (! isProcessing && frameCount % 5 == 0) {
                    scope.launch(Dispatchers.Default) {
                        isProcessing = true
                        try {
                            lastFrameSize = IntSize(bitmap.width, bitmap. height)
                            val (resultBitmap, detections) = modelHelper.detectObjects(bitmap, isLiveCamera = true)
                            withContext(Dispatchers.Main) {
                                currentDetections = detections
                            }
                            onDetectionsUpdate(resultBitmap, detections)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Log.e("CameraDetection", "Error processing frame", e)
                        } finally {
                            isProcessing = false
                        }
                    }
                } else {
                    // Recycle bitmap if not processing
                    bitmap.recycle()
                }
            }
        )

        // Canvas overlay for bounding boxes
        Canvas(
            modifier = Modifier. fillMaxSize()
        ) {
            if (lastFrameSize != null && currentDetections.isNotEmpty()) {
                val scaleX = size.width / lastFrameSize!! .width
                val scaleY = size.height / lastFrameSize!!.height

                currentDetections.forEachIndexed { index, detection ->
                    val scaledLeft = detection.bbox.left * scaleX
                    val scaledTop = detection. bbox.top * scaleY
                    val scaledRight = detection.bbox.right * scaleX
                    val scaledBottom = detection.bbox.bottom * scaleY

                    val boxWidth = scaledRight - scaledLeft
                    val boxHeight = scaledBottom - scaledTop

                    val colors = listOf(
                        androidx.compose.ui.graphics.Color.Red,
                        androidx.compose. ui.graphics.Color.Green,
                        androidx.compose.ui.graphics.Color.Blue,
                        androidx.compose.ui. graphics.Color.Yellow,
                        androidx.compose.ui.graphics.Color.Cyan,
                        androidx.compose.ui.graphics.Color.Magenta
                    )
                    val boxColor = colors[index % colors. size]

                    drawRect(
                        color = boxColor,
                        topLeft = Offset(scaledLeft, scaledTop),
                        size = Size(boxWidth, boxHeight),
                        style = Stroke(width = 8f)
                    )

                    val label = "${detection.label} ${(detection.confidence * 100).toInt()}%"

                    val textPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 50f
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(label, 0, label.length, textBounds)

                    val textWidth = textBounds.width().toFloat()
                    val textHeight = textBounds.height().toFloat()

                    val labelTop = if (scaledTop - textHeight - 20f > 0) {
                        scaledTop - textHeight - 20f
                    } else {
                        scaledBottom + 10f
                    }

                    drawRect(
                        color = boxColor,
                        topLeft = Offset(scaledLeft, labelTop),
                        size = Size(textWidth + 20f, textHeight + 20f)
                    )

                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            label,
                            scaledLeft + 10f,
                            labelTop + textHeight + 5f,
                            textPaint
                        )
                    }
                }
            }
        }

        // Overlay with detection info at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            if (currentDetections.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface. copy(alpha = 0.9f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Detected:  ${currentDetections.size} objects",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(
                            modifier = Modifier. heightIn(max = 150.dp)
                        ) {
                            items(currentDetections. take(3)) { detection ->
                                Text(
                                    text = "${detection.label} (${(detection.confidence * 100).toInt()}%)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun DetectionsList(detections: List<Detection>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Detected Objects:  ${detections.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                . fillMaxWidth()
                .heightIn(max = 200.dp)
        ) {
            items(detections) { detection ->
                DetectionItem(detection)
            }
        }
    }
}

@Composable
fun DetectionItem(detection: Detection) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = detection.label,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = String.format(Locale.US, "Confidence: %.2f%%", detection.confidence * 100),
                style = MaterialTheme.typography. bodyMedium
            )
            Text(
                text = "Location: (${detection.bbox. left. toInt()}, ${detection.bbox.top.toInt()}) - (${detection.bbox.right. toInt()}, ${detection.bbox.bottom.toInt()})",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun uriToBitmap(inputStream: InputStream?): Bitmap {
    return BitmapFactory. decodeStream(inputStream)
}