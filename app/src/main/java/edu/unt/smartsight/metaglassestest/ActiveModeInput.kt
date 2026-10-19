package edu.unt.smartsight.metaglassestest

import android.util.Log
import com.meta.wearable.dat.inputs.types.ButtonType
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.DragAction
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsError
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.inputs.types.NavDirection

internal const val ACTIVE_MODE_TAG = "ActiveMode"

internal fun classifyInput(event: InputEvent): Pair<ActiveInput, Boolean> {
    return when (event) {
        is InputEvent.Nav -> ActiveInput.NAV to false
        is InputEvent.Select -> ActiveInput.SELECT to false
        is InputEvent.Back -> ActiveInput.BACK to false
        is InputEvent.Button -> ActiveInput.BUTTON to false
        is InputEvent.Drag -> ActiveInput.DRAG to false
        is InputEvent.Capture -> classifyCapture(event) to (event.source == InputSource.CAPTURE_BUTTON)
        else -> ActiveInput.UNKNOWN to false
    }
}

internal fun logInput(event: InputEvent) {
    Log.i(ACTIVE_MODE_TAG, "Input ${describeInput(event)} source=${event.source}")
}

internal fun logInputsState(state: InputsState) {
    val label = when (state) {
        InputsState.INACTIVE -> "INACTIVE"
        InputsState.ACTIVATING -> "ACTIVATING"
        InputsState.ACTIVE -> "ACTIVE"
        InputsState.DEACTIVATING -> "DEACTIVATING"
        else -> "UNKNOWN"
    }
    Log.i(ACTIVE_MODE_TAG, "Inputs state: $label")
}

internal fun logInputsError(error: InputsError) {
    val label = when (error) {
        InputsError.PERMISSION_DENIED -> "PERMISSION_DENIED"
        InputsError.CONNECTION_CLOSED -> "CONNECTION_CLOSED"
        InputsError.ACTIVATION_TIMEOUT -> "ACTIVATION_TIMEOUT"
        InputsError.ACTIVATION_FAILED -> "ACTIVATION_FAILED"
        InputsError.DEVICE_DISCONNECTED -> "DEVICE_DISCONNECTED"
        InputsError.COMMUNICATION_ERROR -> "COMMUNICATION_ERROR"
        else -> "UNKNOWN"
    }
    Log.e(ACTIVE_MODE_TAG, "Inputs error: $label ${error.description}")
}

private fun classifyCapture(event: InputEvent.Capture): ActiveInput {
    return when (event.pressType) {
        CapturePressType.SHORT_PRESS -> ActiveInput.CAPTURE_SHORT
        CapturePressType.HOLD -> ActiveInput.CAPTURE_HOLD
        CapturePressType.DOUBLE_PRESS -> ActiveInput.CAPTURE_DOUBLE
        else -> ActiveInput.CAPTURE_OTHER
    }
}

private fun describeInput(event: InputEvent): String {
    return when (event) {
        is InputEvent.Nav -> "nav ${navLabel(event.direction)}"
        is InputEvent.Select -> "select"
        is InputEvent.Back -> "back"
        is InputEvent.Button -> "button ${buttonLabel(event.button)}"
        is InputEvent.Capture -> "capture ${pressLabel(event.pressType)}"
        is InputEvent.Drag -> "drag ${dragLabel(event.action)}"
        else -> "unknown"
    }
}

private fun navLabel(direction: NavDirection): String {
    return when (direction) {
        NavDirection.UP -> "UP"
        NavDirection.DOWN -> "DOWN"
        NavDirection.LEFT -> "LEFT"
        NavDirection.RIGHT -> "RIGHT"
        else -> "UNKNOWN"
    }
}

private fun buttonLabel(button: ButtonType): String {
    return when (button) {
        ButtonType.ACTION -> "ACTION"
        else -> "UNKNOWN"
    }
}

private fun pressLabel(pressType: CapturePressType): String {
    return when (pressType) {
        CapturePressType.SHORT_PRESS -> "SHORT_PRESS"
        CapturePressType.HOLD -> "HOLD"
        CapturePressType.DOUBLE_PRESS -> "DOUBLE_PRESS"
        else -> "UNKNOWN"
    }
}

private fun dragLabel(action: DragAction): String {
    return when (action) {
        DragAction.DOWN -> "DOWN"
        DragAction.MOVE -> "MOVE"
        DragAction.UP -> "UP"
        else -> "UNKNOWN"
    }
}
