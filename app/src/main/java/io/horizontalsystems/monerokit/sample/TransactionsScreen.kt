package io.horizontalsystems.monerokit.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.monerokit.model.TransactionInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Long.toMonero(): String {
    val monero = this / 1_000_000_000_000.0
    return "%.12f".format(Locale.US, monero).trimEnd('0').trimEnd('.') + " XMR"
}

@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val transactions = uiState.transactions

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            if (uiState.isLoading && transactions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Error: ${uiState.errorMessage}", color = Color.Red)
                }
            } else if (transactions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No transactions found.")
                }
            } else {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    items(transactions, key = { it.hash }) { transaction ->
                        TransactionRow(transaction = transaction)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionRow(transaction: TransactionInfo) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = if (transaction.direction == TransactionInfo.Direction.Direction_In) "Received" else "Sent",
                color = if (transaction.direction == TransactionInfo.Direction.Direction_In) Color.Green else Color.Red,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            Text("Hash: ${transaction.hash}", fontSize = 12.sp, maxLines = 1, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Amount:", fontWeight = FontWeight.SemiBold)
                Text(transaction.amount.toMonero(), fontWeight = FontWeight.SemiBold)
            }
            if (transaction.fee > 0) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Fee:")
                    Text(transaction.fee.toMonero())
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            Text("Date: ${dateFormat.format(Date(transaction.timestamp * 1000))}", fontSize = 12.sp)
            Text("Block: ${transaction.blockheight}", fontSize = 12.sp)
            Text("Confirmations: ${transaction.confirmations}", fontSize = 12.sp)

            if (transaction.isPending) {
                Text("Status: Pending", color = MaterialTheme.colorScheme.tertiary)
            } else if (transaction.isFailed) {
                Text("Status: Failed", color = Color.Red)
            } else {
                Text("Status: Confirmed", color = Color.Green)
            }
        }
    }
}
