package edu.unt.smartsight.metaglassestest

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveModeTest {
    @Test
    fun freshFrameUsesTheCacheAndOlderOrMissingFramesCapture() {
        assertEquals(FrameChoice.CACHED, chooseFrame(0))
        assertEquals(FrameChoice.CACHED, chooseFrame(1_500))
        assertEquals(FrameChoice.CAPTURE, chooseFrame(1_501))
        assertEquals(FrameChoice.CAPTURE, chooseFrame(null))
    }

    @Test
    fun onlyCaptureShortPressStartsActiveMode() {
        assertTrue(isCaptureTrigger(ActiveInput.CAPTURE_SHORT, fromCaptureButton = true, sessionStarted = true, inputsActive = true))
        assertFalse(isCaptureTrigger(ActiveInput.CAPTURE_SHORT, fromCaptureButton = false, sessionStarted = true, inputsActive = true))
        assertFalse(isCaptureTrigger(ActiveInput.CAPTURE_SHORT, fromCaptureButton = true, sessionStarted = false, inputsActive = true))
        assertFalse(isCaptureTrigger(ActiveInput.CAPTURE_SHORT, fromCaptureButton = true, sessionStarted = true, inputsActive = false))
        listOf(
            ActiveInput.CAPTURE_HOLD,
            ActiveInput.CAPTURE_DOUBLE,
            ActiveInput.CAPTURE_OTHER,
            ActiveInput.NAV,
            ActiveInput.SELECT,
            ActiveInput.BACK,
            ActiveInput.BUTTON,
            ActiveInput.DRAG,
            ActiveInput.UNKNOWN,
        ).forEach { input ->
            assertFalse(isCaptureTrigger(input, fromCaptureButton = true, sessionStarted = true, inputsActive = true))
        }
    }

    @Test
    fun actionOutputCollapsesWhitespaceAndCapsLength() {
        assertEquals("hello there", shortActionOutput("  hello \n there "))
        assertEquals("a".repeat(160), shortActionOutput("a".repeat(160)))
        val capped = shortActionOutput("a".repeat(161))
        assertEquals(160, capped.length)
        assertTrue(capped.endsWith("..."))
    }

    @Test
    fun freshFrameIsEncodedOnceAndSentWithTheTranscript() = runBlocking {
        val jpeg = byteArrayOf(1, 2, 3, 4)
        val host = FakeHost(frameAge = 1_500, cachedJpeg = jpeg)
        val router = RecordingRouter()
        val controller = controller(host, router.client)
        val voice = VoiceTracker()

        controller.onVoice(voice.answer)
        controller.join()

        assertEquals(1, host.cachedCalls)
        assertEquals(0, host.captureCalls)
        assertEquals(1, router.calls)
        val encoded = Base64.getEncoder().encodeToString(jpeg)
        assertEquals(1, router.body!!.split(encoded).size - 1)
        assertTrue(router.body!!.contains("final request"))
        assertFalse(router.body!!.contains("partial words"))
        assertEquals(listOf("A red cup."), voice.successes)
        assertTrue(voice.failures.isEmpty())
        assertEquals(listOf(false, true), host.passiveChanges)
    }

    @Test
    fun staleOrMissingFrameCapturesOnce() = runBlocking {
        val host = FakeHost(frameAge = 1_501, capturedJpeg = byteArrayOf(9))
        val router = RecordingRouter()
        controller(host, router.client).apply {
            onCaptureShortPress()
            join()
        }
        assertEquals(0, host.cachedCalls)
        assertEquals(1, host.captureCalls)

        val missing = FakeHost(frameAge = null, capturedJpeg = byteArrayOf(9))
        controller(missing, RecordingRouter().client).apply {
            onCaptureShortPress()
            join()
        }
        assertEquals(1, missing.captureCalls)
        assertEquals(0, missing.cachedCalls)
    }

    @Test
    fun voiceWaitsUntilTheSessionIsStreaming() = runBlocking {
        val host = FakeHost(runnable = false)
        val router = RecordingRouter()
        val controller = controller(host, router.client)
        val voice = VoiceTracker()

        controller.onVoice(voice.answer)
        yield()
        assertEquals(0, host.transcribeCalls)
        assertEquals(0, router.calls)

        host.runnable = true
        controller.onSessionReady()
        controller.join()

        assertEquals(1, host.transcribeCalls)
        assertEquals(1, router.calls)
        assertEquals(listOf("A red cup."), voice.successes)
    }

    @Test
    fun captureDoesNotReplaceAQueuedVoiceRequest() = runBlocking {
        val host = FakeHost(runnable = false)
        val controller = controller(host, RecordingRouter().client)
        val voice = VoiceTracker()

        controller.onVoice(voice.answer)
        controller.onCaptureShortPress()
        host.runnable = true
        controller.onSessionReady()
        controller.join()

        assertEquals(1, host.transcribeCalls)
        assertEquals(listOf("A red cup."), voice.successes)
    }

    @Test
    fun newerVoiceReplacesAQueuedVoice() = runBlocking {
        val host = FakeHost(runnable = false)
        val controller = controller(host, RecordingRouter().client)
        val first = VoiceTracker()
        val second = VoiceTracker()

        controller.onVoice(first.answer)
        controller.onVoice(second.answer)
        assertEquals(listOf("Replaced"), first.failures)

        host.runnable = true
        controller.onSessionReady()
        controller.join()

        assertEquals(listOf("A red cup."), second.successes)
        assertEquals(listOf("Replaced"), first.failures)
    }

    @Test
    fun captureAndVoiceAreIgnoredWhileActiveModeIsRunning() = runBlocking {
        val host = FakeHost(hangSpeech = true)
        val controller = controller(host, RecordingRouter().client)
        controller.onCaptureShortPress()
        while (host.transcribeCalls == 0) {
            yield()
        }
        controller.onCaptureShortPress()
        val voice = VoiceTracker()
        controller.onVoice(voice.answer)

        assertEquals(listOf("COULD_NOT_ANSWER"), voice.failures)
        assertEquals(1, host.transcribeCalls)
        controller.onSessionPaused()
        controller.join()
        assertEquals(1, host.transcribeCalls)
    }

    @Test
    fun partialsAndBlankFinalDoNotCallOpenRouter() = runBlocking {
        val host = FakeHost(
            script = { onPartial ->
                onPartial("partial words", 0.4f)
                SpeechOutcome.Final("   ", -1f)
            },
        )
        val router = RecordingRouter()
        val voice = VoiceTracker()
        val controller = controller(host, router.client)

        controller.onVoice(voice.answer)
        controller.join()

        assertEquals(0, router.calls)
        assertEquals(listOf("NO_SPEECH"), voice.failures)
        assertEquals("partial words", host.partialSeen)
    }

    @Test
    fun unscoredFinalStillCallsOpenRouter() = runBlocking {
        val host = FakeHost()
        val router = RecordingRouter()
        val controller = controller(host, router.client)

        controller.onCaptureShortPress()
        controller.join()

        assertEquals(1, router.calls)
        assertTrue(host.logs.any { it.contains("unscored") })
        assertEquals(listOf(ActiveModeNotice.LISTENING), host.notices.filter { it == ActiveModeNotice.LISTENING })
    }

    @Test
    fun openRouterFailureStillSucceedsTheInvocationOnce() = runBlocking {
        val host = FakeHost()
        val router = RecordingRouter(fail = true)
        val controller = controller(host, router.client)
        val voice = VoiceTracker()

        controller.onVoice(voice.answer)
        controller.join()

        assertEquals(listOf("COULD_NOT_ANSWER"), voice.successes)
        assertTrue(voice.failures.isEmpty())
        assertEquals(listOf(ActiveModeNotice.LISTENING, ActiveModeNotice.COULD_NOT_ANSWER), host.notices)
        voice.answer.succeed("again")
        voice.answer.fail("again")
        assertEquals(listOf("COULD_NOT_ANSWER"), voice.successes)
        assertTrue(voice.failures.isEmpty())
    }

    @Test
    fun leavingBeforeOpenRouterFailsTheInvocationOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val host = FakeHost()
        val client = OpenRouterClient(
            apiKey = "test-key",
            model = "google/gemini-2.5-flash",
            post = {
                started.complete(Unit)
                suspendCancellableCoroutine { }
            },
        )
        val controller = controller(host, client)
        val voice = VoiceTracker()

        controller.onVoice(voice.answer)
        while (!started.isCompleted) {
            yield()
        }
        controller.onSessionPaused()
        controller.join()

        assertEquals(listOf("Stopped"), voice.failures)
        assertTrue(voice.successes.isEmpty())
        voice.answer.fail("again")
        assertEquals(listOf("Stopped"), voice.failures)
    }

    @Test
    fun passiveDetectionTurnsBackOnUnlessTheSessionStopped() = runBlocking {
        val running = FakeHost()
        controller(running, RecordingRouter().client).apply {
            onCaptureShortPress()
            join()
        }
        assertEquals(listOf(false, true), running.passiveChanges)

        val stopped = FakeHost(hangSpeech = true)
        val controller = controller(stopped, RecordingRouter().client)
        controller.onCaptureShortPress()
        while (stopped.transcribeCalls == 0) {
            yield()
        }
        stopped.stopped = true
        controller.onSessionStopped()
        controller.join()
        assertEquals(listOf(false), stopped.passiveChanges)
    }

    @Test
    fun speechTimeoutEndsTheRunWithoutAModelCall() = runBlocking {
        val host = FakeHost(hangSpeech = true)
        val router = RecordingRouter()
        val voice = VoiceTracker()
        val controller = controller(host, router.client, speechTimeoutMs = 40)

        controller.onVoice(voice.answer)
        controller.join()

        assertEquals(0, router.calls)
        assertEquals(listOf(ActiveModeNotice.LISTENING, ActiveModeNotice.NO_SPEECH), host.notices)
        assertEquals(listOf("NO_SPEECH"), voice.failures)
        assertEquals(listOf(false, true), host.passiveChanges)
    }

    @Test
    fun microphoneOrImageFailureSkipsOpenRouter() = runBlocking {
        val denied = FakeHost(microphone = false)
        val deniedRouter = RecordingRouter()
        controller(denied, deniedRouter.client).apply {
            onCaptureShortPress()
            join()
        }
        assertEquals(0, deniedRouter.calls)
        assertEquals(listOf(ActiveModeNotice.LISTENING, ActiveModeNotice.NO_MICROPHONE), denied.notices)
        assertEquals(listOf(false, true), denied.passiveChanges)

        val noImage = FakeHost(frameAge = 10, cachedJpeg = byteArrayOf())
        val imageRouter = RecordingRouter()
        controller(noImage, imageRouter.client).apply {
            onCaptureShortPress()
            join()
        }
        assertEquals(0, imageRouter.calls)
        assertEquals(0, noImage.captureCalls)
        assertEquals(listOf(ActiveModeNotice.LISTENING, ActiveModeNotice.NO_IMAGE), noImage.notices)
    }

    @Test
    fun emptyApiKeyDoesNotCallTheNetwork() = runBlocking {
        var called = false
        val client = OpenRouterClient(
            apiKey = "  ",
            model = "google/gemini-2.5-flash",
            post = {
                called = true
                ""
            },
        )

        val result = client.complete(byteArrayOf(1), "what is this")

        assertTrue(result.isFailure)
        assertEquals("OpenRouter API key is not configured.", result.exceptionOrNull()?.message)
        assertFalse(called)
    }

    @Test
    fun requestBodyCarriesTranscriptAndOneJpeg() {
        val jpeg = byteArrayOf(10, 20, 30)
        val body = OpenRouterClient.requestBody("google/gemini-2.5-flash", "what is this", jpeg)
        val encoded = Base64.getEncoder().encodeToString(jpeg)

        assertTrue(body.contains("\"model\":\"google/gemini-2.5-flash\""))
        assertTrue(body.contains("what is this"))
        assertEquals(1, body.split("data:image/jpeg;base64,").size - 1)
        assertTrue(body.contains(encoded))
    }

    @Test
    fun completionBodyParsesAssistantText() {
        assertEquals(
            "A red cup.",
            OpenRouterClient.parseContent(
                """{"choices":[{"message":{"role":"assistant","content":"A red cup."}}]}""",
            ),
        )
        assertEquals(
            "A red cup.",
            OpenRouterClient.parseContent(
                """{"choices":[{"message":{"content":[{"type":"text","text":"A red cup."}]}}]}""",
            ),
        )
        assertNull(OpenRouterClient.parseContent("""{"choices":[{"message":{"content":"  "}}]}"""))
        assertNull(OpenRouterClient.parseContent("""{"choices":[]}"""))
    }

    private fun CoroutineScope.controller(
        host: FakeHost,
        client: OpenRouterClient,
        speechTimeoutMs: Long = 2_000,
    ): ActiveModeController {
        return ActiveModeController(
            host = host,
            openRouter = client,
            scope = this,
            speechTimeoutMs = speechTimeoutMs,
            nowMs = { 5_000L },
        )
    }
}

private class VoiceTracker {
    val successes = mutableListOf<String>()
    val failures = mutableListOf<String>()
    val answer = InvocationAnswer(
        onSuccess = { successes += it },
        onFailure = { failures += it },
    )
}

private class RecordingRouter(fail: Boolean = false) {
    var calls = 0
    var body: String? = null
    val client = OpenRouterClient(
        apiKey = "test-key",
        model = "google/gemini-2.5-flash",
        post = { request ->
            calls += 1
            body = request.body
            if (fail) {
                throw IOException("down")
            }
            """{"choices":[{"message":{"content":"A red cup."}}]}"""
        },
    )
}

private class FakeHost(
    var runnable: Boolean = true,
    var stopped: Boolean = false,
    var frameAge: Long? = 100,
    var cachedJpeg: ByteArray? = byteArrayOf(1, 2, 3),
    var capturedJpeg: ByteArray = byteArrayOf(4, 5, 6),
    var microphone: Boolean = true,
    var hangSpeech: Boolean = false,
    var outcome: SpeechOutcome = SpeechOutcome.Final("final request", -1f),
    var script: (suspend (onPartial: (String, Float) -> Unit) -> SpeechOutcome)? = null,
) : ActiveModeHost {
    var cachedCalls = 0
    var captureCalls = 0
    var transcribeCalls = 0
    var partialSeen: String? = null
    val passiveChanges = mutableListOf<Boolean>()
    val notices = mutableListOf<ActiveModeNotice>()
    val logs = mutableListOf<String>()

    override fun canRun(): Boolean = runnable

    override fun sessionStopped(): Boolean = stopped

    override fun latestFrameAgeMs(nowMs: Long): Long? = frameAge

    override suspend fun jpegFromCachedFrame(): ByteArray? {
        cachedCalls += 1
        return cachedJpeg
    }

    override suspend fun captureJpeg(): Result<ByteArray> {
        captureCalls += 1
        return Result.success(capturedJpeg)
    }

    override suspend fun ensureMicrophone(): Boolean = microphone

    override suspend fun transcribe(onPartial: (String, Float) -> Unit): SpeechOutcome {
        transcribeCalls += 1
        val record: (String, Float) -> Unit = { text, confidence ->
            partialSeen = text
            onPartial(text, confidence)
        }
        val scripted = script
        if (scripted != null) {
            return scripted(record)
        }
        if (hangSpeech) {
            suspendCancellableCoroutine<Unit> { }
        }
        record("partial words", -1f)
        return outcome
    }

    override fun stopSpeech() = Unit

    override fun setPassiveDetectionEnabled(enabled: Boolean) {
        passiveChanges += enabled
    }

    override fun showListening() {
        notices += ActiveModeNotice.LISTENING
    }

    override fun showSuccess(text: String) = Unit

    override fun showNotice(notice: ActiveModeNotice) {
        notices += notice
    }

    override fun text(notice: ActiveModeNotice): String = notice.name

    override fun log(message: String) {
        logs += message
    }
}
