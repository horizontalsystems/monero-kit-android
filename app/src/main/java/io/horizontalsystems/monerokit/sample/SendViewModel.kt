package io.horizontalsystems.monerokit.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.monerokit.MoneroKit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

data class SendUiState(
    val amountString: String = "",
    val addressString: String = "",
    val memoString: String = "",

    val amountError: String? = null,
    val addressError: String? = null,

    val estimatedFee: String? = null,
    val feeError: String? = null,
    val isLoadingFee: Boolean = false,

    val canEstimateFee: Boolean = false,
    val canSend: Boolean = false,

    val isSending: Boolean = false,
    val sendSuccess: Boolean = false,
    val sendError: String? = null
)

class SendViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(SendUiState())
    val uiState = _uiState.asStateFlow()

    private var feeEstimationJob: Job? = null
    private var kit: MoneroKit = App.kit

    init {
        _uiState
            .map { Pair(it.addressString, it.amountString) }
            .debounce(750)
            .onEach { (address, amount) ->
                if (address.isNotBlank() && amount.isNotBlank() && xmrStringToPiconero(amount) != null) {
                    val isValidAddress = try {
                        MoneroKit.validateAddress(address)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (isValidAddress) {
                        autoEstimateFee()
                    }
                }
            }
            .launchIn(viewModelScope)

        _uiState.onEach { currentState ->
            val amountLong = xmrStringToPiconero(currentState.amountString)
            val isValidAddress = currentState.addressString.isNotBlank() && currentState.addressError == null
            val isValidAmount = amountLong != null && amountLong > 0 && currentState.amountError == null

            val canEstimate = isValidAddress && isValidAmount
            val canActuallySend = canEstimate && currentState.estimatedFee != null && !currentState.isLoadingFee && currentState.feeError == null

            if (currentState.canEstimateFee != canEstimate || currentState.canSend != canActuallySend) {
                _uiState.update {
                    it.copy(
                        canEstimateFee = canEstimate,
                        canSend = canActuallySend
                    )
                }
            }
        }.launchIn(viewModelScope)
    }

    fun onAmountChange(amount: String) {
        val amountLong = xmrStringToPiconero(amount)
        _uiState.update {
            it.copy(
                amountString = amount,
                amountError = if (amount.isNotBlank() && amountLong == null) "Invalid amount" else if (amountLong != null && amountLong <= 0) "Amount too low" else null,
                estimatedFee = null,
                feeError = null,
                sendSuccess = false,
                sendError = null
            )
        }
    }

    fun onAddressChange(address: String) {
        var addressErr: String? = null
        if (address.isNotBlank()) {
            try {
                MoneroKit.validateAddress(address)
            } catch (e: Exception) {
                addressErr = "Invalid address"
            }
        }
        _uiState.update {
            it.copy(
                addressString = address,
                addressError = addressErr,
                estimatedFee = null,
                feeError = null,
                sendSuccess = false,
                sendError = null
            )
        }
    }

    fun onMemoChange(memo: String) {
        _uiState.update { it.copy(memoString = memo, sendSuccess = false, sendError = null) }
    }

    private fun autoEstimateFee() {
        if (_uiState.value.canEstimateFee) {
            estimateFee()
        }
    }

    private fun estimateFee() {
        feeEstimationJob?.cancel()
        val currentAmountString = _uiState.value.amountString
        val currentAddressString = _uiState.value.addressString

        val amountLong = xmrStringToPiconero(currentAmountString)
        if (amountLong == null || amountLong <= 0) {
            _uiState.update { it.copy(amountError = "Invalid amount", feeError = null, isLoadingFee = false) }
            return
        }

        try {
            MoneroKit.validateAddress(currentAddressString)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(addressError = "Invalid address", feeError = null, isLoadingFee = false) }
            return
        }

        _uiState.update { it.copy(isLoadingFee = true, feeError = null, estimatedFee = null) }

        feeEstimationJob = viewModelScope.launch {
            try {
                val feePiconero = kit.estimateFee(amountLong, currentAddressString, _uiState.value.memoString.ifBlank { null })
                _uiState.update {
                    it.copy(
                        estimatedFee = piconeroToXmrString(feePiconero),
                        feeError = null,
                        isLoadingFee = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(feeError = "Fee estimation failed: ${e.message}", isLoadingFee = false, estimatedFee = null) }
            }
        }
    }

    fun sendTransaction() {
        if (!_uiState.value.canSend) return

        val amountLong = xmrStringToPiconero(_uiState.value.amountString)
        if (amountLong == null || amountLong <= 0) {
            _uiState.update { it.copy(sendError = "Invalid amount") }
            return
        }

        val address = _uiState.value.addressString
        try {
            MoneroKit.validateAddress(address)
        } catch (e: IllegalArgumentException) {
            _uiState.update { it.copy(sendError = "Invalid address") }
            return
        }

        _uiState.update { it.copy(isSending = true, sendError = null, sendSuccess = false) }

        viewModelScope.launch {
            try {
                kit.send(
                    amount = amountLong,
                    address = address,
                    memo = _uiState.value.memoString.ifBlank { null }
                )
                _uiState.update {
                    it.copy(
                        isSending = false,
                        sendSuccess = true,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSending = false, sendError = "Send failed: ${e.message}") }
            }
        }
    }

    companion object {
        private val PiconeroPerXMR = BigDecimal("1000000000000") // 10^12

        fun xmrStringToPiconero(amountString: String): Long? {
            if (amountString.isBlank()) return null
            return try {
                val amountDecimal = BigDecimal(amountString)
                if (amountDecimal <= BigDecimal.ZERO) return null
                val piconeroValue = amountDecimal.multiply(PiconeroPerXMR)
                if (piconeroValue < BigDecimal(Long.MIN_VALUE) || piconeroValue > BigDecimal(Long.MAX_VALUE)) {
                    return null
                }
                piconeroValue.toLong()
            } catch (_: Exception) {
                null
            }
        }

        fun piconeroToXmrString(piconero: Long, includeTicker: Boolean = true): String {
            val amountDecimal = BigDecimal(piconero).divide(PiconeroPerXMR)
            val formatted = amountDecimal.setScale(12, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
            return if (includeTicker) "$formatted XMR" else formatted
        }
    }
}
