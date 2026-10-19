package edu.unt.smartsight.metaglassestest

import android.util.Log
import com.meta.wearable.dat.core.voiceinvocations.types.SessionState
import com.meta.wearable.dat.core.voiceinvocations.types.VoiceInvocationError
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import com.meta.wearable.dat.core.voiceinvocations.types.actions.VoiceInvocation

/**
 * LaunchApp is the only invocation the toolkit delivers today.
 * An unknown invocation has no response handle, so it can only be logged.
 */
internal fun voiceInvocationAnswer(invocation: VoiceInvocation): InvocationAnswer? {
    return when (invocation) {
        is LaunchApp -> {
            val handle = invocation.responseHandle
            Log.i(ACTIVE_MODE_TAG, "Voice invocation: LaunchApp")
            InvocationAnswer(
                onSuccess = { output ->
                    val accepted = handle.sendSuccess(output)
                    Log.i(ACTIVE_MODE_TAG, "Voice sendSuccess accepted=$accepted")
                },
                onFailure = { reason ->
                    val accepted = handle.sendFailure(reason)
                    Log.i(ACTIVE_MODE_TAG, "Voice sendFailure accepted=$accepted reason=$reason")
                },
            )
        }
        else -> {
            Log.w(ACTIVE_MODE_TAG, "Voice invocation unknown: $invocation")
            null
        }
    }
}

/** @return true when an unanswered invocation must be failed. The first event is the current state. */
internal fun voiceSessionLost(state: SessionState, firstEvent: Boolean): Boolean {
    val label = when (state) {
        SessionState.STARTING -> "STARTING"
        SessionState.STARTED -> "STARTED"
        SessionState.STOPPED -> "STOPPED"
        else -> "UNKNOWN"
    }
    Log.i(ACTIVE_MODE_TAG, "Voice session state: $label")
    if (firstEvent) {
        return false
    }
    return when (state) {
        SessionState.STARTING,
        SessionState.STARTED,
        -> false
        SessionState.STOPPED -> true
        else -> true
    }
}

/** @return true when the voice channel can no longer deliver an answer. */
internal fun voiceChannelLost(error: VoiceInvocationError): Boolean {
    val label = when (error) {
        VoiceInvocationError.CHANNEL_NOT_CONNECTED -> "CHANNEL_NOT_CONNECTED"
        VoiceInvocationError.CHANNEL_ERROR -> "CHANNEL_ERROR"
        VoiceInvocationError.FAIL_TO_SEND_INIT_REQUEST -> "FAIL_TO_SEND_INIT_REQUEST"
        VoiceInvocationError.FAIL_TO_SEND_MESSAGE -> "FAIL_TO_SEND_MESSAGE"
        VoiceInvocationError.INIT_REQUEST_ERROR -> "INIT_REQUEST_ERROR"
        VoiceInvocationError.INVALID_ACTION_MESSAGE_PROTO -> "INVALID_ACTION_MESSAGE_PROTO"
        VoiceInvocationError.UNKNOWN_MESSAGE_TYPE -> "UNKNOWN_MESSAGE_TYPE"
        VoiceInvocationError.ACTION_FAILED -> "ACTION_FAILED"
        VoiceInvocationError.INVALID_SESSION_STATE -> "INVALID_SESSION_STATE"
        else -> "UNKNOWN"
    }
    Log.e(ACTIVE_MODE_TAG, "Voice error: $label ${error.description}")
    return when (error) {
        VoiceInvocationError.CHANNEL_NOT_CONNECTED,
        VoiceInvocationError.CHANNEL_ERROR,
        VoiceInvocationError.INVALID_SESSION_STATE,
        -> true
        VoiceInvocationError.FAIL_TO_SEND_INIT_REQUEST,
        VoiceInvocationError.FAIL_TO_SEND_MESSAGE,
        VoiceInvocationError.INIT_REQUEST_ERROR,
        VoiceInvocationError.INVALID_ACTION_MESSAGE_PROTO,
        VoiceInvocationError.UNKNOWN_MESSAGE_TYPE,
        VoiceInvocationError.ACTION_FAILED,
        -> false
        else -> true
    }
}
