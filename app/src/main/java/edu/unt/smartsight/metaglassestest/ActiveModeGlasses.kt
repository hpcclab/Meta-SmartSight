package edu.unt.smartsight.metaglassestest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.CaptureError
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.speech.Speech
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import com.meta.wearable.dat.speech.types.SpeechError
import com.meta.wearable.dat.speech.types.SpeechState
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal data class TimedFrame(
    val bitmap: Bitmap,
    val atMs: Long,
)

/**
 * SDK calls for active mode. Speech and Inputs are beta capabilities.
 * Raw microphone audio is not read here. Speech returns text only.
 */
internal class ActiveModeGlasses(
    private val context: Context,
    private val session: () -> DeviceSession?,
    private val stream: () -> Stream?,
    private val frame: () -> TimedFrame?,
    private val sessionState: () -> DeviceSessionState?,
    private val requestMicrophone: suspend () -> PermissionStatus?,
    private val setPassive: (Boolean) -> Unit,
    private val onListening: () -> Unit,
    private val onSuccess: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onMicrophonePrompt: () -> Unit,
) : ActiveModeHost {
    private var speech: Speech? = null
    private val stopRequested = AtomicBoolean(false)

    override fun canRun(): Boolean {
        return sessionState() == DeviceSessionState.STARTED &&
            stream()?.state?.value == StreamState.STREAMING
    }

    override fun sessionStopped(): Boolean {
        val state = sessionState()
        return state == null ||
            state == DeviceSessionState.STOPPED ||
            state == DeviceSessionState.STOPPING
    }

    override fun latestFrameAgeMs(nowMs: Long): Long? {
        val timed = frame() ?: return null
        return nowMs - timed.atMs
    }

    override suspend fun jpegFromCachedFrame(): ByteArray? {
        val bitmap = frame()?.bitmap ?: return null
        return jpegBytes(bitmap)
    }

    override suspend fun captureJpeg(): Result<ByteArray> {
        val active = stream()
        if (active == null || active.state.value != StreamState.STREAMING) {
            Log.e(ACTIVE_MODE_TAG, "capturePhoto failed: NotStreaming")
            return Result.failure(IllegalStateException(text(ActiveModeNotice.NO_IMAGE)))
        }
        return active.capturePhoto().fold(
            onSuccess = { data ->
                val bytes = photoToJpeg(data)
                if (bytes == null || bytes.isEmpty()) {
                    Result.failure(IllegalStateException(text(ActiveModeNotice.NO_IMAGE)))
                } else {
                    Result.success(bytes)
                }
            },
            onFailure = { error, _ ->
                logCaptureError(error)
                Result.failure(IllegalStateException(text(ActiveModeNotice.NO_IMAGE)))
            },
        )
    }

    override suspend fun ensureMicrophone(): Boolean {
        var granted = false
        var checkFailed = false
        Wearables.checkPermissionStatus(Permission.MICROPHONE).fold(
            onSuccess = { status -> granted = status == PermissionStatus.Granted },
            onFailure = { error, _ ->
                checkFailed = true
                Log.e(ACTIVE_MODE_TAG, "microphone permission check failed: ${error.description}")
            },
        )
        if (checkFailed) {
            return false
        }
        if (granted) {
            return true
        }
        onMicrophonePrompt()
        val status = requestMicrophone()
        return status == PermissionStatus.Granted
    }

    override suspend fun transcribe(onPartial: (String, Float) -> Unit): SpeechOutcome {
        val current = session() ?: return SpeechOutcome.Failed(text(ActiveModeNotice.SPEECH_STOPPED))
        if (sessionState() != DeviceSessionState.STARTED) {
            return SpeechOutcome.Failed(text(ActiveModeNotice.SPEECH_STOPPED))
        }
        val attached = attachSpeech(current) ?: return SpeechOutcome.Failed(text(ActiveModeNotice.SPEECH_STOPPED))
        stopRequested.set(false)
        return coroutineScope {
            val result = kotlinx.coroutines.CompletableDeferred<SpeechOutcome>()
            var leftStopped = false
            var firstError = true
            var firstTranscript = true
            var submitted = false
            val jobs = listOf(
                launch {
                    attached.state.collect { state ->
                        logSpeechState(state)
                        when (state) {
                            SpeechState.STARTING,
                            SpeechState.STARTED,
                            SpeechState.STOPPING,
                            -> leftStopped = true
                            SpeechState.STOPPED -> {
                                if (leftStopped && !stopRequested.get() && !result.isCompleted) {
                                    result.complete(SpeechOutcome.Failed(text(ActiveModeNotice.SPEECH_STOPPED)))
                                }
                            }
                            else -> Log.w(ACTIVE_MODE_TAG, "Speech state unknown: $state")
                        }
                    }
                },
                launch {
                    attached.errors.collect { error ->
                        if (firstError) {
                            firstError = false
                            return@collect
                        }
                        if (error == null || result.isCompleted) {
                            return@collect
                        }
                        result.complete(failSpeech(error))
                    }
                },
                launch {
                    attached.errorDetails.collect { details ->
                        if (!details.isNullOrBlank()) {
                            Log.i(ACTIVE_MODE_TAG, "Speech error detail: $details")
                        }
                    }
                },
                launch {
                    attached.transcriptions.collect { value ->
                        if (firstTranscript) {
                            firstTranscript = false
                            return@collect
                        }
                        if (value == null) {
                            return@collect
                        }
                        logConfidence(value.confidence)
                        if (!value.isFinal) {
                            onPartial(value.text, value.confidence)
                            return@collect
                        }
                        if (submitted || result.isCompleted) {
                            return@collect
                        }
                        submitted = true
                        result.complete(SpeechOutcome.Final(value.text, value.confidence))
                    }
                },
            )
            yield()
            attached.start().fold(
                onSuccess = { Log.i(ACTIVE_MODE_TAG, "Speech start requested") },
                onFailure = { error, _ ->
                    if (!result.isCompleted) {
                        result.complete(failSpeech(error))
                    }
                },
            )
            try {
                result.await()
            } finally {
                stopRequested.set(true)
                jobs.forEach { it.cancel() }
                attached.stop()
            }
        }
    }

    override fun stopSpeech() {
        stopRequested.set(true)
        speech?.stop()
    }

    fun releaseSpeech() {
        stopSpeech()
        if (speech == null) {
            return
        }
        speech = null
        val current = session() ?: return
        current.removeSpeech().fold(
            onSuccess = { Log.i(ACTIVE_MODE_TAG, "Speech removed") },
            onFailure = { error, _ ->
                Log.w(ACTIVE_MODE_TAG, "removeSpeech failed: ${error.description}")
            },
        )
    }

    override fun setPassiveDetectionEnabled(enabled: Boolean) {
        setPassive(enabled)
    }

    override fun showListening() {
        onListening()
    }

    override fun showSuccess(text: String) {
        onSuccess(text)
    }

    override fun showNotice(notice: ActiveModeNotice) {
        onError(text(notice))
    }

    override fun text(notice: ActiveModeNotice): String {
        return context.getString(stringRes(notice))
    }

    override fun log(message: String) {
        Log.i(ACTIVE_MODE_TAG, message)
    }

    private fun attachSpeech(current: DeviceSession): Speech? {
        speech?.let { return it }
        var opened: Speech? = null
        current.addSpeech().fold(
            onSuccess = { created ->
                speech = created
                opened = created
                Log.i(
                    ACTIVE_MODE_TAG,
                    "Speech attached. Speech is a beta capability and is not a production guarantee.",
                )
            },
            onFailure = { error, _ ->
                Log.e(ACTIVE_MODE_TAG, "addSpeech failed: ${error.description}")
                if (error == DeviceSessionError.CAPABILITY_ALREADY_ADDED) {
                    current.removeSpeech()
                    current.addSpeech().fold(
                        onSuccess = { created ->
                            speech = created
                            opened = created
                        },
                        onFailure = { retryError, _ ->
                            Log.e(ACTIVE_MODE_TAG, "addSpeech retry failed: ${retryError.description}")
                        },
                    )
                }
            },
        )
        return opened
    }

    private fun photoToJpeg(data: PhotoData): ByteArray? {
        return when (data) {
            is PhotoData.Bitmap -> jpegBytes(data.bitmap)
            is PhotoData.HEIC -> {
                val buffer = data.data.duplicate()
                val encoded = ByteArray(buffer.remaining())
                buffer.get(encoded)
                val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: return null
                jpegBytes(bitmap)
            }
            else -> {
                Log.e(ACTIVE_MODE_TAG, "Unknown photo type")
                null
            }
        }
    }

    private fun logCaptureError(error: CaptureError) {
        val label = when (error) {
            CaptureError.DeviceDisconnected -> "DeviceDisconnected"
            CaptureError.NotStreaming -> "NotStreaming"
            CaptureError.CaptureInProgress -> "CaptureInProgress"
            CaptureError.CaptureFailed -> "CaptureFailed"
            else -> "UNKNOWN"
        }
        Log.e(ACTIVE_MODE_TAG, "capturePhoto failed: $label")
    }

    private fun logSpeechState(state: SpeechState) {
        val label = when (state) {
            SpeechState.STARTING -> "STARTING"
            SpeechState.STARTED -> "STARTED"
            SpeechState.STOPPING -> "STOPPING"
            SpeechState.STOPPED -> "STOPPED"
            else -> "UNKNOWN"
        }
        Log.i(ACTIVE_MODE_TAG, "Speech state: $label")
    }

    private fun failSpeech(error: SpeechError): SpeechOutcome.Failed {
        val label = when (error) {
            SpeechError.DEVICE_DISCONNECTED -> "DEVICE_DISCONNECTED"
            SpeechError.INVALID_STATE -> "INVALID_STATE"
            SpeechError.UNAVAILABLE -> "UNAVAILABLE"
            SpeechError.ALREADY_LISTENING -> "ALREADY_LISTENING"
            SpeechError.START_FAILED -> "START_FAILED"
            SpeechError.UNEXPECTED_ERROR -> "UNEXPECTED_ERROR"
            else -> "UNKNOWN"
        }
        Log.e(ACTIVE_MODE_TAG, "Speech error: $label ${error.description}")
        return SpeechOutcome.Failed(text(ActiveModeNotice.SPEECH_STOPPED))
    }

    private fun logConfidence(confidence: Float) {
        if (confidence == ActiveModeController.UNSCORED_CONFIDENCE) {
            Log.i(ACTIVE_MODE_TAG, "Speech confidence unscored")
        } else {
            Log.i(ACTIVE_MODE_TAG, "Speech confidence $confidence")
        }
    }

    private fun stringRes(notice: ActiveModeNotice): Int {
        return when (notice) {
            ActiveModeNotice.LISTENING -> R.string.active_listening
            ActiveModeNotice.NO_SPEECH -> R.string.active_no_speech
            ActiveModeNotice.NO_IMAGE -> R.string.active_no_image
            ActiveModeNotice.NO_MICROPHONE -> R.string.active_microphone_denied
            ActiveModeNotice.COULD_NOT_ANSWER -> R.string.active_could_not_answer
            ActiveModeNotice.SPEECH_STOPPED -> R.string.active_speech_stopped
        }
    }
}

internal fun jpegBytes(bitmap: Bitmap, quality: Int = JPEG_QUALITY): ByteArray {
    val output = ByteArrayOutputStream()
    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
        return ByteArray(0)
    }
    return output.toByteArray()
}

private const val JPEG_QUALITY = 80
