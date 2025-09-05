package io.horizontalsystems.monerokit

sealed class SyncState(val description: String) {
    object Synced : SyncState("Synced")
    object Connecting : SyncState("Connecting")
    data class Syncing(val progress: Double? = null) : SyncState("Syncing")
    data class NotSynced(val error: Throwable) : SyncState("Not Synced: ${error.message}")

    override fun toString(): String {
       return when (this) {
            is NotSynced -> "NotSynced"
            is Connecting -> "Connecting"
            is Synced -> "Synced"
            is Syncing -> "Syncing $progress * 100"
        }
    }
}
