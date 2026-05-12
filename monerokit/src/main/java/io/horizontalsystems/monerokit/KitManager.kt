package io.horizontalsystems.monerokit

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object KitManager {
    enum class KitState { Running, Waiting, Obsolete }

    private val lock = ReentrantLock()
    private var runningKitId: String? = null
    private var waitingKitId: String? = null

    fun checkAndGetInitialState(kitId: String): KitState = lock.withLock {
        if (runningKitId != null && runningKitId != kitId) {
            waitingKitId = kitId
            KitState.Waiting
        } else {
            runningKitId = kitId
            KitState.Running
        }
    }

    fun checkAndGetState(kitId: String): KitState = lock.withLock {
        if (runningKitId != null && runningKitId != kitId) {
            if (waitingKitId == kitId) {
                KitState.Waiting
            } else {
                KitState.Obsolete
            }
        } else {
            runningKitId = kitId
            KitState.Running
        }
    }

    fun removeRunning(kitId: String) = lock.withLock {
        if (runningKitId == kitId) {
            runningKitId = null
        }
    }

    fun removeWaiting(kitId: String) = lock.withLock {
        if (waitingKitId == kitId) {
            waitingKitId = null
        }
    }

    fun isRunning(kitId: String): Boolean = lock.withLock { runningKitId == kitId }
}
