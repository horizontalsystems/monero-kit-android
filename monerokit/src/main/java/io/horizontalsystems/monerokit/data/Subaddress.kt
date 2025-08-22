package io.horizontalsystems.monerokit.data

import java.util.regex.Pattern

data class Subaddress(
    val accountIndex: Int,
    val addressIndex: Int,
    val address: String,
    val label: String
) : Comparable<Subaddress> {
    var amount: Long = 0
    var txsCount: Long = 0

    override fun compareTo(other: Subaddress): Int {
        val compareAccountIndex = other.accountIndex - accountIndex
        return if (compareAccountIndex == 0) {
            other.addressIndex - addressIndex
        } else {
            compareAccountIndex
        }
    }

    val squashedAddress: String
        get() = if (address.length > 16) { // Ensure address is long enough
            "${address.substring(0, 8)}…${address.substring(address.length - 8)}"
        } else {
            address // Or return the original address if too short
        }

    val displayLabel: String
        get() = if (label.isEmpty() || DEFAULT_LABEL_FORMATTER.matcher(label).matches()) {
            "#$addressIndex"
        } else {
            label
        }

    companion object {
        @JvmField // Expose as a static field for Java compatibility if needed
        val DEFAULT_LABEL_FORMATTER: Pattern = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}:[0-9]{2}:[0-9]{2}$")
    }
}
