package com.parentguard.agent.domain

import com.parentguard.core.protocol.CapabilityModule
import com.parentguard.core.protocol.ConsentGrantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ConsentStateMachine {

    private val _moduleStates = MutableStateFlow<Map<CapabilityModule, ConsentGrantState>>(
        CapabilityModule.entries.associateWith { ConsentGrantState.ACTIVE_GRANTED }
    )
    val moduleStates: StateFlow<Map<CapabilityModule, ConsentGrantState>> = _moduleStates.asStateFlow()

    fun onChildGrantedModule(module: CapabilityModule, androidOsPermissionGranted: Boolean) {
        _moduleStates.update { current ->
            current + (module to if (androidOsPermissionGranted) {
                ConsentGrantState.ACTIVE_GRANTED
            } else {
                ConsentGrantState.DENIED_OR_REVOKED_IN_OS_SETTINGS
            })
        }
    }

    fun onChildPausedModuleInApp(module: CapabilityModule) {
        _moduleStates.update { current ->
            current + (module to ConsentGrantState.PAUSED_BY_CHILD)
        }
    }

    fun onAndroidOsPermissionRevoked(module: CapabilityModule) {
        _moduleStates.update { current ->
            current + (module to ConsentGrantState.DENIED_OR_REVOKED_IN_OS_SETTINGS)
        }
    }

    fun onParentDisabledModule(module: CapabilityModule) {
        _moduleStates.update { current ->
            current + (module to ConsentGrantState.DISABLED_BY_PARENT)
        }
    }

    fun isCollectionAllowed(module: CapabilityModule, currentlyHasOsPermission: Boolean): Boolean {
        val state = _moduleStates.value[module] ?: ConsentGrantState.NOT_CONFIGURED
        return state == ConsentGrantState.ACTIVE_GRANTED && currentlyHasOsPermission
    }
}
