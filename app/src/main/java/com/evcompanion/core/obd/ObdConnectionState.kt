package com.evcompanion.core.obd

sealed class ObdConnectionState {
    data object Disconnected : ObdConnectionState()
    data object Connecting : ObdConnectionState()
    data object Initializing : ObdConnectionState()
    data object Connected : ObdConnectionState()
    data class Error(val message: String) : ObdConnectionState()
}
