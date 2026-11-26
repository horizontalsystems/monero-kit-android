package io.horizontalsystems.monerokit

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncErrorTest {

    private val buildError = "failed to get output distribution"
    private val syncError = "no connection to daemon"

    @Test
    fun errorOfTheSync_isTheSyncs() {
        val about = statusError(syncError, buildingTransfer = false, transferError = null, sendInProgress = false)

        assertEquals(StatusError.OfTheSync, about)
    }

    @Test
    fun errorLeftByATransferThatWasNotBuilt_isTheTransfers() {
        val about = statusError(buildError, buildingTransfer = false, transferError = buildError, sendInProgress = false)

        assertEquals(StatusError.OfATransfer, about)
    }

    @Test
    fun errorSeenWhileATransferIsBeingBuilt_isTheTransfers() {
        val about = statusError(buildError, buildingTransfer = true, transferError = null, sendInProgress = true)

        assertEquals(StatusError.OfATransfer, about)
    }

    @Test
    fun errorOfTheSyncAfterATransferThatWasNotBuilt_isTheSyncs() {
        val about = statusError(syncError, buildingTransfer = false, transferError = buildError, sendInProgress = false)

        assertEquals(StatusError.OfTheSync, about)
    }

    @Test
    fun errorOfTheSyncDuringASend_isHeldBack() {
        val about = statusError(syncError, buildingTransfer = false, transferError = null, sendInProgress = true)

        assertEquals(StatusError.HeldBack, about)
    }
}
