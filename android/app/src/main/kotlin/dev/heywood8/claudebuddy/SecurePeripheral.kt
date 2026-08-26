package dev.heywood8.claudebuddy

import android.content.Context
import android.util.Log

/**
 * Puts [PhoneSession] between the service and the radio.
 *
 * [GattPeripheral] still moves bytes and knows nothing about the protocol; the service still
 * receives snapshots and knows nothing about keys. This is the only place that holds both.
 *
 * [isReady] stays false until the handshake finishes, so a connected-but-unauthenticated peer
 * gets nothing: no snapshot is rendered and no decision can be sent.
 */
class SecurePeripheral(
    private val context: Context,
    private val deviceName: String,
    private val onSnapshot: (Snapshot) -> Unit,
    private val onReadyChange: (Boolean) -> Unit,
) {
    private var session: PhoneSession? = null
    private var transport: GattPeripheral? = null

    @Volatile
    var isReady: Boolean = false
        private set

    /** Which bridge is on the other end, once the handshake has said so. */
    val host: PairedHost?
        get() = session?.host

    /**
     * The same thing as an id, readable from any thread.
     *
     * `session` is only ever touched on the transport's own thread; the UI needs to know which
     * bridge is live in order to warn that forgetting it will cut the link, and reading it
     * through `session` from the main thread is the race we already paid for once.
     */
    @Volatile
    var linkedHostId: String? = null
        private set

    fun start(): Boolean {
        if (Keyring.hosts(context).isEmpty()) {
            // Advertising with an empty keyring can only ever end in unknown_host. Better to
            // refuse and say why than to blink at the ceiling.
            Log.w(TAG, "no paired bridges — scan a pairing code first")
            return false
        }
        val transport = GattPeripheral(context, ::onLine, ::onTransportChange)
        this.transport = transport
        return transport.start()
    }

    fun stop() {
        session = null
        linkedHostId = null
        setReady(false)
        transport?.stop()
        transport = null
    }

    /**
     * Drops the link if it belongs to [hostId].
     *
     * Deleting the keyring entry does not end the session it authorised: the keys in use were
     * derived at handshake time and live in memory until the peer goes away. Without this, a
     * bridge you just revoked keeps approving things until it happens to disconnect.
     */
    fun revoke(hostId: String) {
        if (linkedHostId != hostId) return
        Log.i(TAG, "revoked $hostId — dropping the live session")
        transport?.disconnect()
    }

    fun send(decision: Decision) {
        val sealed = session?.seal(Wire.encodePayload(decision))
        if (sealed == null) {
            // Either nothing is linked or the handshake has not finished. Silence here reads
            // as a dead button, so it is worth a line.
            Log.w(TAG, "cannot send ${decision.decision} for ${decision.id}: no ready session")
            return
        }
        Log.i(TAG, "sending ${decision.decision} for ${decision.id}")
        transport?.send(sealed)
    }

    // MARK: - Session plumbing

    private fun onTransportChange(connected: Boolean) {
        if (connected) {
            // A fresh session per connection: session keys are derived from salts exchanged
            // in this handshake, so nothing survives a reconnect on purpose.
            session = PhoneSession(
                lookup = { hostId -> Keyring.lookup(context, hostId) },
                deviceName = deviceName,
                isBusy = { isReady },
            )
        } else {
            session = null
            linkedHostId = null
            setReady(false)
        }
    }

    private fun onLine(line: ByteArray) {
        val session = session ?: return
        for (output in session.receive(line)) {
            when (output) {
                is SessionOutput.Send -> transport?.send(output.line)
                is SessionOutput.Ready -> {
                    Log.i(TAG, "session ready with ${session.host?.name}, channel encrypted")
                    linkedHostId = session.host?.hostId
                    setReady(true)
                }
                is SessionOutput.Message ->
                    Wire.decodeSnapshot(output.plaintext)?.let(onSnapshot)
                is SessionOutput.Close -> {
                    Log.i(TAG, "session over: ${output.reason}")
                    setReady(false)
                    transport?.disconnect()
                }
            }
        }
    }

    private fun setReady(value: Boolean) {
        if (isReady == value) return
        isReady = value
        onReadyChange(value)
    }

    private companion object {
        const val TAG = "SecurePeripheral"
    }
}
