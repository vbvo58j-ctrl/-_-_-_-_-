package com.plosap.myapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
import android.bluetooth.le.AdvertiseSettings.ADVERTISE_TX_POWER_LOW
import android.os.ParcelUuid
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.os.Handler
import android.os.Looper
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Binder
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.UUID
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.core.app.NotificationCompat

/**
 * Foreground runtime host. It owns the RFCOMM listener and authenticated live
 * socket I/O and both inbound/outbound signed Blousab handshakes across Activity
 * recreation. Flutter supplies the public identity envelope and signing callback.
 */
class BlousabBackgroundService : Service() {
    companion object {
        const val CHANNEL_ID = "blousab_background_runtime"
        const val NOTIFICATION_ID = 7318
        const val ACTION_STOP = "com.plosap.myapp.action.STOP_BACKGROUND_RUNTIME"
        const val ACTION_ENABLE_DISCOVERY = "com.plosap.myapp.action.ENABLE_DISCOVERY"
        const val ACTION_DISABLE_DISCOVERY = "com.plosap.myapp.action.DISABLE_DISCOVERY"
        // Public protocol marker only; no account ID, name, key, or fingerprint is broadcast.
        val BLOUSAB_BLE_SERVICE_UUID: UUID = UUID.fromString("f0e1d2c3-b4a5-4678-9012-123456789abd")
    }

    private val runtimeBinder = RuntimeBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var bleAdvertiser: BluetoothLeAdvertiser? = null
    @Volatile private var bleAdvertiseCallback: AdvertiseCallback? = null
    @Volatile private var bleBeaconEnabled = false
    private var bluetoothStateReceiver: BroadcastReceiver? = null
    @Volatile private var wifiAwareSession: WifiAwareSession? = null
    @Volatile private var wifiPublishSession: PublishDiscoverySession? = null
    @Volatile private var wifiSubscribeSession: SubscribeDiscoverySession? = null
    @Volatile private var wifiAwareStarting = false
    @Volatile private var wifiHandshakeEngine: BlousabWifiAwareHandshakeEngine? = null
    @Volatile private var wifiDataPathEngine: BlousabWifiAwareDataPathEngine? = null
    @Volatile private var wifiVerifiedCallback: ((PeerHandle, DiscoverySession, BlousabWifiPeerIdentity, String, ByteArray, ByteArray, ByteArray, ByteArray) -> Unit)? = null
    @Volatile private var wifiHandshakeFailureCallback: ((PeerHandle, String) -> Unit)? = null
    @Volatile private var wifiPublishStartedCallback: ((PublishDiscoverySession) -> Unit)? = null
    @Volatile private var wifiSubscribeStartedCallback: ((SubscribeDiscoverySession) -> Unit)? = null
    @Volatile private var wifiServiceDiscoveredCallback: ((PeerHandle, ByteArray?, SubscribeDiscoverySession) -> Unit)? = null
    @Volatile private var wifiMessageReceivedCallback: ((PeerHandle, DiscoverySession, ByteArray) -> Unit)? = null
    @Volatile private var wifiServiceLostCallback: ((PeerHandle, Int) -> Unit)? = null
    @Volatile private var wifiErrorCallback: ((String) -> Unit)? = null

    // Phase 30: TCP framing and socket lifetime are owned by the foreground
    // service. Crypto remains supplied by the authenticated session owner so
    // this migration does not alter key derivation, AAD, or nonce counters.
    private data class WifiAwareTcpSession(
        val peerId: String,
        val peerName: String,
        val socket: java.net.Socket,
        val output: java.io.DataOutputStream,
        val encrypt: (String) -> ByteArray,
        val decrypt: (ByteArray) -> String,
        // Serializes access to the Activity-provided crypto state. The actual
        // key bytes/counters remain owned by the existing crypto object until
        // a dedicated, versioned state-transfer API is introduced.
        val cryptoGate: Any = Any(),
        val closed: AtomicBoolean = AtomicBoolean(false)
    )
    private val wifiAwareTcpSessions = ConcurrentHashMap<String, WifiAwareTcpSession>()
    private val wifiAwareCryptoStore = BlousabWifiAwareCryptoStore()
    @Volatile private var wifiAwareDataEventConsumer: ((String, String, String, String?) -> Unit)? = null
    private data class PendingWifiAwareEvent(val type: String, val peerId: String, val peerName: String, val message: String?)
    private val pendingWifiAwareEvents = ConcurrentLinkedQueue<PendingWifiAwareEvent>()
    private val maxPendingWifiAwareEvents = 100

    /** Deliver service-owned transport events to the current UI, retaining a bounded
     * queue while Activity/Flutter is detached so reconnect state is not silently lost. */
    private fun publishWifiAwareDataEvent(type: String, peerId: String, peerName: String, message: String?) {
        val consumer = wifiAwareDataEventConsumer
        if (consumer != null) {
            try { consumer.invoke(type, peerId, peerName, message); return } catch (_: Exception) {}
        }
        while (pendingWifiAwareEvents.size >= maxPendingWifiAwareEvents) pendingWifiAwareEvents.poll()
        pendingWifiAwareEvents.offer(PendingWifiAwareEvent(type, peerId, peerName, message))
    }
    private val wifiAwareTcpExecutor = Executors.newCachedThreadPool()
    private val listenerExecutor = Executors.newSingleThreadExecutor()
    private val peerIoExecutor = Executors.newCachedThreadPool()
    private data class ManagedPeer(
        val address: String,
        val userName: String,
        val socket: BluetoothSocket,
        val writer: PrintWriter,
        val encrypt: (String) -> String,
        val decrypt: (String) -> String,
        val onClosed: () -> Unit,
        val closed: AtomicBoolean = AtomicBoolean(false)
    )
    private val managedPeers = ConcurrentHashMap<String, ManagedPeer>()
    private val candidateVerifications = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var sessionEventCallback: ((Map<String, Any>) -> Unit)? = null
    // Events remain pending until Flutter acknowledges their eventId. This is
    // at-least-once delivery across Activity/engine reattachment, bounded to 200.
    private val pendingSessionEvents = java.util.LinkedHashMap<Long, Map<String, Any>>()
    // Persist only the monotonically increasing event identifier, never event payloads
    // (which may contain message plaintext or identity metadata).
    private val eventPrefs by lazy { getSharedPreferences("blousab_runtime_meta", Context.MODE_PRIVATE) }
    private val sessionEventSequence = AtomicLong(0L)
    private val serviceEpoch = UUID.randomUUID().toString()
    private val runtimeStartedAt = System.currentTimeMillis()
    private val maxPendingSessionEvents = 200
    private val serverRunning = AtomicBoolean(false)
    @Volatile private var bluetoothServerSocket: BluetoothServerSocket? = null
    @Volatile private var acceptedSocketCallback: ((BluetoothSocket) -> Unit)? = null
    @Volatile private var listenerErrorCallback: ((String) -> Unit)? = null
    private val pendingAcceptedSockets = ConcurrentLinkedQueue<BluetoothSocket>()
    private val maxPendingAcceptedSockets = 8
    @Volatile private var handshakeHello: String = ""
    @Volatile private var handshakeUserId: String = ""
    @Volatile private var transcriptSigner: ((String) -> String)? = null


    /** Configure the service-owned Wi-Fi Aware mutual handshake. Flutter provides
     * the existing signed public envelope and a narrow Ed25519 signer callback;
     * private key material never enters this service. */
    fun configureWifiAwareHandshake(
        localHello: String,
        localUserId: String,
        signer: (String) -> String,
        onVerified: (PeerHandle, DiscoverySession, BlousabWifiPeerIdentity, String, ByteArray, ByteArray, ByteArray, ByteArray) -> Unit,
        onFailure: (PeerHandle, String) -> Unit
    ) {
        require(localHello.startsWith("BLUSAB_HELLO|2|") && localUserId.isNotBlank())
        wifiHandshakeEngine?.clear()
        wifiVerifiedCallback = onVerified
        wifiHandshakeFailureCallback = onFailure
        wifiHandshakeEngine = BlousabWifiAwareHandshakeEngine(localHello, localUserId, signer,
            { peer, session, identity, passphrase, sendKey, receiveKey, sendPrefix, receivePrefix ->
                wifiVerifiedCallback?.invoke(peer, session, identity, passphrase, sendKey, receiveKey, sendPrefix, receivePrefix)
            },
            { peer, message -> wifiHandshakeFailureCallback?.invoke(peer, message) })
    }

    /** Service owns attach/publish/subscribe and the signed HELLO/IDENTITY/PROOF
     * state machine. Activity callbacks are now limited to UI and selected NDP data path. */
    fun startWifiAware(
        identityAdvertisement: String,
        publishStartedCallback: (PublishDiscoverySession) -> Unit,
        subscribeStartedCallback: (SubscribeDiscoverySession) -> Unit,
        serviceDiscoveredCallback: (PeerHandle, ByteArray?, SubscribeDiscoverySession) -> Unit,
        messageReceivedCallback: (PeerHandle, DiscoverySession, ByteArray) -> Unit,
        serviceLostCallback: (PeerHandle, Int) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (Build.VERSION.SDK_INT < 26) throw IllegalStateException("يتطلب Wi-Fi Aware أندرويد 8 أو أحدث")
        val manager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            ?: throw IllegalStateException("هذا الهاتف لا يدعم Wi-Fi Aware")
        if (!manager.isAvailable) throw IllegalStateException("Wi-Fi Aware غير متاح الآن")
        wifiPublishStartedCallback = publishStartedCallback
        wifiSubscribeStartedCallback = subscribeStartedCallback
        wifiServiceDiscoveredCallback = serviceDiscoveredCallback
        wifiMessageReceivedCallback = messageReceivedCallback
        wifiServiceLostCallback = serviceLostCallback
        wifiErrorCallback = onError
        if (wifiAwareSession != null) return true
        if (wifiAwareStarting) return true
        wifiAwareStarting = true
        val payload = identityAdvertisement.toByteArray(Charsets.UTF_8).take(220).toByteArray()
        try {
            manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                wifiAwareSession = session
                wifiAwareStarting = false
                try {
                    session.publish(
                        PublishConfig.Builder().setServiceName("blousab-nearby").setServiceSpecificInfo(payload).build(),
                        object : DiscoverySessionCallback() {
                            override fun onPublishStarted(discoverySession: PublishDiscoverySession) {
                                wifiPublishSession = discoverySession
                                wifiPublishStartedCallback?.invoke(discoverySession)
                            }
                            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                                routeWifiAwareMessage(peerHandle, discoverySessionOrPublish(), message)
                            }
                            override fun onSessionConfigFailed() { failWifiAware("فشل بدء بث هوية بلوساب عبر Wi-Fi Aware") }
                        }, mainHandler
                    )
                    session.subscribe(
                        SubscribeConfig.Builder().setServiceName("blousab-nearby").build(),
                        object : DiscoverySessionCallback() {
                            override fun onSubscribeStarted(discoverySession: SubscribeDiscoverySession) {
                                wifiSubscribeSession = discoverySession
                                wifiSubscribeStartedCallback?.invoke(discoverySession)
                            }
                            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                                val active = wifiSubscribeSession ?: return
                                wifiServiceDiscoveredCallback?.invoke(peerHandle, serviceSpecificInfo, active)
                                val raw = try { serviceSpecificInfo?.toString(Charsets.UTF_8) ?: "" } catch (_: Exception) { "" }
                                if (raw.startsWith("BLUSAB2|")) {
                                    val fields = raw.split("|", limit = 3)
                                    if (fields.size == 3 && fields[1].isNotBlank() && fields[2].matches(Regex("[0-9a-f]{64}")))
                                        wifiHandshakeEngine?.start(peerHandle, active, fields[1], fields[2])
                                }
                            }
                            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                                val active = wifiSubscribeSession ?: return
                                routeWifiAwareMessage(peerHandle, active, message)
                            }
                            override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
                                wifiHandshakeEngine?.remove(peerHandle)
                                wifiServiceLostCallback?.invoke(peerHandle, reason)
                            }
                            override fun onSessionConfigFailed() { failWifiAware("فشل بدء البحث عن مستخدمي بلوساب عبر Wi-Fi Aware") }
                        }, mainHandler
                    )
                } catch (e: Exception) {
                    failWifiAware(e.message ?: "تعذر بدء جلسات Wi-Fi Aware")
                }
            }
            override fun onAttachFailed() { failWifiAware("فشل تهيئة Wi-Fi Aware على هذا الهاتف") }
            }, mainHandler)
        } catch (e: Exception) {
            wifiAwareStarting = false
            throw e
        }
        return true
    }

    /** Rebind UI event delivery after Activity/Flutter recreation without moving
     * the live socket or its reader thread back into the Activity. */
    /** Drop Activity-bound callbacks without closing service-owned discovery/NDP/TCP resources. */
    @Synchronized
    fun detachWifiAwareActivityCallbacks() {
        wifiPublishStartedCallback = null
        wifiSubscribeStartedCallback = null
        wifiServiceDiscoveredCallback = null
        wifiMessageReceivedCallback = null
        wifiServiceLostCallback = null
        wifiErrorCallback = null
        wifiVerifiedCallback = null
        wifiHandshakeFailureCallback = null
        wifiDataEventConsumer = null
    }

    @Synchronized
    fun attachWifiAwareDataEventConsumer(consumer: (String, String, String, String?) -> Unit) {
        wifiAwareDataEventConsumer = consumer
        while (true) {
            val event = pendingWifiAwareEvents.poll() ?: break
            try { consumer.invoke(event.type, event.peerId, event.peerName, event.message) }
            catch (_: Exception) {
                pendingWifiAwareEvents.offer(event)
                break
            }
        }
    }

    @Synchronized
    fun detachWifiAwareDataEventConsumer() {
        wifiAwareDataEventConsumer = null
    }

    /** Install verified directional keys into service-owned storage. The caller
     * must erase its handshake buffers after this method returns. */
    fun installWifiAwareCrypto(peerId: String, localUserId: String, sendKey: ByteArray,
        receiveKey: ByteArray, sendPrefix: ByteArray, receivePrefix: ByteArray) {
        wifiAwareCryptoStore.install(peerId, localUserId, sendKey, receiveKey, sendPrefix, receivePrefix)
    }

    fun encryptWifiAwarePacket(peerId: String, payload: String): ByteArray =
        wifiAwareCryptoStore.encrypt(peerId, payload)

    fun decryptWifiAwarePacket(peerId: String, frame: ByteArray): String =
        wifiAwareCryptoStore.decrypt(peerId, frame)

    fun removeWifiAwareCrypto(peerId: String) { wifiAwareCryptoStore.remove(peerId) }

    fun adoptWifiAwareTcpSocket(
        peerId: String,
        peerName: String,
        socket: java.net.Socket,
        maxPacketBytes: Int,
        encrypt: (String) -> ByteArray,
        decrypt: (ByteArray) -> String
    ) {
        require(peerId.isNotBlank() && maxPacketBytes in 1024..(4 * 1024 * 1024))
        check(!isDestroyed) { "Background service is stopping" }
        socket.tcpNoDelay = true
        socket.keepAlive = true
        closeWifiAwareTcpSocket(peerId)
        val session = WifiAwareTcpSession(
            peerId, peerName, socket, java.io.DataOutputStream(socket.getOutputStream()), encrypt, decrypt
        )
        wifiAwareTcpSessions[peerId] = session
        publishWifiAwareDataEvent("dataConnected", peerId, peerName, "اتصل الجهاز عبر Wi-Fi Aware")
        wifiAwareTcpExecutor.execute {
            try {
                val input = java.io.DataInputStream(socket.getInputStream())
                while (!session.closed.get() && !socket.isClosed) {
                    val packetLength = input.readInt()
                    if (packetLength <= 0 || packetLength > maxPacketBytes) {
                        throw java.io.IOException("حجم حزمة Wi-Fi Aware غير صالح")
                    }
                    val packet = ByteArray(packetLength)
                    input.readFully(packet)
                    val clearPacket = synchronized(session.cryptoGate) {
                        check(!session.closed.get()) { "Wi-Fi Aware session was closed" }
                        session.decrypt(packet)
                    }
                    if (!session.closed.get()) {
                        publishWifiAwareDataEvent("dataPacket", peerId, peerName, clearPacket)
                    }
                }
            } catch (e: Exception) {
                if (!session.closed.get() && !socket.isClosed) {
                    publishWifiAwareDataEvent("dataDisconnected", peerId, peerName,
                        e.message ?: "انقطع اتصال Wi-Fi Aware")
                }
            } finally {
                if (wifiAwareTcpSessions[peerId] === session) {
                    closeWifiAwareTcpSocket(peerId)
                } else {
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    fun sendWifiAwareTcpPacket(peerId: String, payload: String, maxPacketBytes: Int) {
        val session = wifiAwareTcpSessions[peerId]
            ?: throw IllegalStateException("لا توجد قناة Wi-Fi Aware جاهزة لهذا الجهاز")
        synchronized(session.output) {
            check(!session.closed.get() && !session.socket.isClosed) { "قناة Wi-Fi Aware مغلقة" }
            val bytes = synchronized(session.cryptoGate) {
                check(!session.closed.get()) { "قناة Wi-Fi Aware مغلقة" }
                session.encrypt(payload)
            }
            if (bytes.isEmpty() || bytes.size > maxPacketBytes) {
                throw IllegalArgumentException("حجم الحزمة المشفرة يتجاوز الحد المسموح")
            }
            session.output.writeInt(bytes.size)
            session.output.write(bytes)
            session.output.flush()
        }
    }

    fun closeWifiAwareTcpSocket(peerId: String) {
        val session = wifiAwareTcpSessions.remove(peerId) ?: return
        session.closed.set(true)
        try { session.output.close() } catch (_: Exception) {}
        try { session.socket.close() } catch (_: Exception) {}
    }

    private fun closeAllWifiAwareTcpSockets() {
        // Keep the executor reusable: users may stop and restart Wi-Fi Aware
        // without destroying the foreground service.
        wifiAwareTcpSessions.keys.toList().forEach { closeWifiAwareTcpSocket(it) }
    }

    @android.annotation.TargetApi(29)
    fun startWifiAwareServerDataPath(
        peerId: String, peerHandle: PeerHandle, passphrase: String,
        onSocket: (java.net.Socket) -> Unit,
        onUnavailable: () -> Unit, onLost: () -> Unit, onError: (String) -> Unit
    ) {
        val session = wifiPublishSession ?: throw IllegalStateException("جلسة نشر Wi-Fi Aware غير جاهزة")
        val engine = wifiDataPathEngine ?: BlousabWifiAwareDataPathEngine(this).also { wifiDataPathEngine = it }
        engine.startServer(peerId, peerHandle, session, passphrase, onSocket, onUnavailable, onLost, onError)
    }

    @android.annotation.TargetApi(29)
    fun startWifiAwareClientDataPath(
        peerId: String, peerHandle: PeerHandle, passphrase: String,
        onSocket: (java.net.Socket) -> Unit,
        onUnavailable: () -> Unit, onLost: () -> Unit, onError: (String) -> Unit
    ) {
        val session = wifiSubscribeSession ?: throw IllegalStateException("جلسة اشتراك Wi-Fi Aware غير جاهزة")
        val engine = wifiDataPathEngine ?: BlousabWifiAwareDataPathEngine(this).also { wifiDataPathEngine = it }
        engine.startClient(peerId, peerHandle, session, passphrase, onSocket, onUnavailable, onLost, onError)
    }

    fun closeWifiAwareDataPath(peerId: String) {
        wifiDataPathEngine?.close(peerId)
    }

    private fun routeWifiAwareMessage(
        peer: PeerHandle,
        session: DiscoverySession,
        message: ByteArray
    ) {
        val text = try { String(message, Charsets.UTF_8) } catch (_: Exception) { "" }
        if (text.startsWith("BLUSAB_WIFI_")) {
            wifiHandshakeEngine?.onMessage(peer, session, message)
                ?: wifiHandshakeFailureCallback?.invoke(peer, "منسق مصافحة Wi-Fi Aware غير مهيأ")
        } else {
            wifiMessageReceivedCallback?.invoke(peer, session, message)
        }
    }

    private fun discoverySessionOrPublish(): DiscoverySession =
        wifiPublishSession ?: throw IllegalStateException("جلسة نشر Wi-Fi Aware غير جاهزة")

    private fun failWifiAware(message: String) {
        wifiAwareStarting = false
        val callback = wifiErrorCallback
        stopWifiAware()
        callback?.invoke(message)
    }

    fun stopWifiAware() {
        wifiAwareStarting = false
        closeAllWifiAwareTcpSockets()
        wifiHandshakeEngine?.clear()
        wifiHandshakeEngine = null
        wifiDataPathEngine?.closeAll()
        wifiDataPathEngine = null
        try { wifiPublishSession?.close() } catch (_: Exception) {}
        try { wifiSubscribeSession?.close() } catch (_: Exception) {}
        wifiPublishSession = null
        wifiSubscribeSession = null
        try { wifiAwareSession?.close() } catch (_: Exception) {}
        wifiAwareSession = null
        detachWifiAwareActivityCallbacks()
    }

    /** Flutter supplies only the already-signed public identity envelope and a
     * narrow signing callback. The service owns socket protocol I/O and checks. */
    fun configureBluetoothHandshake(localHello: String, localUserId: String, signer: (String) -> String) {
        require(localHello.startsWith("BLUSAB_HELLO|2|") && localUserId.isNotBlank())
        handshakeHello = localHello
        handshakeUserId = localUserId
        transcriptSigner = signer
        drainPendingSockets()
    }

    fun clearBluetoothHandshake() {
        transcriptSigner = null
        handshakeHello = ""
        handshakeUserId = ""
    }

    @Synchronized
    fun authenticateIncomingSocket(socket: BluetoothSocket) {
        if (handshakeHello.isBlank() || transcriptSigner == null) {
            while (pendingAcceptedSockets.size >= maxPendingAcceptedSockets) {
                try { pendingAcceptedSockets.poll()?.close() } catch (_: Exception) {}
            }
            pendingAcceptedSockets.offer(socket)
            return
        }
        authenticateAndAdopt(socket)
    }

    /** Verify a nearby RFCOMM candidate with the shared handshake in discovery-only
     * mode. This never adopts the socket as a chat session and always closes it. */
    fun verifyDiscoveryCandidate(address: String, onComplete: (Boolean, String?) -> Unit) {
        if (address.isBlank()) {
            onComplete(false, "عنوان Bluetooth غير صالح")
            return
        }
        if (handshakeHello.isBlank() || transcriptSigner == null) {
            onComplete(false, "هوية بلوساب أو خدمة التوقيع غير جاهزة")
            return
        }
        if (!candidateVerifications.add(address)) {
            onComplete(false, "يجري التحقق من هذا المستخدم بالفعل")
            return
        }
        try {
            peerIoExecutor.execute {
                var socket: BluetoothSocket? = null
                var crypto: BlousabBluetoothHandshake.SessionCrypto? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                        ?: throw IllegalStateException("هذا الجهاز لا يدعم البلوتوث")
                    if (!adapter.isEnabled) throw IllegalStateException("شغّل البلوتوث أولاً")
                    try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                    val device = adapter.getRemoteDevice(address)
                    val candidate = device.createRfcommSocketToServiceRecord(
                        UUID.fromString("f0e1d2c3-b4a5-4678-9012-123456789abc")
                    )
                    socket = candidate
                    candidate.connect()
                    candidate.soTimeout = 15000
                    val writer = PrintWriter(java.io.OutputStreamWriter(candidate.outputStream, Charsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(candidate.inputStream, Charsets.UTF_8))
                    val signer = transcriptSigner ?: throw IllegalStateException("خدمة توقيع هوية بلوساب غير جاهزة")
                    val authenticated = BlousabBluetoothHandshake(handshakeHello, handshakeUserId, signer)
                        .authenticate(writer, reader, localDiscoveryOnly = true)
                    crypto = authenticated.crypto
                    val peer = authenticated.identity
                    emitSessionEvent(mapOf(
                        "type" to "blousabVerified", "address" to address,
                        "userId" to peer.userId, "userName" to peer.userName,
                        "profilePhoto" to peer.photo, "publicKey" to peer.publicKey,
                        "discoveryOnly" to true
                    ))
                    crypto.destroy()
                    crypto = null
                    onComplete(true, null)
                } catch (e: Exception) {
                    crypto?.destroy()
                    val message = e.message ?: "تعذر التحقق من هوية مستخدم بلوساب"
                    emitSessionEvent(mapOf("type" to "candidateVerificationFailed", "address" to address, "message" to message))
                    onComplete(false, message)
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                    candidateVerifications.remove(address)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            candidateVerifications.remove(address)
            onComplete(false, "خدمة Bluetooth غير متاحة حالياً")
        }
    }

    /** Outgoing RFCOMM uses the exact same service-owned handshake and session
     * adoption path as accepted sockets. The Activity never handles chat frames. */
    fun connectAuthenticatedPeer(address: String, onComplete: (Boolean, String?) -> Unit) {
        if (handshakeHello.isBlank() || transcriptSigner == null) {
            onComplete(false, "هوية بلوساب أو خدمة التوقيع غير جاهزة")
            return
        }
        try {
            peerIoExecutor.execute {
                var socket: BluetoothSocket? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                        ?: throw IllegalStateException("هذا الجهاز لا يدعم البلوتوث")
                    if (!adapter.isEnabled) throw IllegalStateException("شغّل البلوتوث أولاً")
                    try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                    val device = adapter.getRemoteDevice(address)
                    val connectedSocket = device.createRfcommSocketToServiceRecord(
                        UUID.fromString("f0e1d2c3-b4a5-4678-9012-123456789abc")
                    )
                    socket = connectedSocket
                    connectedSocket.connect()
                    authenticateAndAdopt(connectedSocket) { ok, message -> onComplete(ok, message) }
                } catch (e: Exception) {
                    try { socket?.close() } catch (_: Exception) {}
                    val message = e.message ?: "تعذر الاتصال بمستخدم بلوساب"
                    emitSessionEvent(mapOf("type" to "connectionError", "address" to address, "message" to message))
                    onComplete(false, message)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false, "خدمة Bluetooth غير متاحة حالياً")
        }
    }

    private fun authenticateAndAdopt(socket: BluetoothSocket, onComplete: ((Boolean, String?) -> Unit)? = null) {
        try {
            peerIoExecutor.execute {
                var crypto: BlousabBluetoothHandshake.SessionCrypto? = null
                val address = try { socket.remoteDevice.address } catch (_: Exception) { "" }
                try {
                    val hello = handshakeHello
                    val userId = handshakeUserId
                    val signer = transcriptSigner ?: throw IllegalStateException("خدمة توقيع هوية بلوساب غير جاهزة")
                    if (hello.isBlank() || userId.isBlank()) throw IllegalStateException("هوية بلوساب المحلية غير مهيأة")
                    socket.soTimeout = 15000
                    val writer = PrintWriter(java.io.OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)
                    val reader = BufferedReader(java.io.InputStreamReader(socket.inputStream, Charsets.UTF_8))
                    val authenticated = BlousabBluetoothHandshake(hello, userId, signer).authenticate(writer, reader)
                    val peer = authenticated.identity
                    if (peer.discoveryOnly) {
                        emitSessionEvent(mapOf("type" to "blousabVerified", "address" to address,
                            "userId" to peer.userId, "userName" to peer.userName,
                            "profilePhoto" to peer.photo, "publicKey" to peer.publicKey, "discoveryOnly" to true))
                        authenticated.crypto.destroy()
                        socket.close()
                        onComplete?.invoke(false, "تم التحقق من مستخدم بلوساب بوضع الاكتشاف فقط؛ اختر اتصالاً عادياً")
                        return@execute
                    }
                    crypto = authenticated.crypto
                    socket.soTimeout = 0
                    emitSessionEvent(mapOf("type" to "blousabVerified", "address" to address,
                        "userId" to peer.userId, "userName" to peer.userName,
                        "profilePhoto" to peer.photo, "publicKey" to peer.publicKey))
                    emitSessionEvent(mapOf("type" to "connected", "address" to address, "userName" to peer.userName))
                    val sessionCrypto = crypto
                    crypto = null
                    adoptAuthenticatedPeer(address, peer.userName, socket, reader, writer,
                        encrypt = { clear -> sessionCrypto!!.encrypt(clear) },
                        decrypt = { frame -> sessionCrypto!!.decrypt(frame) },
                        onClosed = { sessionCrypto!!.destroy() })
                    onComplete?.invoke(true, null)
                } catch (e: Exception) {
                    crypto?.destroy()
                    try { socket.close() } catch (_: Exception) {}
                    val message = e.message ?: "لم تثبت هوية بلوساب؛ تم تجاهل الجهاز"
                    emitSessionEvent(mapOf("type" to "unverifiedDevice", "address" to address, "message" to message))
                    onComplete?.invoke(false, message)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { socket.close() } catch (_: Exception) {}
            onComplete?.invoke(false, "خدمة Bluetooth غير متاحة حالياً")
        }
    }

    private fun deliverOrQueue(socket: BluetoothSocket) {
        val callback = acceptedSocketCallback
        if (callback != null) {
            try { callback.invoke(socket) } catch (_: Exception) { try { socket.close() } catch (_: Exception) {} }
            return
        }
        if (handshakeHello.isNotBlank() && transcriptSigner != null) {
            authenticateIncomingSocket(socket)
            return
        }
        // Activity/Flutter may be recreating. Keep only a small bounded queue;
        // never silently turn an unowned socket into an authenticated peer.
        while (pendingAcceptedSockets.size >= maxPendingAcceptedSockets) {
            try { pendingAcceptedSockets.poll()?.close() } catch (_: Exception) {}
        }
        pendingAcceptedSockets.offer(socket)
    }

    @Synchronized
    private fun drainPendingSockets() {
        val callback = acceptedSocketCallback ?: return
        while (true) {
            val socket = pendingAcceptedSockets.poll() ?: break
            try { callback.invoke(socket) } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    /** Called when a live Activity/Flutter host is available to authenticate peers. */
    @Synchronized
    fun attachBluetoothListenerConsumer(
        onAccepted: (BluetoothSocket) -> Unit,
        onError: (String) -> Unit
    ) {
        acceptedSocketCallback = onAccepted
        listenerErrorCallback = onError
        drainPendingSockets()
    }

    /** Detach the Activity callback without stopping the service-owned RFCOMM listener. */
    @Synchronized
    fun detachBluetoothListenerConsumer() {
        acceptedSocketCallback = null
        listenerErrorCallback = null
        // Accepted sockets remain queued until the service has a current identity signer.
    }


    /** Attach the current Flutter host to events from service-owned live sessions. */
    @Synchronized
    fun attachSessionEventConsumer(onEvent: (Map<String, Any>) -> Unit) {
        sessionEventCallback = onEvent
        // Do not remove events here. The Dart event stream acknowledges each
        // event only after it has been handed to its broadcast listeners.
        pendingSessionEvents.values.toList().forEach { event ->
            try { onEvent.invoke(event) } catch (_: Exception) { /* retained for retry */ }
        }
    }

    @Synchronized
    fun acknowledgeSessionEvent(eventId: Long): Boolean =
        pendingSessionEvents.remove(eventId) != null

    @Synchronized
    fun detachSessionEventConsumer() {
        sessionEventCallback = null
    }

    @Synchronized
    private fun emitSessionEvent(event: Map<String, Any>) {
        val eventId = sessionEventSequence.incrementAndGet()
        check(eventPrefs.edit().putLong("last_event_id", eventId).commit()) { "تعذر حفظ رقم حدث الخدمة" }
        val envelope = HashMap(event)
        envelope["eventId"] = eventId
        envelope["serviceEpoch"] = serviceEpoch
        pendingSessionEvents[eventId] = envelope
        // Bound memory use. On overflow discard the oldest unacknowledged event;
        // the durable encrypted outbox remains the source of truth for messages.
        while (pendingSessionEvents.size > maxPendingSessionEvents) {
            val oldest = pendingSessionEvents.keys.firstOrNull() ?: break
            pendingSessionEvents.remove(oldest)
        }
        val callback = sessionEventCallback
        if (callback != null) {
            try { callback.invoke(envelope) } catch (_: Exception) { /* retry on reattach */ }
        }
    }

    /**
     * Transfer an already authenticated RFCOMM session into the foreground service.
     * Authentication and key derivation happen before this call in the existing
     * signed Blousab handshake. The service only owns encrypted frame I/O.
     */
    fun adoptAuthenticatedPeer(
        address: String,
        userName: String,
        socket: BluetoothSocket,
        reader: BufferedReader,
        writer: PrintWriter,
        encrypt: (String) -> String,
        decrypt: (String) -> String,
        onClosed: () -> Unit
    ) {
        val session = ManagedPeer(address, userName, socket, writer, encrypt, decrypt, onClosed)
        val previous = managedPeers.put(address, session)
        if (previous != null && previous.socket !== socket) closeManagedPeer(previous, false)
        try {
            peerIoExecutor.execute {
            try {
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    val clear = session.decrypt.invoke(line)
                    emitSessionEvent(mapOf(
                        "type" to "message", "address" to address,
                        "userName" to userName, "message" to clear
                    ))
                }
            } catch (e: Exception) {
                if (!socket.isClosed) emitSessionEvent(mapOf(
                    "type" to "connectionError", "address" to address,
                    "message" to (e.message ?: "انقطع الاتصال")
                ))
            } finally {
                if (managedPeers[address] === session) {
                    managedPeers.remove(address, session)
                    closeManagedPeer(session, true)
                } else {
                    closeManagedPeer(session, false)
                }
            }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            managedPeers.remove(address, session)
            closeManagedPeer(session, false)
            throw e
        }
    }

    private fun closeManagedPeer(peer: ManagedPeer, notify: Boolean) {
        if (!peer.closed.compareAndSet(false, true)) return
        try { peer.socket.close() } catch (_: Exception) {}
        try { peer.onClosed.invoke() } catch (_: Exception) {}
        if (notify) emitSessionEvent(mapOf("type" to "disconnected", "address" to peer.address))
    }

    fun sendAuthenticatedMessage(address: String, message: String): Int {
        val targets = if (address == "*") managedPeers.values.toList()
            else listOfNotNull(managedPeers[address])
        if (targets.isEmpty()) throw IllegalStateException("لا توجد جلسة Bluetooth موثقة")
        var sent = 0
        var lastError: Exception? = null
        for (peer in targets) {
            try {
                synchronized(peer.writer) {
                    peer.writer.println(peer.encrypt.invoke(message))
                    peer.writer.flush()
                    if (peer.writer.checkError()) throw java.io.IOException("فشل الإرسال")
                }
                sent++
            } catch (e: Exception) {
                lastError = e
                closeManagedPeer(peer, true)
                managedPeers.remove(peer.address, peer)
            }
        }
        if (sent == 0) throw (lastError ?: java.io.IOException("فشل إرسال الرسالة"))
        return sent
    }

    fun closeAuthenticatedPeer(address: String? = null) {
        val peers = if (address.isNullOrBlank()) managedPeers.values.toList()
            else listOfNotNull(managedPeers[address])
        peers.forEach { peer ->
            managedPeers.remove(peer.address, peer)
            closeManagedPeer(peer, true)
        }
    }

    fun hasAuthenticatedPeer(address: String): Boolean = managedPeers.containsKey(address)

    fun authenticatedPeerSnapshot(): List<Map<String, Any>> = managedPeers.values.map { peer ->
        mapOf("type" to "connected", "address" to peer.address, "userName" to peer.userName)
    }

    /** Snapshot is intentionally metadata-only: no keys, passphrases, or message bodies. */
    @Synchronized
    fun runtimeSnapshot(): Map<String, Any> = mapOf(
        "serviceEpoch" to serviceEpoch,
        "startedAt" to runtimeStartedAt,
        "wifiAwareActive" to (wifiAwareSession != null),
        "wifiAwareStarting" to wifiAwareStarting,
        "wifiAwareTcpPeers" to wifiAwareTcpSessions.keys.toList(),
        "authenticatedBluetoothPeers" to authenticatedPeerSnapshot(),
        "pendingEvents" to pendingSessionEvents.size,
        "lastEventId" to sessionEventSequence.get()
    )

    /** Diagnostics for the network monitor; deliberately excludes addresses and payloads. */
    fun runtimeHealth(): Map<String, Any> = mapOf(
        "serviceEpoch" to serviceEpoch,
        "wifiAwareSession" to if (wifiAwareSession != null) "attached" else "detached",
        "wifiAwareTcpSessionCount" to wifiAwareTcpSessions.size,
        "bluetoothPeerCount" to managedPeers.size,
        "bluetoothListenerRunning" to serverRunning.get(),
        "pendingEventCount" to synchronized(this) { pendingSessionEvents.size },
        "eventDelivery" to "at-least-once-until-ack"
    )

    inner class RuntimeBinder : Binder() {
        fun service(): BlousabBackgroundService = this@BlousabBackgroundService
    }

    /** The foreground service owns the RFCOMM listener and authenticates accepted
     * sockets through the same coordinator used for outgoing connections. */
    @Synchronized
    fun startBluetoothListener(onAccepted: (BluetoothSocket) -> Unit, onError: (String) -> Unit) {
        // Refresh both callbacks even when the listener is already running. This
        // is essential after Activity/Flutter-engine recreation: the accept loop
        // remains service-owned while its consumer is replaced.
        attachBluetoothListenerConsumer(onAccepted, onError)
        if (!serverRunning.compareAndSet(false, true)) return
        listenerExecutor.execute {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IllegalStateException("هذا الجهاز لا يدعم البلوتوث")
                if (!adapter.isEnabled) throw IllegalStateException("شغّل البلوتوث أولاً")
                val listening = adapter.listenUsingRfcommWithServiceRecord(
                    "BlousabRFCOMM",
                    UUID.fromString("f0e1d2c3-b4a5-4678-9012-123456789abc")
                )
                bluetoothServerSocket = listening
                while (serverRunning.get()) {
                    val client = listening.accept()
                    deliverOrQueue(client)
                }
            } catch (e: Exception) {
                if (serverRunning.get()) listenerErrorCallback?.invoke(e.message ?: "تعذر تشغيل استقبال Bluetooth")
            } finally {
                serverRunning.set(false)
                try { bluetoothServerSocket?.close() } catch (_: Exception) {}
                bluetoothServerSocket = null
            }
        }
    }

    @Synchronized
    fun stopBluetoothListener() {
        closeAuthenticatedPeer()
        serverRunning.set(false)
        try { bluetoothServerSocket?.close() } catch (_: Exception) {}
        bluetoothServerSocket = null
        acceptedSocketCallback = null
        listenerErrorCallback = null
        while (true) {
            try { pendingAcceptedSockets.poll()?.close() ?: break } catch (_: Exception) { break }
        }
    }

    /** Advertise only the public Blousab service marker. Identity is established later
     * by the existing signed handshake; no personal or cryptographic material is exposed. */
    @Synchronized
    fun startBlousabBleBeacon() {
        bleBeaconEnabled = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (!adapter.isEnabled) return
            val advertiser = adapter.bluetoothLeAdvertiser ?: return
            if (bleAdvertiseCallback != null) return
            val serviceId = ParcelUuid(BLOUSAB_BLE_SERVICE_UUID)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(ADVERTISE_TX_POWER_LOW)
                .setConnectable(false)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(serviceId)
                .addServiceData(serviceId, byteArrayOf(0x02))
                .build()
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    bleAdvertiser = advertiser
                }
                override fun onStartFailure(errorCode: Int) {
                    bleAdvertiseCallback = null
                    bleAdvertiser = null
                }
            }
            bleAdvertiser = advertiser
            bleAdvertiseCallback = callback
            advertiser.startAdvertising(settings, data, callback)
        } catch (_: SecurityException) {
            bleAdvertiseCallback = null
            bleAdvertiser = null
        } catch (_: Exception) {
            bleAdvertiseCallback = null
            bleAdvertiser = null
        }
    }

    @Synchronized
    private fun stopBleAdvertisingOnly() {
        val callback = bleAdvertiseCallback
        val advertiser = bleAdvertiser
        if (callback != null && advertiser != null) {
            try { advertiser.stopAdvertising(callback) } catch (_: Exception) {}
        }
        bleAdvertiseCallback = null
        bleAdvertiser = null
    }

    @Synchronized
    fun stopBlousabBleBeacon() {
        bleBeaconEnabled = false
        stopBleAdvertisingOnly()
    }

    private fun registerBluetoothStateReceiver() {
        if (bluetoothStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> if (bleBeaconEnabled) startBlousabBleBeacon()
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> stopBleAdvertisingOnly()
                }
            }
        }
        bluetoothStateReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            }
        } catch (_: Exception) { bluetoothStateReceiver = null }
    }

    override fun onCreate() {
        super.onCreate()
        registerBluetoothStateReceiver()
        sessionEventSequence.set(eventPrefs.getLong("last_event_id", 0L))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISABLE_DISCOVERY ||
            (intent != null && intent.action == null && intent.hasExtra("nearbyVisible") && !intent.getBooleanExtra("nearbyVisible", false))) {
            stopBlousabBleBeacon()
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        if (intent?.action == ACTION_ENABLE_DISCOVERY ||
            (intent?.action == null && intent?.getBooleanExtra("nearbyVisible", false) == true)) {
            startBlousabBleBeacon()
        }
        // Restart the foreground host if Android recreates its process. This is
        // not a promise that transport sockets or Dart state have been restored.
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تشغيل بلوساب في الخلفية",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار استمرار خدمة بلوساب أثناء نشاطها في الخلفية"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("بلوساب يعمل في الخلفية")
            .setContentText("خدمات Bluetooth وWi-Fi Aware تعمل أثناء نشاط بلوساب")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = runtimeBinder

    override fun onDestroy() {
        stopBlousabBleBeacon()
        try { bluetoothStateReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        bluetoothStateReceiver = null
        stopBluetoothListener()
        stopWifiAware()
        clearBluetoothHandshake()
        detachSessionEventConsumer()
        detachWifiAwareDataEventConsumer()
        wifiAwareCryptoStore.clear()
        wifiAwareTcpExecutor.shutdownNow()
        peerIoExecutor.shutdownNow()
        listenerExecutor.shutdownNow()
        super.onDestroy()
    }
}
