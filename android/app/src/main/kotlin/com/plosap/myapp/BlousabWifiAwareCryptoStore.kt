package com.plosap.myapp

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Service-owned Wi-Fi Aware packet crypto state.
 * Key material is copied exactly once on installation; callers must wipe their
 * source arrays after this method returns. A peer session cannot be replaced
 * silently: callers must explicitly remove the previous session first.
 */
internal class BlousabWifiAwareCryptoStore(
    private val maxPacketBytes: Int = 1024 * 1024
) {
    private data class State(
        val localUserId: String,
        val peerId: String,
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val sendPrefix: ByteArray,
        val receivePrefix: ByteArray,
        var sendCounter: Long = 0L,
        var receiveCounter: Long = 0L,
        var closed: Boolean = false
    ) {
        fun wipe() {
            closed = true
            Arrays.fill(sendKey, 0); Arrays.fill(receiveKey, 0)
            Arrays.fill(sendPrefix, 0); Arrays.fill(receivePrefix, 0)
            sendCounter = 0L; receiveCounter = 0L
        }
    }

    private val states = ConcurrentHashMap<String, State>()
    private val magic = byteArrayOf(0x42, 0x53, 0x41, 0x45) // BSAE

    @Synchronized
    fun install(peerId: String, localUserId: String, sendKey: ByteArray,
                receiveKey: ByteArray, sendPrefix: ByteArray, receivePrefix: ByteArray) {
        require(peerId.isNotBlank() && localUserId.isNotBlank())
        require(sendKey.size == 32 && receiveKey.size == 32)
        require(sendPrefix.size == 4 && receivePrefix.size == 4)
        check(!states.containsKey(peerId)) { "Crypto session already installed; remove it before rekeying" }
        states[peerId] = State(localUserId, peerId, sendKey.copyOf(), receiveKey.copyOf(),
            sendPrefix.copyOf(), receivePrefix.copyOf())
    }

    fun encrypt(peerId: String, payload: String): ByteArray {
        val state = states[peerId] ?: throw SecurityException("Wi-Fi Aware crypto session is missing")
        val clear = payload.toByteArray(Charsets.UTF_8)
        require(clear.isNotEmpty() && clear.size <= maxPacketBytes - 32)
        synchronized(state) {
            check(!state.closed)
            check(state.sendCounter < Long.MAX_VALUE) { "Nonce counter exhausted; re-handshake required" }
            val nonce = ByteBuffer.allocate(12).put(state.sendPrefix).putLong(state.sendCounter).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(state.sendKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(state.localUserId, peerId))
            val encrypted = cipher.doFinal(clear)
            state.sendCounter++
            return ByteBuffer.allocate(4 + nonce.size + encrypted.size)
                .put(magic).put(nonce).put(encrypted).array()
        }
    }

    fun decrypt(peerId: String, frame: ByteArray): String {
        require(frame.size >= 32 && frame.copyOfRange(0, 4).contentEquals(magic))
        val state = states[peerId] ?: throw SecurityException("Wi-Fi Aware crypto session is missing")
        synchronized(state) {
            check(!state.closed)
            val nonce = frame.copyOfRange(4, 16)
            val nb = ByteBuffer.wrap(nonce)
            val prefix = ByteArray(4).also { nb.get(it) }
            val counter = nb.long
            if (!prefix.contentEquals(state.receivePrefix) || counter != state.receiveCounter || counter < 0L)
                throw SecurityException("Invalid or replayed Wi-Fi Aware packet counter")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(state.receiveKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(peerId, state.localUserId))
            val clear = cipher.doFinal(frame.copyOfRange(16, frame.size))
            state.receiveCounter++
            return String(clear, Charsets.UTF_8)
        }
    }

    @Synchronized fun remove(peerId: String) { states.remove(peerId)?.let { synchronized(it) { it.wipe() } } }
    @Synchronized fun clear() { states.keys.toList().forEach { remove(it) } }

    private fun aad(senderId: String, recipientId: String): ByteArray =
        "blousab-wifi-aware-packet-v1|$senderId|$recipientId".toByteArray(Charset.forName("UTF-8"))
}
