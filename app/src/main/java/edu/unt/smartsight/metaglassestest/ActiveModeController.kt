package edu.unt.smartsight.metaglassestest

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class FrameChoice {
    CACHED,
    CAPTURE,
}

internal enum class ActiveInput {
    NAV,
    SELECT,
    BACK,
    BUTTON,
    CAPTURE_SHORT,
    CAPTURE_HOLD,
    CAPTURE_DOUBLE,
    CAPTURE_OTHER,
    DRAG,
    UNKNOWN,
}

internal enum class ActiveModeNotice {
    LISTENING,
    NO_SPEECH,
    NO_IMAGE,
    NO_MICROPHONE,
    COULD_NOT_ANSWER,
    SPEECH_STOPPED,
}

internal sealed class SpeechOutcome {
    data class Final(val text: String, val confidence: Float) : SpeechOutcome()
    data class Failed(val message: String) : SpeechOutcome()
}

/**
 * Answers one voice invocation at most once. A second success or failure is a no-op.
 */
internal class InvocationAnswer(
    private val onSuccess: suspend (String) -> Unit,
    private val onFailure: suspend (String) -> Unit,
) {
    private val answered = AtomicBoolean(false)

    suspend fun succeed(output: String) {
        if (!answered.compareAndSet(false, true)) {
            return
        }
        try {
            onSuccess(output)
        } catch (error: CancellationException) {
            answered.set(false)
            throw error
        }
    }

    suspend fun fail(reason: String) {
        if (!answered.compareAndSet(false, true)) {
            return
        }
        try {
            onFailure(reason)
        } catch (error: CancellationException) {
            answered.set(false)
            throw error
        }
    }
}

internal interface ActiveModeHost {
    fun canRun(): Boolean
    fun sessionStopped(): Boolean
    fun latestFrameAgeMs(nowMs: Long): Long?
    suspend fun jpegFromCachedFrame(): ByteArray?
    suspend fun captureJpeg(): Result<ByteArray>
    suspend fun ensureMicrophone(): Boolean
    suspend fun transcribe(onPartial: (String, Float) -> Unit): SpeechOutcome
    fun stopSpeech()
    fun setPassiveDetectionEnabled(enabled: Boolean)
    fun showListening()
    fun showSuccess(text: String)
    fun showNotice(notice: ActiveModeNotice)
    fun text(notice: ActiveModeNotice): String
    fun log(message: String)
}

/**
 * The only active-mode sequence: pause passive detection, choose one JPEG,
 * run glasses Speech, then call OpenRouter.
 *
 * Speech is a beta capability and can stop without a final transcript.
 */
internal class ActiveModeController(
    private val host: ActiveModeHost,
    private val openRouter: OpenRouterClient,
    private val scope: CoroutineScope,
    private val answerScope: CoroutineScope = scope,
    private val speechTimeoutMs: Long = SPEECH_TIMEOUT_MS,
    private val frameMaxAgeMs: Long = FRAME_MAX_AGE_MS,
    private val nowMs: () -> Long,
) {
    private var job: Job? = null
    private var pending: Pending? = null
    private var partialTranscript: String? = null

    fun isRunning(): Boolean = job?.isActive == true

    suspend fun join() {
        job?.join()
    }

    fun onCaptureShortPress() {
        if (isRunning()) {
            host.log("Capture ignored because active mode is running")
            return
        }
        if (pending?.voice != null) {
            host.log("Capture ignored because a voice request is queued")
            return
        }
        if (pending != null) {
            return
        }
        pending = Pending(voice = null)
        tryStart()
    }

    fun onVoice(answer: InvocationAnswer) {
        if (isRunning()) {
            answerFailure(answer, host.text(ActiveModeNotice.COULD_NOT_ANSWER))
            host.log("Voice invocation rejected because active mode is running")
            return
        }
        pending?.voice?.let { previous ->
            answerFailure(previous, "Replaced")
        }
        pending = Pending(voice = answer)
        tryStart()
    }

    fun onSessionReady() {
        tryStart()
    }

    fun onSessionPaused() {
        job?.cancel()
    }

    fun onSessionStopped() {
        job?.cancel()
        failPending("Session stopped")
    }

    fun onVoiceChannelLost() {
        job?.cancel()
        failPending("Voice channel stopped")
    }

    fun abandon() {
        onSessionStopped()
    }

    /** Fails a queued voice when the camera never starts. A running request answers itself. */
    fun failQueued(reason: String) {
        if (isRunning()) {
            return
        }
        host.log("Active mode unavailable: $reason")
        failPending(reason)
    }

    private fun failPending(reason: String) {
        val queued = pending
        pending = null
        queued?.voice?.let { voice ->
            answerFailure(voice, reason)
        }
    }

    private fun answerFailure(voice: InvocationAnswer, reason: String) {
        answerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                voice.fail(reason)
            }
        }
    }

    private fun tryStart() {
        if (isRunning()) {
            return
        }
        val request = pending ?: return
        if (!host.canRun()) {
            host.log("Active mode queued until the session is streaming")
            return
        }
        pending = null
        job = scope.launch { run(request) }
    }

    private suspend fun run(request: Pending) {
        partialTranscript = null
        host.log("Active mode start")
        try {
            host.setPassiveDetectionEnabled(false)
            host.showListening()
            val jpeg = resolveJpeg()
            if (jpeg == null) {
                host.showNotice(ActiveModeNotice.NO_IMAGE)
                request.voice?.fail(host.text(ActiveModeNotice.NO_IMAGE))
                return
            }
            if (!host.ensureMicrophone()) {
                host.showNotice(ActiveModeNotice.NO_MICROPHONE)
                request.voice?.fail(host.text(ActiveModeNotice.NO_MICROPHONE))
                return
            }
            val outcome = withTimeoutOrNull(speechTimeoutMs) {
                host.transcribe { text, confidence ->
                    partialTranscript = text
                    if (confidence == UNSCORED_CONFIDENCE) {
                        host.log("Speech confidence unscored")
                    }
                }
            }
            host.stopSpeech()
            when (outcome) {
                null -> {
                    host.showNotice(ActiveModeNotice.NO_SPEECH)
                    request.voice?.fail(host.text(ActiveModeNotice.NO_SPEECH))
                }
                is SpeechOutcome.Failed -> {
                    host.showNotice(ActiveModeNotice.SPEECH_STOPPED)
                    request.voice?.fail(outcome.message)
                }
                is SpeechOutcome.Final -> deliverFinal(request, jpeg, outcome)
            }
        } finally {
            host.stopSpeech()
            if (!host.sessionStopped()) {
                host.setPassiveDetectionEnabled(true)
            }
            withContext(NonCancellable) {
                request.voice?.fail("Stopped")
            }
            partialTranscript = null
            host.log("Active mode exit")
        }
    }

    private suspend fun deliverFinal(request: Pending, jpeg: ByteArray, outcome: SpeechOutcome.Final) {
        if (outcome.confidence == UNSCORED_CONFIDENCE) {
            host.log("Final speech confidence unscored")
        }
        val transcript = outcome.text.trim()
        if (transcript.isEmpty()) {
            host.showNotice(ActiveModeNotice.NO_SPEECH)
            request.voice?.fail(host.text(ActiveModeNotice.NO_SPEECH))
            return
        }
        val model = openRouter.complete(jpeg, transcript)
        model.fold(
            onSuccess = { answer ->
                host.showSuccess(answer)
                request.voice?.succeed(shortActionOutput(answer))
            },
            onFailure = { error ->
                host.log("OpenRouter failed: ${error.message ?: "Could not answer."}")
                host.showNotice(ActiveModeNotice.COULD_NOT_ANSWER)
                request.voice?.succeed(host.text(ActiveModeNotice.COULD_NOT_ANSWER))
            },
        )
    }

    private suspend fun resolveJpeg(): ByteArray? {
        val choice = chooseFrame(host.latestFrameAgeMs(nowMs()), frameMaxAgeMs)
        host.log("Frame choice: $choice")
        val bytes = when (choice) {
            FrameChoice.CACHED -> host.jpegFromCachedFrame()
            FrameChoice.CAPTURE -> host.captureJpeg().getOrNull()
        }
        return bytes?.takeIf { it.isNotEmpty() }
    }

    private class Pending(val voice: InvocationAnswer?)

    companion object {
        const val FRAME_MAX_AGE_MS = 1_500L
        const val SPEECH_TIMEOUT_MS = 12_000L
        const val UNSCORED_CONFIDENCE = -1.0f
        const val ACTION_OUTPUT_LIMIT = 160
    }
}

internal fun chooseFrame(ageMs: Long?, maxAgeMs: Long = ActiveModeController.FRAME_MAX_AGE_MS): FrameChoice {
    return if (ageMs != null && ageMs <= maxAgeMs) FrameChoice.CACHED else FrameChoice.CAPTURE
}

internal fun isCaptureTrigger(
    input: ActiveInput,
    fromCaptureButton: Boolean,
    sessionStarted: Boolean,
    inputsActive: Boolean,
): Boolean {
    return input == ActiveInput.CAPTURE_SHORT &&
        fromCaptureButton &&
        sessionStarted &&
        inputsActive
}

internal fun shortActionOutput(text: String): String {
    val trimmed = text.trim().replace(Regex("\\s+"), " ")
    if (trimmed.length <= ActiveModeController.ACTION_OUTPUT_LIMIT) {
        return trimmed
    }
    return trimmed.take(ActiveModeController.ACTION_OUTPUT_LIMIT - 3) + "..."
}
