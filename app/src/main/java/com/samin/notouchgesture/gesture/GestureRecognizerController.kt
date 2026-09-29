package com.samin.notouchgesture.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GestureRecognizerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val mode: GestureStateMachine.GestureMode,
    private val onState: (GestureUiState) -> Unit,
    private val onAction: (GestureStateMachine.Action) -> Unit,
) {
    data class GestureUiState(
        val label: GestureStateMachine.Label = GestureStateMachine.Label.NONE,
        val confidence: Float = 0f,
        val handQuality: Float = 0f,
        val message: String = "Searching for one hand…",
    )

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stateMachine = GestureStateMachine(mode)
    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        setupRecognizer()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCamera(cameraProvider!!)
            } catch (error: Exception) {
                onState(GestureUiState(message = "Camera could not start."))
            }
        }, mainExecutor)
    }

    fun stop() {
        started = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        recognizer?.close()
        recognizer = null
        analysisExecutor.shutdownNow()
        analysisExecutor.awaitTermination(200, TimeUnit.MILLISECONDS)
        stateMachine.reset()
    }

    private fun setupRecognizer() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMinHandDetectionConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { error -> onState(GestureUiState(message = "Gesture engine error: ${error.message}")) }
            .build()

        recognizer = GestureRecognizer.createFromOptions(context, options)
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setImageQueueDepth(1)
            .build()

        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            preview,
            analysis,
        )
    }

    private fun analyze(image: ImageProxy) {
        val bitmap = image.toBitmap()
        val rotated = rotate(bitmap, image.imageInfo.rotationDegrees)
        val mpImage = BitmapImageBuilder(rotated).build()
        try {
            recognizer?.recognizeAsync(mpImage, SystemClock.uptimeMillis())
        } catch (_: Exception) {
            // The analyzer must always close the ImageProxy even if inference rejects a frame.
        } finally {
            image.close()
        }
    }

    private fun handleResult(result: GestureRecognizerResult) {
        val now = SystemClock.uptimeMillis()
        val hands = result.landmarks()
        if (hands.size != 1 || result.gestures().isEmpty() || result.gestures()[0].isEmpty()) {
            publish(GestureUiState(message = "Show one hand clearly in the frame."), now)
            stateMachine.update(GestureStateMachine.Label.NONE, 0f, now)
            return
        }

        val landmarks = hands[0]
        val minX = landmarks.minOf { it.x() }
        val maxX = landmarks.maxOf { it.x() }
        val minY = landmarks.minOf { it.y() }
        val maxY = landmarks.maxOf { it.y() }
        val width = maxX - minX
        val height = maxY - minY
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val sizeQuality = ((width * height) / 0.18f).coerceIn(0f, 1f)
        val centerQuality = (1f - (kotlin.math.abs(centerX - 0.5f) + kotlin.math.abs(centerY - 0.5f))).coerceIn(0f, 1f)
        val handQuality = (sizeQuality * 0.7f + centerQuality * 0.3f).coerceIn(0f, 1f)

        val category = result.gestures()[0][0]
        val confidence = category.score()
        val label = when (category.categoryName().lowercase()) {
            "open_palm", "open palm" -> GestureStateMachine.Label.OPEN_PALM
            "closed_fist", "closed fist" -> GestureStateMachine.Label.CLOSED_FIST
            else -> GestureStateMachine.Label.NONE
        }

        val accepted = handQuality >= 0.45f
        val effectiveLabel = if (accepted) label else GestureStateMachine.Label.NONE
        val action = stateMachine.update(effectiveLabel, confidence, now)

        val message = when {
            !accepted -> "Move your hand closer to the center."
            confidence < 0.72f -> "Hold the gesture steady."
            label == GestureStateMachine.Label.OPEN_PALM -> when (mode) {
                GestureStateMachine.GestureMode.CAPTURE -> "Palm detected. Close your hand to capture."
                GestureStateMachine.GestureMode.SEND -> "Palm detected."
                GestureStateMachine.GestureMode.RECEIVE -> "Palm detected."
            }
            label == GestureStateMachine.Label.CLOSED_FIST -> when (mode) {
                GestureStateMachine.GestureMode.CAPTURE -> "Fist detected."
                GestureStateMachine.GestureMode.SEND -> "Fist detected. Open your hand to send."
                GestureStateMachine.GestureMode.RECEIVE -> "Fist detected."
            }
            else -> "Show an open palm or closed fist."
        }

        publish(GestureUiState(label, confidence, handQuality, message), now)
        if (action != GestureStateMachine.Action.NONE && action != GestureStateMachine.Action.ARMED) {
            mainExecutor.execute { onAction(action) }
        }
    }

    private fun publish(state: GestureUiState, @Suppress("UNUSED_PARAMETER") now: Long) {
        mainExecutor.execute { onState(state) }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
