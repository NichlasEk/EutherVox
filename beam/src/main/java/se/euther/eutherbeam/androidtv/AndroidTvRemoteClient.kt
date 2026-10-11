package se.euther.eutherbeam.androidtv

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.Socket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AndroidTvRemoteClient(private val identity: AndroidTvIdentity, private val requestedFeatures: Long = AndroidTvProtocol.ACTIVE_FEATURES) : Closeable {
    private val connectionMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outputLock = Any()

    @Volatile private var features = requestedFeatures
    @Volatile private var appPackage: String? = null
    @Volatile private var voiceReady: CompletableDeferred<Long>? = null
    @Volatile private var appReady: CompletableDeferred<String>? = null
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var connectedHost: String? = null

    suspend fun connect(host: String) = connectionMutex.withLock {
        withContext(Dispatchers.IO) { ensureConnected(host) }
    }

    suspend fun isAvailable(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, AndroidTvProtocol.REMOTE_PORT), PROBE_TIMEOUT_MS) }
        }.isSuccess
    }

    suspend fun sendKey(host: String, key: AndroidTvKey) = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            var lastFailure: Throwable? = null
            repeat(2) {
                try {
                    val active = ensureConnected(host)
                    send(active, AndroidTvProtocol.remoteKey(key.code))
                    return@withContext
                } catch (failure: Throwable) {
                    lastFailure = failure
                    closeSocket()
                }
            }
            throw lastFailure ?: IllegalStateException("Android TV-kommandot misslyckades")
        }
    }

    suspend fun readCurrentApp(host: String): String? = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            val ready = CompletableDeferred<String>()
            appReady = ready
            try {
                ensureConnected(host)
                appPackage ?: withTimeoutOrNull(2_000) { ready.await() }
            } finally { appReady = null }
        }
    }

    suspend fun search(host: String, pcm: ByteArray) = connectionMutex.withLock {
        require(pcm.size in 2..400_000 && pcm.size % 2 == 0) { "Ogiltigt sökljud" }
        withContext(Dispatchers.IO) {
            val active = ensureConnected(host)
            check(features and 8L != 0L) { "Pucken stöder inte fjärrsökning med röst" }
            val ready = CompletableDeferred<Long>()
            voiceReady = ready
            var session: Long? = null
            try {
                send(active, AndroidTvProtocol.remoteKey(AndroidTvKey.SEARCH.code))
                session = withTimeoutOrNull(3_000) { ready.await() }
                    ?: error("Pucken öppnade inte röstsökningen. Kontrollera dess sökfunktion på TV:n.")
                send(active, AndroidTvProtocol.voiceBegin(session))
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + 20_480, pcm.size)
                    val chunk = pcm.copyOfRange(offset, end)
                    send(active, AndroidTvProtocol.voicePayload(session, if (chunk.size < 8192) chunk.copyOf(8192) else chunk))
                    offset = end
                }
                send(active, AndroidTvProtocol.voiceEnd(session))
                session = null
            } finally {
                voiceReady = null
                session?.let { runCatching { send(active, AndroidTvProtocol.voiceEnd(it)) } }
            }
        }
    }

    private fun observe(payload: ByteArray) {
        AndroidTvProtocol.currentApp(payload)?.let { appPackage = it; appReady?.complete(it) }
        AndroidTvProtocol.voiceSession(payload)?.let { voiceReady?.complete(it) }
    }

    private fun ensureConnected(host: String): SSLSocket {
        socket?.takeIf { connectedHost == host && !it.isClosed && it.isConnected }?.let { return it }
        closeSocket()
        features = requestedFeatures
        appPackage = null
        val fresh = identity.sslContext().socketFactory.createSocket() as SSLSocket
        try {
            fresh.connect(InetSocketAddress(host, AndroidTvProtocol.REMOTE_PORT), CONNECT_TIMEOUT_MS)
            fresh.soTimeout = READ_TIMEOUT_MS
            fresh.startHandshake()
            var started = false
            for (ignored in 0 until MAX_HANDSHAKE_MESSAGES) {
                val payload = ProtoWire.readFrame(fresh.inputStream)
                observe(payload)
                val fields = AndroidTvProtocol.topLevelFields(payload)
                when {
                    1 in fields -> {
                        features = requestedFeatures and (AndroidTvProtocol.supportedFeatures(payload) ?: 0L)
                        send(fresh, AndroidTvProtocol.remoteConfigure(features))
                    }
                    2 in fields -> send(fresh, AndroidTvProtocol.remoteSetActive(features))
                    8 in fields -> AndroidTvProtocol.pingValue(payload)?.let {
                        send(fresh, AndroidTvProtocol.remotePingResponse(it))
                    }
                }
                if (40 in fields) {
                    started = true
                    break
                }
            }
            check(started) { "Android TV blev inte redo för kommandon" }
            socket = fresh
            connectedHost = host
            scope.launch { readLoop(fresh) }
            return fresh
        } catch (throwable: Throwable) {
            runCatching { fresh.close() }
            throw throwable
        }
    }

    private fun readLoop(active: SSLSocket) {
        try {
            while (!active.isClosed) {
                val payload = ProtoWire.readFrame(active.inputStream)
                observe(payload)
                val fields = AndroidTvProtocol.topLevelFields(payload)
                when {
                    8 in fields -> AndroidTvProtocol.pingValue(payload)?.let {
                        send(active, AndroidTvProtocol.remotePingResponse(it))
                    }
                    2 in fields -> send(active, AndroidTvProtocol.remoteSetActive(features))
                    1 in fields -> send(active, AndroidTvProtocol.remoteConfigure(features))
                }
            }
        } catch (_: SocketException) {
            // Normal when reconnecting or leaving the app.
        } catch (_: Throwable) {
            // A later command reconnects and surfaces any persistent failure.
        } finally {
            if (socket === active) closeSocket()
        }
    }

    private fun send(target: SSLSocket, payload: ByteArray) = synchronized(outputLock) {
        ProtoWire.frame(target.outputStream, payload)
    }

    private fun closeSocket() {
        val previous = socket
        socket = null
        connectedHost = null
        runCatching { previous?.close() }
    }

    fun disconnect() = closeSocket()

    override fun close() {
        closeSocket()
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 4_000
        const val PROBE_TIMEOUT_MS = 400
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_HANDSHAKE_MESSAGES = 12
    }
}
