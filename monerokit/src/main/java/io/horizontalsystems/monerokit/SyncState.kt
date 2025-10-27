package io.horizontalsystems.monerokit

sealed class SyncState(val description: String) {
    object Synced : SyncState("Synced")
    data class Connecting(val waiting: Boolean) : SyncState("Connecting (waiting: $waiting)")
    data class Syncing(val progress: Double? = null, val remainingBlocks: Long? = null) : SyncState("Syncing (${progress?.times(100)?.toInt() ?:0 }%)")
    data class NotSynced(val error: Throwable) : SyncState("NotSynced (${error.message ?: error.javaClass.simpleName})")
}
