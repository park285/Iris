package party.qwer.iris.config

import party.qwer.iris.ConfigRuntimeState
import party.qwer.iris.UserConfigState

internal class ConfigStateStore {
    @Volatile
    private var state: ConfigRuntimeState = ConfigRuntimeState()

    fun current(): ConfigRuntimeState = state

    @Synchronized
    fun mutate(transform: (ConfigRuntimeState) -> ConfigRuntimeState): ConfigRuntimeState {
        val updated = transform(state)
        state = updated
        return updated
    }

    @Synchronized
    fun replace(nextState: ConfigRuntimeState): ConfigRuntimeState {
        state = nextState
        return nextState
    }

    fun updateUserState(
        applyImmediately: Boolean,
        transform: (UserConfigState) -> UserConfigState,
    ): ConfigRuntimeState =
        mutate { current ->
            val updatedSnapshot = transform(current.snapshotUser)
            val updatedApplied =
                if (applyImmediately) {
                    transform(current.appliedUser)
                } else {
                    current.appliedUser
                }
            if (updatedSnapshot == current.snapshotUser && updatedApplied == current.appliedUser) {
                current
            } else {
                current.copy(
                    snapshotUser = updatedSnapshot,
                    appliedUser = updatedApplied,
                    isDirty = true,
                )
            }
        }

    @Synchronized
    fun clearDirty() {
        state = state.copy(isDirty = false)
    }

    @Synchronized
    fun clearDirtyIf(savedSnapshot: UserConfigState) {
        if (state.snapshotUser == savedSnapshot) {
            state = state.copy(isDirty = false)
        }
    }
}
