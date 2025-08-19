package io.horizontalsystems.monerokit.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.monerokit.MoneroKit
import io.horizontalsystems.monerokit.SyncState
import io.horizontalsystems.monerokit.model.TransactionInfo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class TransactionsViewModel : ViewModel() {

    private val kit: MoneroKit = App.kit

    val uiState: StateFlow<TransactionsUiState> = combine(
        kit.allTransactionsFlow,
        kit.syncStateFlow
    ) { transactions, syncState ->
        val isLoading = when (syncState) {
            is SyncState.Syncing -> true
            else -> false
        }
        val errorMessage = when (syncState) {
            is SyncState.NotSynced -> syncState.error.message ?: "Not Synced"
            else -> null
        }

        TransactionsUiState(
            transactions = transactions.sortedByDescending { it.timestamp },
            isLoading = isLoading,
            errorMessage = errorMessage,
            syncState = syncState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TransactionsUiState()
    )

    fun refresh() {
        kit.stop()
        kit.start()
    }
}

data class TransactionsUiState(
    val transactions: List<TransactionInfo> = listOf(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val syncState: SyncState? = null
)
