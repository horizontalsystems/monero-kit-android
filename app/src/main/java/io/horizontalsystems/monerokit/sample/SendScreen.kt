package io.horizontalsystems.monerokit.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    sendViewModel: SendViewModel = viewModel()
) {
    val uiState by sendViewModel.uiState.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val amountFocusRequester = remember { FocusRequester() }
    val addressFocusRequester = remember { FocusRequester() }
    val memoFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        amountFocusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Send Monero") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            OutlinedTextField(
                value = uiState.amountString,
                onValueChange = { sendViewModel.onAmountChange(it) },
                label = { Text("Amount (XMR)") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                isError = uiState.amountError != null,
                supportingText = {
                    val currentAmountError = uiState.amountError
                    if (currentAmountError != null) {
                        Text(currentAmountError, color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocusRequester)
            )

            OutlinedTextField(
                value = uiState.addressString,
                onValueChange = { sendViewModel.onAddressChange(it) },
                label = { Text("Recipient Address") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                isError = uiState.addressError != null,
                supportingText = {
                    val currentAddressError = uiState.addressError
                    if (currentAddressError != null) {
                        Text(currentAddressError, color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(addressFocusRequester)
            )

            OutlinedTextField(
                value = uiState.memoString,
                onValueChange = { sendViewModel.onMemoChange(it) },
                label = { Text("Memo (Optional)") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(memoFocusRequester)
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isLoadingFee) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text("Estimating fee...")
            } else if (uiState.feeError != null) {
                Text("Fee Error: ${uiState.feeError}", color = MaterialTheme.colorScheme.error)
            } else if (uiState.estimatedFee != null) {
                Text("Estimated Fee: ${uiState.estimatedFee}")
            }

            Spacer(modifier = Modifier.weight(1f))

            val currentSendError = uiState.sendError
            if (currentSendError != null) {
                Text(currentSendError, color = MaterialTheme.colorScheme.error)
            }
            if (uiState.sendSuccess) {
                Text("Transaction sent successfully!", color = Color.Green)
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    sendViewModel.sendTransaction()
                },
                enabled = uiState.canSend && !uiState.isSending,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending...")
                } else {
                    Text("Send Monero")
                }
            }
        }
    }
}
