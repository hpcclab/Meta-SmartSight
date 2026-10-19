package edu.unt.smartsight.metaglassestest

import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    private val viewModel: SessionViewModel by viewModels()
    private var sdkInitStarted = false
    private var cameraPermissionContinuation: CancellableContinuation<PermissionStatus>? = null
    private var microphonePermissionContinuation: CancellableContinuation<PermissionStatus>? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrDefault(PermissionStatus.Denied)
            cameraPermissionContinuation?.resume(status)
            cameraPermissionContinuation = null
        }

    private val microphonePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrDefault(PermissionStatus.Denied)
            microphonePermissionContinuation?.resume(status)
            microphonePermissionContinuation = null
        }

    private suspend fun requestCameraPermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            cameraPermissionContinuation = continuation
            continuation.invokeOnCancellation { cameraPermissionContinuation = null }
            cameraPermissionLauncher.launch(Permission.CAMERA)
        }
    }

    private suspend fun requestMicrophonePermission(): PermissionStatus {
        return suspendCancellableCoroutine { continuation ->
            microphonePermissionContinuation = continuation
            continuation.invokeOnCancellation { microphonePermissionContinuation = null }
            microphonePermissionLauncher.launch(Permission.MICROPHONE)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            if (result.values.any { granted -> !granted }) {
                viewModel.onPermissionDenied()
                return@registerForActivityResult
            }
            if (sdkInitStarted) {
                return@registerForActivityResult
            }
            sdkInitStarted = true
            Wearables.initialize(this).fold(
                onSuccess = { viewModel.onSdkReady() },
                onFailure = { error, _ ->
                    sdkInitStarted = false
                    val message = error.getLocalizedDescription(this)
                    Log.e(TAG, "Wearables.initialize failed: $message")
                    viewModel.onSdkError(message)
                },
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteVoiceIntent(intent)
        enableEdgeToEdge()
        setContent {
            SideEffect {
                viewModel.bindCameraPermissionRequester(::requestCameraPermission)
                viewModel.bindMicrophonePermissionRequester(::requestMicrophonePermission)
            }
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MaterialTheme {
                SessionScreen(
                    state = state,
                    onRegister = { viewModel.register(this) },
                    onStartSession = viewModel::startSession,
                    onStopSession = viewModel::stopSession,
                    onDetectionsPerSecond = viewModel::setDetectionsPerSecond,
                    onAnnounceGapSeconds = viewModel::setAnnounceGapSeconds,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteVoiceIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        permissionLauncher.launch(PERMISSIONS)
    }

    private fun noteVoiceIntent(intent: Intent?) {
        if (intent != null && isVoiceInvocationsIntent(intent)) {
            Log.i(TAG, "Voice invocation intent")
            viewModel.noteVoiceLaunch()
        }
    }

    private companion object {
        const val TAG = "MetaGlassesTest"
        val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)
    }
}

@Composable
private fun SessionScreen(
    state: SessionUiState,
    onRegister: () -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onDetectionsPerSecond: (Float) -> Unit,
    onAnnounceGapSeconds: (Int) -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.developer_mode),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.registration_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.registrationState.name,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.session_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.sessionState?.name ?: stringResource(R.string.session_none),
                style = MaterialTheme.typography.headlineSmall,
            )
            state.photo?.let { photo ->
                val image = remember(photo) { photo.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.captured_photo),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
            state.detections?.let { detections ->
                Text(
                    text = if (detections.isEmpty()) {
                        stringResource(R.string.detected_none)
                    } else {
                        stringResource(R.string.detected_objects, detections.joinToString(", "))
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state.spoken?.let { spoken ->
                Text(
                    text = stringResource(R.string.spoken_objects, spoken),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.detecting) {
                Text(
                    text = stringResource(R.string.detecting),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.activeResult?.let { result ->
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            state.audioWarning?.let { warning ->
                Text(
                    text = warning,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(R.string.detections_per_second, state.detectionsPerSecond),
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = state.detectionsPerSecond,
                onValueChange = onDetectionsPerSecond,
                valueRange = CaptureSettings.MIN_DETECTIONS_PER_SECOND..CaptureSettings.MAX_DETECTIONS_PER_SECOND,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.announce_gap, state.announceGapSeconds),
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = state.announceGapSeconds.toFloat(),
                onValueChange = { onAnnounceGapSeconds(it.roundToInt()) },
                valueRange = CaptureSettings.MIN_GAP_SECONDS.toFloat()..CaptureSettings.MAX_GAP_SECONDS.toFloat(),
                steps = CaptureSettings.MAX_GAP_SECONDS - CaptureSettings.MIN_GAP_SECONDS - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onRegister,
                enabled = state.canRegister,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.register))
            }
            Button(
                onClick = onStartSession,
                enabled = state.canStartSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.start_session))
            }
            Button(
                onClick = onStopSession,
                enabled = state.canStopSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.stop_session))
            }
        }
    }
}
