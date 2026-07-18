package io.horizontalsystems.monerokit.data

data class MoneroOutput(
    val keyImage: String,
    val amount: Long,
    val txHash: String,
    val subaddressIndex: Int,
    val blockHeight: Long,
    val frozen: Boolean,
    val unlocked: Boolean,
)
