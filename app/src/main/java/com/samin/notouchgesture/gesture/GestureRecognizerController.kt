package com.samin.notouchgesture.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.components.processors.ClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Camera + MediaPipe gesture recognition.
 *
 * The controller owns the process-wide CameraX session. This prevents a foreground
 * Activity controller and the background service controller from unbinding each other.
 */
class GestureRecognizerController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView?,
    private val mode: GestureStateMachine.GestureMode,
    private val onState: (GestureUiState) -> Unit,
    private val onAction: (GestureStateMachine.Action) -> Unit,
    private val receivePending: () -> Boolean = { false },
    private val onReady: () -> Unit = {},
    private val onFatalError: (String) -> Unit = {},
) {
    data class GestureUiState(
        val label: GestureStateMachine.Label = GestureStateMachine.Label.NONE,
        val confidence: Float? = null,
        val handQuality: Float = 0f,
        val message: String = "Searching for one hand…",
    )

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PalmLink-Gesture").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val stateMachine = GestureStateMachine(mode)
    private val acceptingFrames = AtomicBoolean(false)

    @Volatile
    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var previewUseCase: Preview? = null
    private var cameraStateData: LiveData<CameraState>? = null
    private var cameraStateObserver: Observer<CameraState>? = null
    private var started = false

    /** True between a successful start() and stop(); lets owners detect a controller that died. */
    val isRunning: Boolean get() = started

    fun start() {
        synchronized(REGISTRY_LOCK) {
            if (started) return
            activeController?.takeUnless { it === this }?.stopInternal(unbindCamera = true)
            activeController = this
            started = true
            acceptingFrames.set(true)
        }

        try {
            setupRecognizer()
        } catch (error: Throwable) {
            // Includes LinkageError (e.g. a native MediaPipe library that failed to load).
            if (error is VirtualMachineError) throw error
            Log.e(TAG, "MediaPipe recognizer setup failed", error)
            val message = "Gesture engine could not start: ${error.message ?: "model error"}"
            publish(GestureUiState(message = message))
            mainExecutor.execute { safely { onFatalError(message) } }
            stop()
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!started) return@addListener
            try {
                cameraProvider = providerFuture.get()
                bindCamera(cameraProvider!!)
                publish(GestureUiState(message = "Camera ready. Show one hand clearly in the frame."))
                onReady()
            } catch (error: Throwable) {
                if (error is VirtualMachineError) throw error
                Log.e(TAG, "CameraX could not start", error)
                val message = "Camera could not start: ${error.message ?: error.javaClass.simpleName}"
                publish(GestureUiState(message = message))
                safely { onFatalError(message) }
                stop()
            }
        }, mainExecutor)
    }

    fun stop() {
        val ownsCamera = synchronized(REGISTRY_LOCK) {
            if (activeController === this) {
                activeController = null
                true
            } else {
                false
            }
        }
        stopInternal(unbindCamera = ownsCamera)
    }

    private fun stopInternal(unbindCamera: Boolean, onStopped: () -> Unit = {}) {
        if (!started) return
        started = false
        acceptingFrames.set(false)

        removeCameraStateObserver()
        val provider = cameraProvider
        if (unbindCamera) {
            runCatching {
                analysisUseCase?.let { provider?.unbind(it) }
                previewUseCase?.let { provider?.unbind(it) }
                if (analysisUseCase == null && previewUseCase == null) provider?.unbindAll()
            }
        }
        cameraProvider = null
        analysisUseCase = null
        previewUseCase = null

        // Never close MediaPipe while an ImageAnalysis callback may still be inside
        // recognize(). Let the single analysis thread finish the current frame first, then
        // close the native recognizer. This is especially important when the foreground UI
        // hands camera ownership to the background service.
        val recognizerToClose = recognizer
        recognizer = null
        if (recognizerToClose != null) {
            val closeTask = Runnable {
                runCatching { recognizerToClose.close() }
                mainExecutor.execute { onStopped() }
            }
            runCatching {
                // The executor is single-threaded, so this close runs only after any
                // in-flight recognize() call and any already-queued analyzer callback.
                analysisExecutor.execute(closeTask)
            }.onFailure {
                runCatching { recognizerToClose.close() }
                mainExecutor.execute { onStopped() }
            }
        } else {
            mainExecutor.execute { onStopped() }
        }
        analysisExecutor.shutdown()
        stateMachine.reset()
    }

    private fun setupRecognizer() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()

        val gestureClasses = ClassifierOptions.builder()
            .setCategoryAllowlist(listOf("Open_Palm", "Closed_Fist"))
            // Keep both target classes available for the UI/classifier. The state machine
            // performs the final confidence gate, so rejecting low scores here only hides
            // useful detection information.
            .setScoreThreshold(0.0f)
            .setMaxResults(2)
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .setMinHandPresenceConfidence(0.35f)
            .setCannedGesturesClassifierOptions(gestureClasses)
            // IMAGE mode is intentionally used here. Inference is synchronous on the
            // single analysis executor, which avoids keeping a recycled Bitmap alive while
            // LIVE_STREAM inference is still pending and eliminates stale async callbacks.
            .setRunningMode(RunningMode.IMAGE)
            .build()

        recognizer = GestureRecognizer.createFromOptions(context, options)
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }
        analysisUseCase = analysis

        val preview = previewView?.let { view ->
            Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        }
        previewUseCase = preview

        // Only this controller owns the session at this point. Unbinding here clears any
        // stale use-cases left by a previous controller before binding the new session.
        provider.unbindAll()
        val useCases = if (preview != null) {
            arrayOf<androidx.camera.core.UseCase>(analysis, preview)
        } else {
            arrayOf<androidx.camera.core.UseCase>(analysis)
        }
        val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases)
        observeCameraState(camera)
    }

    /**
     * Camera failures that happen after bindToLifecycle() returns (camera disabled by policy,
     * in use by another app, fatal HAL error, ...) are reported asynchronously by CameraX.
     * Surface them instead of silently running without a camera.
     */
    private fun observeCameraState(camera: Camera) {
        removeCameraStateObserver()
        val liveData = camera.cameraInfo.cameraState
        val observer = Observer<CameraState> { cameraState ->
            val error = cameraState.error
            if (error != null && started) {
                val critical = error.code == CAMERA_ERROR_STREAM_CONFIG ||
                    error.code == CAMERA_ERROR_DISABLED ||
                    error.code == CAMERA_ERROR_FATAL
                Log.w(TAG, "Camera state error code=${error.code} critical=$critical")
                if (critical) {
                    val message = "Camera error (code ${error.code}). Check that no other app is blocking the camera."
                    publish(GestureUiState(message = message))
                    safely { onFatalError(message) }
                    stop()
                } else {
                    publish(GestureUiState(message = "Camera is busy or temporarily unavailable. Retrying…"))
                }
            }
        }
        cameraStateData = liveData
        cameraStateObserver = observer
        liveData.observe(lifecycleOwner, observer)
    }

    private fun removeCameraStateObserver() {
        val data = cameraStateData
        val observer = cameraStateObserver
        cameraStateData = null
        cameraStateObserver = null
        if (data != null && observer != null) {
            // LiveData observers must be removed on the main thread.
            mainExecutor.execute { runCatching { data.removeObserver(observer) } }
        }
    }

    private fun analyze(image: ImageProxy) {
        if (!acceptingFrames.get()) {
            image.close()
            return
        }

        var sourceBitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        try {
            sourceBitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            rotatedBitmap = rotateForInference(sourceBitmap, rotation)
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result = recognizer?.recognize(mpImage)
            if (result != null && started) {
                handleResult(result)
            }
        } catch (error: Exception) {
            // Do not wedge ImageAnalysis. Surface occasional engine errors without flooding
            // the UI when a single camera frame is malformed.
            publishErrorRateLimited(error)
        } finally {
            if (rotatedBitmap != null && rotatedBitmap !== sourceBitmap) {
                runCatching { rotatedBitmap.recycle() }
            }
            runCatching { sourceBitmap?.recycle() }
            image.close()
        }
    }

    private fun rotateForInference(source: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return source
        val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private var lastErrorAt = Long.MIN_VALUE
    private var lastUiPublishAt = Long.MIN_VALUE

    private fun publishErrorRateLimited(error: Exception) {
        val now = SystemClock.uptimeMillis()
        if (lastErrorAt != Long.MIN_VALUE && now - lastErrorAt < 1500L) return
        lastErrorAt = now
        publish(GestureUiState(message = "Gesture frame error: ${error.message ?: "unknown error"}"))
    }

    private fun handleResult(result: GestureRecognizerResult) {
        if (!started) return
        val now = SystemClock.uptimeMillis()
        val hands = result.landmarks()

        if (hands.size != 1) {
            publishThrottled(GestureUiState(message = "Show one hand clearly in the frame."), now)
            stateMachine.update(GestureStateMachine.Label.NONE, 0f, now, receivePending())
            return
        }

        val landmarks = hands[0]
        if (landmarks.size < 21) {
            publishThrottled(GestureUiState(message = "Hand tracking is incomplete. Hold your hand steady."), now)
            stateMachine.update(GestureStateMachine.Label.NONE, 0f, now, receivePending())
            return
        }

        val minX = landmarks.minOf { it.x() }
        val maxX = landmarks.maxOf { it.x() }
        val minY = landmarks.minOf { it.y() }
        val maxY = landmarks.maxOf { it.y() }
        val width = (maxX - minX).coerceAtLeast(0f)
        val height = (maxY - minY).coerceAtLeast(0f)
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        val area = width * height
        val sizeQuality = (area / 0.035f).coerceIn(0f, 1f)
        val centerDistance = abs(centerX - 0.5f) + abs(centerY - 0.5f)
        val centerQuality = (1f - centerDistance / 0.95f).coerceIn(0f, 1f)
        val handQuality = (sizeQuality * 0.65f + centerQuality * 0.35f).coerceIn(0f, 1f)

        val categories = result.gestures().firstOrNull().orEmpty()
        val bestTarget = categories
            .filter { it.categoryName().equals("Open_Palm", true) || it.categoryName().equals("Closed_Fist", true) }
            .maxByOrNull { it.score() }

        val fallback = classifyFromLandmarks(landmarks)
        val classifierLabel = bestTarget?.let { labelFor(it.categoryName()) } ?: GestureStateMachine.Label.NONE
        val classifierConfidence = bestTarget?.score() ?: 0f

        val useFallback = bestTarget == null || classifierConfidence < 0.30f || classifierLabel == GestureStateMachine.Label.NONE
        val label = if (useFallback) fallback?.first ?: classifierLabel else classifierLabel
        val confidence = when {
            useFallback && fallback != null -> fallback.second
            bestTarget != null -> classifierConfidence
            else -> null
        }

        // MediaPipe has already positively detected a hand. Keep the UI responsive at a
        // distance while using a modest quality floor for actual triggers.
        val acceptedForTrigger = handQuality >= 0.12f
        val effectiveLabel = if (acceptedForTrigger) label else GestureStateMachine.Label.NONE
        val action = stateMachine.update(effectiveLabel, confidence ?: 0f, now, receivePending())

        val message = when {
            !acceptedForTrigger -> "Hand seen. Move a little closer or toward the center."
            label == GestureStateMachine.Label.OPEN_PALM && confidence != null && confidence >= 0.45f -> when (mode) {
                GestureStateMachine.GestureMode.CAPTURE,
                GestureStateMachine.GestureMode.BACKGROUND -> "Open palm detected. Close your hand to capture."
                GestureStateMachine.GestureMode.SEND -> "Open palm detected."
                GestureStateMachine.GestureMode.RECEIVE -> "Open palm detected."
            }
            label == GestureStateMachine.Label.CLOSED_FIST && confidence != null && confidence >= 0.45f -> when (mode) {
                GestureStateMachine.GestureMode.CAPTURE -> "Closed fist detected."
                GestureStateMachine.GestureMode.BACKGROUND -> "Closed fist detected. Open your hand to receive."
                GestureStateMachine.GestureMode.SEND -> "Closed fist detected. Open your hand to send."
                GestureStateMachine.GestureMode.RECEIVE -> "Closed fist detected."
            }
            label != GestureStateMachine.Label.NONE -> "Hand detected. Hold steady."
            else -> "Show an open palm or closed fist."
        }

        publishThrottled(GestureUiState(label, confidence?.coerceIn(0f, 1f), handQuality, message), now)
        if (action != GestureStateMachine.Action.NONE) {
            mainExecutor.execute { safely { onAction(action) } }
        }
    }

    private fun labelFor(category: String): GestureStateMachine.Label = when (category.lowercase()) {
        "open_palm", "open palm" -> GestureStateMachine.Label.OPEN_PALM
        "closed_fist", "closed fist" -> GestureStateMachine.Label.CLOSED_FIST
        else -> GestureStateMachine.Label.NONE
    }

    /** Landmark-only fallback used when the canned classifier temporarily returns no class. */
    private fun classifyFromLandmarks(landmarks: List<NormalizedLandmark>): Pair<GestureStateMachine.Label, Float>? {
        if (landmarks.size < 21) return null

        fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
            val dx = (a.x() - b.x()).toDouble()
            val dy = (a.y() - b.y()).toDouble()
            return hypot(dx, dy).toFloat()
        }

        fun angle(a: NormalizedLandmark, b: NormalizedLandmark, c: NormalizedLandmark): Float {
            val abx = a.x() - b.x()
            val aby = a.y() - b.y()
            val cbx = c.x() - b.x()
            val cby = c.y() - b.y()
            val denominator = hypot(abx.toDouble(), aby.toDouble()) * hypot(cbx.toDouble(), cby.toDouble())
            if (denominator <= 1e-6) return 0f
            val cosine = ((abx * cbx + aby * cby) / denominator).coerceIn(-1.0, 1.0)
            return Math.toDegrees(kotlin.math.acos(cosine)).toFloat()
        }

        fun extended(mcpIndex: Int, pipIndex: Int, dipIndex: Int, tipIndex: Int): Boolean {
            val wrist = landmarks[0]
            val mcp = landmarks[mcpIndex]
            val pip = landmarks[pipIndex]
            val dip = landmarks[dipIndex]
            val tip = landmarks[tipIndex]
            val tipDistance = distance(wrist, tip)
            val pipDistance = distance(wrist, pip)
            val pipAngle = angle(mcp, pip, dip)
            val dipAngle = angle(pip, dip, tip)
            return tipDistance > pipDistance * 1.02f && pipAngle > 145f && dipAngle > 145f
        }

        return runCatching {
            val wrist = landmarks[0]
            val fingerExtended = listOf(
                extended(5, 6, 7, 8),
                extended(9, 10, 11, 12),
                extended(13, 14, 15, 16),
                extended(17, 18, 19, 20),
            )
            val thumbExtended = distance(wrist, landmarks[4]) > distance(wrist, landmarks[3]) * 1.10f
            val extendedCount = fingerExtended.count { it } + if (thumbExtended) 1 else 0
            when {
                extendedCount >= 4 -> GestureStateMachine.Label.OPEN_PALM to 0.70f
                extendedCount <= 1 -> GestureStateMachine.Label.CLOSED_FIST to 0.66f
                else -> null
            }
        }.getOrNull()
    }

    private fun publish(state: GestureUiState) {
        mainExecutor.execute { safely { onState(state) } }
    }

    /** Callbacks run on the main thread; an exception in one must never take the process down. */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            Log.e(TAG, "Gesture callback failed", error)
        }
    }

    private fun publishThrottled(state: GestureUiState, now: Long) {
        if (lastUiPublishAt != Long.MIN_VALUE && now - lastUiPublishAt < 100L) return
        lastUiPublishAt = now
        publish(state)
    }

    companion object {
        fun stopActive(onStopped: () -> Unit = {}) {
            val controller = synchronized(REGISTRY_LOCK) {
                val current = activeController?.takeIf { it.started }
                activeController = null
                current
            }
            if (controller == null) {
                onStopped()
            } else {
                controller.stopInternal(unbindCamera = true, onStopped = onStopped)
            }
        }

        private const val TAG = "PalmLink"
        private const val MODEL_ASSET = "gesture_recognizer.task"

        // CameraState.StateError codes (androidx.camera.core.CameraState.ERROR_*).
        private const val CAMERA_ERROR_STREAM_CONFIG = 4
        private const val CAMERA_ERROR_DISABLED = 5
        private const val CAMERA_ERROR_FATAL = 6
        private val REGISTRY_LOCK = Any()
        private var activeController: GestureRecognizerController? = null
    }
}
