package com.plosap.myapp

import org.junit.Assert.*
import org.junit.Test

class BlousabWifiAwareCryptoStoreTest {
    private fun storePair(): Pair<BlousabWifiAwareCryptoStore, BlousabWifiAwareCryptoStore> {
        val a = BlousabWifiAwareCryptoStore()
        val b = BlousabWifiAwareCryptoStore()
        val aSend = ByteArray(32) { (it + 1).toByte() }
        val bSend = ByteArray(32) { (it + 33).toByte() }
        val aPrefix = byteArrayOf(1, 2, 3, 4)
        val bPrefix = byteArrayOf(5, 6, 7, 8)
        a.install("user-b", "user-a", aSend, bSend, aPrefix, bPrefix)
        b.install("user-a", "user-b", bSend, aSend, bPrefix, aPrefix)
        return a to b
    }

    @Test fun roundTripAndCounterAdvance() {
        val (a, b) = storePair()
        val frame = a.encrypt("user-b", "hello")
        assertEquals("hello", b.decrypt("user-a", frame))
        val second = a.encrypt("user-b", "again")
        assertEquals("again", b.decrypt("user-a", second))
    }

    @Test fun replayIsRejected() {
        val (a, b) = storePair()
        val frame = a.encrypt("user-b", "once")
        assertEquals("once", b.decrypt("user-a", frame))
        try {
            b.decrypt("user-a", frame)
            fail("Replay must be rejected")
        } catch (_: SecurityException) { }
    }

    @Test fun tamperingIsRejectedWithoutAdvancingReceiveCounter() {
        val (a, b) = storePair()
        val frame = a.encrypt("user-b", "auth")
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        try {
            b.decrypt("user-a", frame)
            fail("Tampered frame must be rejected")
        } catch (_: javax.crypto.AEADBadTagException) { }
        val valid = a.encrypt("user-b", "next")
        assertEquals("next", b.decrypt("user-a", valid))
    }

    @Test fun replacementRequiresExplicitRemoval() {
        val (a, _) = storePair()
        try {
            a.install("user-b", "user-a", ByteArray(32), ByteArray(32), ByteArray(4), ByteArray(4))
            fail("Silent key replacement must be rejected")
        } catch (_: IllegalStateException) { }
        a.remove("peer-b")
        a.install("user-b", "user-a", ByteArray(32), ByteArray(32), ByteArray(4), ByteArray(4))
    }
}
