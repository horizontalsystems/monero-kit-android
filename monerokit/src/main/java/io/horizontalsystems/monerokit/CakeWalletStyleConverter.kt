package io.horizontalsystems.monerokit

import android.util.Log
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.Mnemonic
import java.math.BigInteger

/**
 * BIP39 to Monero Legacy Converter following Cake Wallet's implementation
 *
 * This matches the exact approach used by Cake Wallet in Dart:
 * 1. Generate BIP39 seed
 * 2. Derive BIP32 key at m/44'/128'/accountIndex'/0/0
 * 3. Reduce private key with Ed25519 curve order (no Keccak hashing)
 * 4. Encode as Monero legacy mnemonic
 */
object CakeWalletStyleConverter {

    // Ed25519 curve order (same as Monero curve order)
    private val ED25519_CURVE_ORDER = BigInteger("1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED", 16)

    @Deprecated(
        message = "Use MoneroMnemonic.wordList",
        replaceWith = ReplaceWith("MoneroMnemonic.wordList"),
    )
    val MONERO_WORDLIST get() = MoneroMnemonic.wordList

    fun getLegacySeedFromBip39(
        bip39Mnemonic: List<String>,
        passphrase: String = "",
        accountIndex: Int = 0
    ): List<String>? {
        return try {
            if (bip39Mnemonic.size !in listOf(12, 18, 24)) return null

            // Step 1: Generate BIP39 seed
            val seed = Mnemonic().toSeed(bip39Mnemonic, passphrase)

            // Step 2: Derive BIP32 key at m/44'/128'/accountIndex'/0/0
            val hdWallet = HDWallet(seed, 128, HDWallet.Purpose.BIP44)
            val privateKey = hdWallet.privateKey("m/44'/128'/$accountIndex'/0/0").privKey

            // Step 3: Reduce private key with Ed25519 curve order (Cake Wallet approach)
            val spendKey = reduceECKey(privateKey.toByteArray32())

            // Step 4: Encode as Monero legacy mnemonic
            encodeMoneroMnemonic(spendKey)

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun BigInteger.toByteArray32(): ByteArray {
        val bytes = this.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.sliceArray(1..32)
            bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
            else -> throw IllegalArgumentException("BigInteger exceeds 32 bytes: ${bytes.size}")
        }
    }

    /**
     * Reduce EC key with Ed25519 curve order (Cake Wallet approach)
     * This is the key difference from Ledger - no Keccak hashing!
     */
    private fun reduceECKey(buffer: ByteArray): ByteArray {
        val bigNumber = readBytesLittleEndian(buffer)
        val result = bigNumber.mod(ED25519_CURVE_ORDER)

        // Convert back to little-endian 32-byte array
        val resultBuffer = ByteArray(32)
        var remainder = result
        for (i in 0 until 32) {
            resultBuffer[i] = (remainder.and(BigInteger.valueOf(0xff))).toByte()
            remainder = remainder.shiftRight(8)
        }
        return resultBuffer
    }

    /**
     * Read BigInt from little-endian byte array (Cake Wallet approach)
     */
    private fun readBytesLittleEndian(bytes: ByteArray): BigInteger {
        fun read(start: Int, end: Int): BigInteger {
            if (end - start <= 4) {
                var result = 0L
                for (i in end - 1 downTo start) {
                    result = result * 256 + (bytes[i].toInt() and 0xff)
                }
                return BigInteger.valueOf(result)
            }
            val mid = start + ((end - start) shr 1)
            return read(start, mid) + read(mid, end) * (BigInteger.ONE.shiftLeft((mid - start) * 8))
        }
        return read(0, bytes.size)
    }

    /**
     * Encode Monero mnemonic from spend key (following Monero's algorithm)
     */
    private fun encodeMoneroMnemonic(spendKey: ByteArray): List<String> {
        if (spendKey.size != 32) {
            throw IllegalArgumentException("Spend key must be 32 bytes")
        }

        val words = mutableListOf<String>()

        // Process spend key in 4-byte chunks, generating 3 words per chunk
        for (i in 0 until spendKey.size / 4) {
            // Read 4 bytes as little-endian uint32
            val val32 = ((spendKey[i * 4 + 0].toInt() and 0xff) shl 0) or
                    ((spendKey[i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((spendKey[i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((spendKey[i * 4 + 3].toInt() and 0xff) shl 24)

            // Convert to unsigned long to handle large values
            val val32Long = val32.toLong() and 0xFFFFFFFFL

            // Generate 3 words using Monero's algorithm
            val w1 = val32Long % MoneroMnemonic.wordList.size
            val w2 = ((val32Long / MoneroMnemonic.wordList.size) + w1) % MoneroMnemonic.wordList.size
            val w3 = (((val32Long / MoneroMnemonic.wordList.size) / MoneroMnemonic.wordList.size) + w2) % MoneroMnemonic.wordList.size

            words.add(MoneroMnemonic.wordList[w1.toInt()])
            words.add(MoneroMnemonic.wordList[w2.toInt()])
            words.add(MoneroMnemonic.wordList[w3.toInt()])
        }

        // Add checksum word (25th word) - selected from first 24 words
        words.add(MoneroMnemonic.checksumWord(words))

        return words
    }

}
