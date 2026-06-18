package io.horizontalsystems.monerokit

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object KitManager {
    enum class KitState { Running, Waiting, Obsolete }

    private val lock = ReentrantLock()
    private var runningKitId: String? = null
    private var waitingKitId: String? = null

    // Serializes the native wallet lifecycle (startInternal/stopInternal). The
    // primary hazard is a single kit: its _start and _stop run as separate
    // coroutines and interleave because startInternal() suspends (withContext(IO)
    // in createWalletIfNotExists()), so a teardown can run mid-open — orphaning a
    // wallet whose native refresh/long-poll threads then race close()'s SSL
    // teardown (X509_STORE_free crash). Holding this lock across the whole of
    // startInternal()/stopInternal() prevents that interleave.
    //
    // It lives here (global, shared by all kits) so the invariant "only one wallet
    // is opened or closed at a time" holds process-wide, matching the fact that
    // the Monero WalletManager is a global singleton. Cross-kit ORDERING during an
    // account switch is already guaranteed by the runningKitId gate below
    // (removeRunning() runs after stopInternal(), so a new kit opens only after the
    // previous one closed); the shared lock is defense-in-depth for that path.
    // Separate from `lock`, which only guards the running/waiting bookkeeping.
    val lifecycleMutex = Mutex()

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

    fun isRunning(kitId: String): Boolean = lock.withLock { runningKitId == kitId }
}
