package com.example.objectdetect

import android.graphics.Bitmap
//import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core. ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime. Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui. Modifier
//import androidx.compose.ui. platform.LocalContext
import androidx. lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
//import java.io.ByteArrayOutputStream
import java.util.concurrent. Executors

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onFrameAnalyzed: (Bitmap) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider. getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview. Builder().build().also {
                    it.surfaceProvider = previewView. surfaceProvider
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis. STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, ImageAnalyzer(onFrameAnalyzed))
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider. unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                } catch (exc: Exception) {
                    exc.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

private class ImageAnalyzer(
    private val onFrameAnalyzed:  (Bitmap) -> Unit
) :  ImageAnalysis.Analyzer {

    override fun analyze(imageProxy: ImageProxy) {
        // Use the built-in toBitmap() method from CameraX
        val bitmap = imageProxy. toBitmap()

        // Rotate bitmap to correct orientation
        val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees. toFloat())

        onFrameAnalyzed(rotatedBitmap)
        imageProxy.close()
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Float): Bitmap {
        if (rotationDegrees == 0f) return source

        val matrix = Matrix().apply {
            postRotate(rotationDegrees)
        }
        return Bitmap.createBitmap(source, 0, 0, source. width, source.height, matrix, true)
    }
}