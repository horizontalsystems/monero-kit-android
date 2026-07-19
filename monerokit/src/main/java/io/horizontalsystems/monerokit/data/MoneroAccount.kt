package io.horizontalsystems.monerokit.data

import io.horizontalsystems.monerokit.Balance

data class MoneroAccount(
    val index: Int,
    val label: String,
    val balance: Balance,
)
