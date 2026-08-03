package io.horizontalsystems.monerokit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneroMnemonicTest {

    private val converterSeed = CakeWalletStyleConverter.getLegacySeedFromBip39(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
    )!!

    @Test
    fun `wordlist has the canonical size`() {
        assertEquals(1626, MoneroMnemonic.wordList.size)
        assertEquals(1626, MoneroMnemonic.wordList.distinct().size)
    }

    @Test
    fun `converter output passes checksum validation`() {
        assertEquals(MoneroMnemonic.WORD_COUNT, converterSeed.size)
        assertTrue(converterSeed.all { MoneroMnemonic.isValidWord(it, partial = false) })
        MoneroMnemonic.validateChecksum(converterSeed)
    }

    @Test(expected = MoneroMnemonic.InvalidChecksumException::class)
    fun `tampered checksum word is rejected`() {
        val replacement = MoneroMnemonic.wordList.first { it != converterSeed.last() }
        MoneroMnemonic.validateChecksum(converterSeed.dropLast(1) + replacement)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `wrong word count is rejected`() {
        MoneroMnemonic.validateChecksum(converterSeed.dropLast(1))
    }

    @Test
    fun `word validity checks against the wordlist`() {
        assertTrue(MoneroMnemonic.isValidWord("abbey", partial = false))
        assertFalse(MoneroMnemonic.isValidWord("abandon", partial = false)) // BIP39-only word
        assertTrue(MoneroMnemonic.isValidWord("abb", partial = true))
        assertFalse(MoneroMnemonic.isValidWord("zzz", partial = true))
    }

    @Test
    fun `suggestions are prefix matches`() {
        val suggestions = MoneroMnemonic.suggestions("abb")
        assertTrue(suggestions.contains("abbey"))
        assertTrue(suggestions.all { it.startsWith("abb") })
        assertTrue(MoneroMnemonic.suggestions("").isEmpty())
    }
}
