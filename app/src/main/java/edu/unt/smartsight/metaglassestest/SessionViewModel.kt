package edu.unt.smartsight.metaglassestest

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.VoiceInvocation
import com.meta.wearable.dat.inputs.Inputs
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.removeInputs
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Developer Mode registration, a glasses session, and a low-rate video stream.
 *
 * While the session is started, the glasses stream at [FRAME_RATE] frames per second.
 * YOLO26n runs on those frames at [CaptureSettings.DEFAULT_DETECTIONS_PER_SECOND]
 * (adjustable in the UI). Labels that were not seen within the announce window are spoken
 * through the glasses speakers. Mock Device Kit is not enabled, so the session uses the
 * glasses connected in the Meta AI app.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(CaptureSettings.PREFS, Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()
    private val detector = YoloDetector(application)
    private val announcer = DetectionAnnouncer()
    private val speaker = GlassesSpeaker(
        application,
        onGlassesUnavailable = {
            _uiState.update {
                it.copy(audioWarning = application.getString(R.string.glasses_audio_unavailable))
            }
        },
        onGlassesReady = {
            _uiState.update { it.copy(audioWarning = null) }
        },
    )

    private var observing = false
    private var session: DeviceSession? = null
    private var sessionJobs: List<Job> = emptyList()
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var streamJobs: List<Job> = emptyList()
    private var cameraPermissionRequester: (suspend () -> PermissionStatus)? = null
    private var microphonePermissionRequester: (suspend () -> PermissionStatus)? = null
    private var captureJob: Job? = null
    private var detectionJob: Job? = null
    private var lastDetectionStartedAt = 0L
    private var loggedFrameFormat = false
    private var latestFrame: TimedFrame? = null
    private var passiveDetectionEnabled = true
    private var inputs: Inputs? = null
    private var inputsJobs: List<Job> = emptyList()
    private var inputsActive = false
    private var voiceStream: VoiceInvocationsStream? = null
    private var voiceJobs: List<Job> = emptyList()
    private val activeAnswerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeHost = ActiveModeGlasses(
        context = application,
        session = { session },
        stream = { stream },
        frame = { latestFrame },
        sessionState = { _uiState.value.sessionState },
        requestMicrophone = { microphonePermissionRequester?.invoke() },
        setPassive = { enabled -> setPassiveDetectionEnabled(enabled) },
        onListening = { showActiveListening() },
        onSuccess = { text -> showActiveSuccess(text) },
        onError = { text -> showActiveError(text) },
        onMicrophonePrompt = {
            _uiState.update {
                it.copy(message = application.getString(R.string.allow_microphone))
            }
        },
    )
    private val activeMode = ActiveModeController(
        host = activeHost,
        openRouter = OpenRouterClient(
            apiKey = BuildConfig.OPENROUTER_API_KEY,
            model = BuildConfig.OPENROUTER_MODEL,
        ),
        scope = viewModelScope,
        answerScope = activeAnswerScope,
        nowMs = { SystemClock.elapsedRealtime() },
    )

    init {
        _uiState.update {
            it.copy(
                detectionsPerSecond = preferences.getFloat(
                    CaptureSettings.KEY_RATE,
                    CaptureSettings.DEFAULT_DETECTIONS_PER_SECOND,
                ).coerceIn(
                    CaptureSettings.MIN_DETECTIONS_PER_SECOND,
                    CaptureSettings.MAX_DETECTIONS_PER_SECOND,
                ),
                announceGapSeconds = preferences.getInt(
                    CaptureSettings.KEY_GAP,
                    CaptureSettings.DEFAULT_GAP_SECONDS,
                ).coerceIn(CaptureSettings.MIN_GAP_SECONDS, CaptureSettings.MAX_GAP_SECONDS),
            )
        }
    }

    fun bindCameraPermissionRequester(requester: suspend () -> PermissionStatus) {
        cameraPermissionRequester = requester
    }

    fun bindMicrophonePermissionRequester(requester: suspend () -> PermissionStatus) {
        microphonePermissionRequester = requester
    }

    /** Records a voice launch so the invocation stream starts once the SDK is ready. */
    fun noteVoiceLaunch() {
        Log.i(ACTIVE_MODE_TAG, "Voice launch intent noted")
        ensureVoiceStream()
    }

    fun setDetectionsPerSecond(value: Float) {
        val clamped = value.coerceIn(
            CaptureSettings.MIN_DETECTIONS_PER_SECOND,
            CaptureSettings.MAX_DETECTIONS_PER_SECOND,
        )
        preferences.edit().putFloat(CaptureSettings.KEY_RATE, clamped).apply()
        _uiState.update { it.copy(detectionsPerSecond = clamped) }
    }

    fun setAnnounceGapSeconds(value: Int) {
        val clamped = value.coerceIn(CaptureSettings.MIN_GAP_SECONDS, CaptureSettings.MAX_GAP_SECONDS)
        preferences.edit().putInt(CaptureSettings.KEY_GAP, clamped).apply()
        _uiState.update { it.copy(announceGapSeconds = clamped) }
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(message = getApplication<Application>().getString(R.string.permissions_required))
        }
    }

    fun onSdkError(message: String) {
        Log.e(TAG, message)
        _uiState.update { it.copy(sdkReady = false, message = message) }
    }

    fun onSdkReady() {
        if (observing) {
            return
        }
        observing = true
        _uiState.update { it.copy(sdkReady = true, message = null) }
        observeRegistration()
        ensureVoiceStream()
    }

    fun register(activity: Activity) {
        if (!_uiState.value.canRegister) {
            return
        }
        _uiState.update { it.copy(message = null) }
        Wearables.startRegistration(activity)
    }

    fun startSession() {
        if (!_uiState.value.canStartSession) {
            return
        }
        _uiState.update { it.copy(message = null) }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { created ->
                session = created
                observeSession(created)
                created.start()
            },
            onFailure = { error, _ ->
                showError("createSession failed", error.getLocalizedDescription(getApplication()))
            },
        )
    }

    fun stopSession() {
        if (!_uiState.value.canStopSession) {
            return
        }
        _uiState.update { it.copy(message = null) }
        session?.stop()
    }

    private fun startAutomaticCapture() {
        if (captureJob?.isActive == true) {
            return
        }
        captureJob = viewModelScope.launch {
            val requester = cameraPermissionRequester
            if (requester == null) {
                showStartupError(
                    "camera permission requester missing",
                    getApplication<Application>().getString(R.string.allow_camera),
                )
                return@launch
            }
            if (!ensureCameraPermission(requester)) {
                return@launch
            }
            val current = session
            if (current == null || _uiState.value.sessionState != DeviceSessionState.STARTED) {
                return@launch
            }
            _uiState.update {
                it.copy(message = getApplication<Application>().getString(R.string.starting_camera))
            }
            val active = openCamera(current) ?: return@launch
            if (!awaitStreaming(active)) {
                showStartupError(
                    "stream did not reach STREAMING",
                    getApplication<Application>().getString(R.string.camera_not_streaming),
                )
                return@launch
            }
            _uiState.update { it.copy(message = null) }
            activeMode.onSessionReady()
            // Frames arrive on videoStream. This job only keeps the camera open for the session.
            while (isActive && sessionIsOpen()) {
                delay(CAPTURE_WAIT_MS)
            }
        }
    }

    private fun sessionIsOpen(): Boolean {
        return when (_uiState.value.sessionState) {
            DeviceSessionState.STARTED,
            DeviceSessionState.PAUSED,
            DeviceSessionState.STARTING,
            -> true
            else -> false
        }
    }

    private fun onVideoFrame(frame: VideoFrame) {
        if (frame.isCompressed || frame.isCodecConfig) {
            frame.buffer.remaining()
            return
        }
        val bitmap = I420Frames.toBitmap(frame.buffer, frame.width, frame.height)
        if (bitmap == null) {
            if (!loggedFrameFormat) {
                loggedFrameFormat = true
                Log.e(
                    TAG,
                    "Video frame was not tightly packed I420: " +
                        "${frame.width}x${frame.height}, ${frame.buffer.remaining()} bytes",
                )
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        latestFrame = TimedFrame(bitmap, now)
        _uiState.update { it.copy(photo = bitmap) }
        if (!passiveDetectionEnabled) {
            return
        }
        val interval = CaptureSettings.intervalMs(_uiState.value.detectionsPerSecond)
        if (detectionJob?.isActive == true || now - lastDetectionStartedAt < interval) {
            return
        }
        lastDetectionStartedAt = now
        detectionJob = viewModelScope.launch(Dispatchers.Default) {
            detectFrame(bitmap)
        }
    }

    private fun detectFrame(bitmap: Bitmap) {
        if (!passiveDetectionEnabled) {
            return
        }
        _uiState.update { it.copy(detecting = true) }
        try {
            val detected = detector.detect(bitmap)
            detected.fold(
                onSuccess = { labels -> publishDetections(labels) },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            message = error.message
                                ?: getApplication<Application>().getString(R.string.detection_failed),
                        )
                    }
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "detection failed", error)
            showError("detection failed", error.message ?: "Object detection failed.")
        } finally {
            _uiState.update { it.copy(detecting = false) }
        }
    }

    private fun publishDetections(labels: Set<String>) {
        if (!passiveDetectionEnabled) {
            return
        }
        val fresh = announcer.freshLabels(
            detected = labels,
            nowMs = SystemClock.elapsedRealtime(),
            gapMs = _uiState.value.announceGapSeconds * 1000L,
        )
        if (fresh.isNotEmpty()) {
            speaker.speak(fresh.joinToString(", "))
        }
        _uiState.update {
            it.copy(
                detections = labels.sorted(),
                spoken = if (fresh.isNotEmpty()) fresh.joinToString(", ") else it.spoken,
                message = if (passiveDetectionEnabled) null else it.message,
            )
        }
    }

    private suspend fun ensureCameraPermission(
        requestCameraPermission: suspend () -> PermissionStatus,
    ): Boolean {
        var granted = false
        var checkFailed = false
        Wearables.checkPermissionStatus(Permission.CAMERA).fold(
            onSuccess = { status -> granted = status == PermissionStatus.Granted },
            onFailure = { error, _ ->
                checkFailed = true
                showStartupError(
                    "camera permission check failed",
                    error.getLocalizedDescription(getApplication()),
                )
            },
        )
        if (checkFailed) {
            return false
        }
        if (granted) {
            return true
        }
        _uiState.update {
            it.copy(message = getApplication<Application>().getString(R.string.allow_camera))
        }
        val status = requestCameraPermission()
        if (status == PermissionStatus.Granted) {
            return true
        }
        showStartupError(
            "camera permission denied",
            getApplication<Application>().getString(R.string.camera_permission_denied),
        )
        return false
    }

    private fun openCamera(current: DeviceSession): Stream? {
        reusableStream()?.let { return it }
        var opened: Stream? = null
        current.addCamera(
            StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = FRAME_RATE,
            ),
        ).fold(
            onSuccess = { added ->
                camera = added
                val created = added.stream
                stream = created
                observeStream(created)
                created.start().fold(
                    onSuccess = { opened = created },
                    onFailure = { error, _ ->
                        showStartupError(
                            "stream start failed",
                            error.getLocalizedDescription(getApplication()),
                        )
                        releaseCamera()
                    },
                )
            },
            onFailure = { error, _ ->
                showStartupError("addCamera failed", error.getLocalizedDescription(getApplication()))
            },
        )
        return opened
    }

    private fun reusableStream(): Stream? {
        val existing = stream ?: return null
        return when (existing.state.value) {
            StreamState.STOPPED, StreamState.STOPPING, StreamState.CLOSED -> {
                releaseCamera()
                null
            }
            else -> existing
        }
    }

    private suspend fun awaitStreaming(active: Stream): Boolean {
        if (active.state.value == StreamState.STREAMING) {
            return true
        }
        val state = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            active.state.first { next ->
                next == StreamState.STREAMING ||
                    next == StreamState.STOPPED ||
                    next == StreamState.CLOSED
            }
        }
        return state == StreamState.STREAMING
    }

    private fun observeRegistration() {
        viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                _uiState.update { it.copy(registrationState = state) }
            }
        }
        viewModelScope.launch {
            Wearables.registrationErrorStream.collect { error ->
                showError("Registration error", error.getLocalizedDescription(getApplication()))
            }
        }
    }

    private fun observeSession(created: DeviceSession) {
        sessionJobs.forEach { it.cancel() }
        sessionJobs = listOf(
            viewModelScope.launch {
                created.state.collect { state ->
                    _uiState.update { it.copy(sessionState = state) }
                    if (state == DeviceSessionState.STARTED) {
                        if (!activeMode.isRunning()) {
                            passiveDetectionEnabled = true
                        }
                        startAutomaticCapture()
                        attachInputs(created)
                        activeMode.onSessionReady()
                    }
                    if (state == DeviceSessionState.PAUSED) {
                        activeMode.onSessionPaused()
                    }
                    if (state == DeviceSessionState.STOPPING || state == DeviceSessionState.STOPPED) {
                        activeMode.onSessionStopped()
                    }
                    if (state == DeviceSessionState.STOPPED) {
                        captureJob?.cancel()
                        captureJob = null
                        detectionJob?.cancel()
                        detectionJob = null
                        releaseInputs()
                        activeHost.releaseSpeech()
                        releaseCamera()
                        session = null
                    }
                }
            },
            viewModelScope.launch {
                created.errors.collect { error ->
                    showError("Session error", error.getLocalizedDescription(getApplication()))
                }
            },
        )
    }

    private fun observeStream(created: Stream) {
        streamJobs.forEach { it.cancel() }
        streamJobs = listOf(
            viewModelScope.launch(Dispatchers.Default) {
                created.videoStream.collect { frame ->
                    onVideoFrame(frame)
                }
            },
            viewModelScope.launch {
                created.state.collect { state ->
                    Log.i(TAG, "Stream state: $state")
                    if (state == StreamState.STREAMING) {
                        activeMode.onSessionReady()
                    }
                }
            },
            viewModelScope.launch {
                created.errorStream.collect { error ->
                    showError("Stream error", error.getLocalizedDescription(getApplication()))
                }
            },
        )
    }

    private fun ensureVoiceStream() {
        if (voiceStream != null || !_uiState.value.sdkReady) {
            return
        }
        val opened = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
        voiceStream = opened
        observeVoice(opened)
    }

    private fun observeVoice(opened: VoiceInvocationsStream) {
        voiceJobs.forEach { it.cancel() }
        var firstState = true
        voiceJobs = listOf(
            viewModelScope.launch {
                opened.state.collect { state ->
                    val first = firstState
                    firstState = false
                    if (voiceSessionLost(state, first)) {
                        activeMode.onVoiceChannelLost()
                    }
                }
            },
            viewModelScope.launch {
                opened.errors.collect { error ->
                    if (voiceChannelLost(error)) {
                        activeMode.onVoiceChannelLost()
                    }
                }
            },
            viewModelScope.launch {
                opened.invocations.collect { invocation ->
                    handleVoiceInvocation(invocation)
                }
            },
        )
    }

    private fun handleVoiceInvocation(invocation: VoiceInvocation) {
        val answer = voiceInvocationAnswer(invocation) ?: return
        activeMode.onVoice(answer)
    }

    private fun attachInputs(current: DeviceSession) {
        if (inputs != null) {
            return
        }
        // Capture button only. Touchpad tap and tap-and-hold stay with the session.
        current.addInputs(
            InputsConfiguration(sources = setOf(InputSource.CAPTURE_BUTTON)),
        ).fold(
            onSuccess = { created ->
                inputs = created
                observeInputs(created)
            },
            onFailure = { error, _ ->
                Log.e(ACTIVE_MODE_TAG, "addInputs failed: ${error.description}")
                if (error == DeviceSessionError.CAPABILITY_ALREADY_ADDED) {
                    Log.i(ACTIVE_MODE_TAG, "Inputs already attached; not adding again")
                }
                _uiState.update {
                    it.copy(message = getApplication<Application>().getString(R.string.inputs_unavailable))
                }
            },
        )
    }

    private fun observeInputs(created: Inputs) {
        inputsJobs.forEach { it.cancel() }
        inputsJobs = listOf(
            viewModelScope.launch {
                created.state.collect { state ->
                    logInputsState(state)
                    inputsActive = state == InputsState.ACTIVE
                }
            },
            viewModelScope.launch {
                created.errors.collect { error ->
                    if (error == null) {
                        return@collect
                    }
                    logInputsError(error)
                    _uiState.update {
                        it.copy(message = getApplication<Application>().getString(R.string.inputs_unavailable))
                    }
                }
            },
            viewModelScope.launch {
                created.events.collect { event ->
                    logInput(event)
                    val (kind, fromCaptureButton) = classifyInput(event)
                    if (
                        isCaptureTrigger(
                            input = kind,
                            fromCaptureButton = fromCaptureButton,
                            sessionStarted = _uiState.value.sessionState == DeviceSessionState.STARTED,
                            inputsActive = inputsActive,
                        )
                    ) {
                        activeMode.onCaptureShortPress()
                    }
                }
            },
        )
    }

    private fun releaseInputs() {
        inputsJobs.forEach { it.cancel() }
        inputsJobs = emptyList()
        val hadInputs = inputs != null
        inputs = null
        inputsActive = false
        if (!hadInputs) {
            return
        }
        val current = session ?: return
        current.removeInputs().fold(
            onSuccess = { Log.i(ACTIVE_MODE_TAG, "Inputs removed") },
            onFailure = { error, _ ->
                Log.w(ACTIVE_MODE_TAG, "removeInputs failed: ${error.description}")
            },
        )
    }

    private fun setPassiveDetectionEnabled(enabled: Boolean) {
        passiveDetectionEnabled = enabled
        Log.i(ACTIVE_MODE_TAG, "Passive detection enabled=$enabled")
        if (!enabled) {
            detectionJob?.cancel()
            detectionJob = null
            _uiState.update { it.copy(detecting = false) }
        }
    }

    private fun showActiveListening() {
        _uiState.update {
            it.copy(
                activeResult = getApplication<Application>().getString(R.string.active_listening),
                message = null,
            )
        }
    }

    private fun showActiveSuccess(text: String) {
        _uiState.update { it.copy(activeResult = text, message = null) }
    }

    private fun showActiveError(text: String) {
        Log.e(TAG, "active mode: $text")
        _uiState.update { it.copy(message = text, activeResult = null) }
    }

    private fun releaseCamera() {
        streamJobs.forEach { it.cancel() }
        streamJobs = emptyList()
        latestFrame = null
        val closing = camera
        camera = null
        stream = null
        try {
            closing?.close()
        } catch (error: Exception) {
            Log.w(TAG, "camera close failed", error)
        }
    }

    private fun showError(logMessage: String, userMessage: String) {
        Log.e(TAG, "$logMessage: $userMessage")
        _uiState.update { it.copy(message = userMessage) }
    }

    private fun showStartupError(logMessage: String, userMessage: String) {
        showError(logMessage, userMessage)
        activeMode.failQueued(userMessage)
    }

    override fun onCleared() {
        activeMode.abandon()
        voiceJobs.forEach { it.cancel() }
        voiceJobs = emptyList()
        try {
            voiceStream?.close()
        } catch (error: Exception) {
            Log.w(TAG, "voice stream close failed", error)
        }
        voiceStream = null
        releaseInputs()
        activeHost.releaseSpeech()
        captureJob?.cancel()
        detectionJob?.cancel()
        sessionJobs.forEach { it.cancel() }
        releaseCamera()
        session?.stop()
        speaker.close()
        detector.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "MetaGlassesTest"
        /** Lowest frame rate the toolkit accepts. Detection timing is separate. */
        const val FRAME_RATE = 2
        const val STREAM_TIMEOUT_MS = 20_000L
        const val CAPTURE_WAIT_MS = 200L
    }
}
