package edu.unt.smartsight.metaglassestest

import android.graphics.Bitmap
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.RegistrationState

data class SessionUiState(
    val sdkReady: Boolean = false,
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val sessionState: DeviceSessionState? = null,
    val photo: Bitmap? = null,
    val detecting: Boolean = false,
    val detections: List<String>? = null,
    val spoken: String? = null,
    val detectionsPerSecond: Float = CaptureSettings.DEFAULT_DETECTIONS_PER_SECOND,
    val announceGapSeconds: Int = CaptureSettings.DEFAULT_GAP_SECONDS,
    val activeResult: String? = null,
    val message: String? = null,
    val audioWarning: String? = null,
) {
    val canRegister: Boolean =
        sdkReady &&
            registrationState != RegistrationState.REGISTERED &&
            registrationState != RegistrationState.REGISTERING &&
            registrationState != RegistrationState.UNREGISTERING

    val canStartSession: Boolean =
        sdkReady &&
            registrationState == RegistrationState.REGISTERED &&
            (sessionState == null || sessionState == DeviceSessionState.STOPPED)

    val canStopSession: Boolean =
        sessionState == DeviceSessionState.IDLE ||
            sessionState == DeviceSessionState.STARTING ||
            sessionState == DeviceSessionState.STARTED ||
            sessionState == DeviceSessionState.PAUSED
}
