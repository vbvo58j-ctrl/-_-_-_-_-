package com.plosap.myapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns Wi-Fi Aware NDP requests and their TCP endpoint resources independently
 * of MainActivity. The caller remains the policy/UI consumer of resulting sockets. */
@android.annotation.TargetApi(29)
internal class BlousabWifiAwareDataPathEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val callbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()
    private val servers = ConcurrentHashMap<String, ServerSocket>()
    private val opening = ConcurrentHashMap.newKeySet<String>()
    private val stopped = AtomicBoolean(false)

    fun startServer(
        peerId: String,
        peerHandle: PeerHandle,
        session: PublishDiscoverySession,
        passphrase: String,
        onSocket: (Socket) -> Unit,
        onPathUnavailable: () -> Unit,
        onPathLost: () -> Unit,
        onError: (String) -> Unit
    ) {
        check(Build.VERSION.SDK_INT >= 29) { "Wi-Fi Aware data path requires Android 10+" }
        if (stopped.get()) throw IllegalStateException("Wi-Fi Aware data path engine is stopped")
        if (callbacks.containsKey(peerId)) return
        val server = ServerSocket(0)
        server.reuseAddress = true
        servers[peerId] = server
        io.execute {
            try {
                val socket = server.accept()
                if (!stopped.get()) onSocket(socket) else socket.close()
            } catch (e: Exception) {
                if (!server.isClosed && !stopped.get()) onError(e.message ?: "Wi-Fi Aware TCP accept failed")
            } finally {
                servers.remove(peerId, server)
                try { server.close() } catch (_: Exception) {}
            }
        }
        try {
            val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                .setPskPassphrase(passphrase)
                .setPort(server.localPort)
                .setTransportProtocol(6)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onUnavailable() { if (!stopped.get()) onPathUnavailable() }
                override fun onLost(network: Network) { if (!stopped.get()) onPathLost() }
            }
            callbacks[peerId] = callback
            connectivity.requestNetwork(request, callback, mainHandler)
        } catch (e: Exception) {
            close(peerId)
            onError(e.message ?: "Could not request Wi-Fi Aware publisher data path")
        }
    }

    fun startClient(
        peerId: String,
        peerHandle: PeerHandle,
        session: SubscribeDiscoverySession,
        passphrase: String,
        onSocket: (Socket) -> Unit,
        onPathUnavailable: () -> Unit,
        onPathLost: () -> Unit,
        onError: (String) -> Unit
    ) {
        check(Build.VERSION.SDK_INT >= 29) { "Wi-Fi Aware data path requires Android 10+" }
        if (stopped.get()) throw IllegalStateException("Wi-Fi Aware data path engine is stopped")
        if (callbacks.containsKey(peerId)) return
        val specifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase(passphrase)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val address = info.peerIpv6Addr ?: return
                val port = info.port
                if (port !in 1..65535 || !opening.add(peerId)) return
                io.execute {
                    try {
                        val socket = network.socketFactory.createSocket(address, port)
                        if (!stopped.get()) onSocket(socket) else socket.close()
                    } catch (e: Exception) {
                        if (!stopped.get()) onError(e.message ?: "Wi-Fi Aware TCP connect failed")
                    } finally { opening.remove(peerId) }
                }
            }
            override fun onUnavailable() { if (!stopped.get()) onPathUnavailable() }
            override fun onLost(network: Network) { if (!stopped.get()) onPathLost() }
        }
        callbacks[peerId] = callback
        try { connectivity.requestNetwork(request, callback, mainHandler) }
        catch (e: Exception) {
            close(peerId)
            onError(e.message ?: "Could not request Wi-Fi Aware subscriber data path")
        }
    }

    fun close(peerId: String) {
        try { servers.remove(peerId)?.close() } catch (_: Exception) {}
        opening.remove(peerId)
        callbacks.remove(peerId)?.let { try { connectivity.unregisterNetworkCallback(it) } catch (_: Exception) {} }
    }

    fun closeAll() {
        stopped.set(true)
        callbacks.keys.toList().forEach { close(it) }
        servers.keys.toList().forEach { close(it) }
        io.shutdownNow()
    }
}
