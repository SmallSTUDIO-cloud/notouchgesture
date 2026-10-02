package com.samin.notouchgesture.gesture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.processors.ClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Camera + MediaPipe gesture recognition.
 *
 * The preview is optional. When null, the same recognizer can run from a foreground
 * service so gestures continue while another app is in the foreground.
 */
class GestureRecognizerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val mode: GestureStateMachine.GestureMode,
    private val onState: (GestureUiState) -> Unit,
    private val onAction: (GestureStateMachine.Action) -> Unit,
    private val receivePending: () -> Boolean = { false },
) {
    data class GestureUiState(
        val label: GestureStateMachine.Label = GestureStateMachine.Label.NONE,
        val confidence: Float = 0f,
        val handQuality: Float = 0f,
        val message: String = "Searching for one hand…",
    )

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PalmLink-Gesture").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val stateMachine = GestureStateMachine(mode)
    private val acceptingFrames = AtomicBoolean(false)
    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        acceptingFrames.set(true)
        try {
            setupRecognizer()
        } catch (error: Exception) {
            onState(GestureUiState(message = "Gesture engine could not start: ${error.message ?: "model error"}"))
            stop()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!started) return@addListener
            try {
                cameraProvider = providerFuture.get()
                bindCamera(cameraProvider!!)
            } catch (error: Exception) {
                onState(GestureUiState(message = "Camera could not start: ${error.message ?: "unknown error"}"))
            }
        }, mainExecutor)
    }

    fun stop() {
        if (!started) return
        started = false
        acceptingFrames.set(false)
        cameraProvider?.unbindAll()
        cameraProvider = null
        recognizer?.close()
        recognizer = null
        analysisExecutor.shutdownNow()
        stateMachine.reset()
    }

    private fun setupRecognizer() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()

        val gestureClasses = ClassifierOptions.builder()
            .setCategoryAllowlist(listOf("Open_Palm", "Closed_Fist"))
            .setScoreThreshold(0.35f)
            .setMaxResults(1)
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.45f)
            .setMinTrackingConfidence(0.45f)
            .setMinHandPresenceConfidence(0.45f)
            .setCannedGesturesClassifierOptions(gestureClasses)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> handleResult(result) }
            .setErrorListener { error -> onState(GestureUiState(message = "Gesture engine error: ${error.message}")) }
            .build()

        recognizer = GestureRecognizer.createFromOptions(context, options)
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            // 640x480 is enough for hand landmarks and materially cheaper than a full camera frame.
            .setTargetResolution(Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

        val useCases = mutableListOf<androidx.camera.core.UseCase>(analysis)
        previewView?.let { view ->
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            useCases += preview
        }

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_FRONT_CAMERA,
            *useCases.toTypedArray(),
        )
    }

    private fun analyze(image: ImageProxy) {
        if (!acceptingFrames.get()) {
            image.close()
            return
        }

        try {
            // CameraX does the YUV -> RGBA conversion for us. Avoid a second full-frame
            // rotation bitmap allocation; MediaPipe receives the rotation metadata below.
            val bitmap = image.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestamp = SystemClock.uptimeMillis()
            recognizer?.recognizeAsync(mpImage, timestamp)
            bitmap.recycle()
        } catch (error: Exception) {
            // A bad frame must never wedge ImageAnalysis. The next frame remains usable.
        } finally {
            image.close()
        }
    }

    private fun handleResult(result: GestureRecognizerResult) {
        if (!started) return
        val now = SystemClock.uptimeMillis()
        val hands = result.landmarks()
        val categories = result.gestures().firstOrNull()

        if (hands.size != 1 || categories.isNullOrEmpty()) {
            publish(GestureUiState(message = "Show one hand clearly in the frame."))
            stateMachine.update(GestureStateMachine.Label.NONE, 0f, now, receivePending())
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

        // The old quality gate demanded roughly 8% of the frame. That is too restrictive
        // on phones where the user naturally holds the hand farther away.
        val sizeQuality = ((width * height) / 0.07f).coerceIn(0f, 1f)
        val centerDistance = abs(centerX - 0.5f) + abs(centerY - 0.5f)
        val centerQuality = (1f - centerDistance / 0.75f).coerceIn(0f, 1f)
        val handQuality = (sizeQuality * 0.75f + centerQuality * 0.25f).coerceIn(0f, 1f)

        val category = categories[0]
        val confidence = category.score()
        val label = when (category.categoryName().lowercase()) {
            "open_palm", "open palm" -> GestureStateMachine.Label.OPEN_PALM
            "closed_fist", "closed fist" -> GestureStateMachine.Label.CLOSED_FIST
            else -> GestureStateMachine.Label.NONE
        }

        val accepted = handQuality >= 0.28f
        val effectiveLabel = if (accepted) label else GestureStateMachine.Label.NONE
        val action = stateMachine.update(effectiveLabel, confidence, now, receivePending())

        val message = when {
            !accepted -> "Move your hand a little closer or toward the center."
            confidence < 0.55f -> "Gesture seen. Hold it briefly."
            label == GestureStateMachine.Label.OPEN_PALM -> when (mode) {
                GestureStateMachine.GestureMode.CAPTURE,
                GestureStateMachine.GestureMode.BACKGROUND -> "Palm detected. Close your hand to capture."
                GestureStateMachine.GestureMode.SEND -> "Palm detected."
                GestureStateMachine.GestureMode.RECEIVE -> "Palm detected."
            }
            label == GestureStateMachine.Label.CLOSED_FIST -> when (mode) {
                GestureStateMachine.GestureMode.CAPTURE -> "Fist detected."
                GestureStateMachine.GestureMode.BACKGROUND -> "Fist detected. Open your hand to send."
                GestureStateMachine.GestureMode.SEND -> "Fist detected. Open your hand to send."
                GestureStateMachine.GestureMode.RECEIVE -> "Fist detected."
            }
            else -> "Show an open palm or closed fist."
        }

        publish(GestureUiState(label, confidence, handQuality, message))
        if (action != GestureStateMachine.Action.NONE && action != GestureStateMachine.Action.ARMED) {
            mainExecutor.execute { onAction(action) }
        } else if (action == GestureStateMachine.Action.ARMED) {
            mainExecutor.execute { onAction(action) }
        }
    }

    private fun publish(state: GestureUiState) {
        mainExecutor.execute { onState(state) }
    }

    companion object {
        private const val MODEL_ASSET = "gesture_recognizer.task"
    }
}
