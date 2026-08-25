package dev.heywood8.claudebuddy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

/**
 * The peripheral half of the link: advertises the service, serves the GATT characteristics,
 * and moves newline-delimited lines in both directions.
 *
 * A transport and nothing more. It has never heard of the handshake, of keys, or of what a
 * line means — [SecurePeripheral] owns all of that, which is what keeps the protocol testable
 * without a radio.
 */
@SuppressLint("MissingPermission")
class GattPeripheral(
    private val context: Context,
    /** Called with each complete line received from the peer. */
    private val onLine: (ByteArray) -> Unit,
    /** Called when a peer connects or disconnects — the transport, not the session. */
    private val onLinkChange: (Boolean) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var peer: BluetoothDevice? = null
    private var mtu = DEFAULT_MTU
    private val assembler = LineAssembler()

    /** Notifications are not fire-and-forget: the next chunk waits for the previous one. */
    private val outbox = ArrayDeque<ByteArray>()
    private var notifyInFlight = false

    fun start(): Boolean {
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) {
            Log.w(TAG, "bluetooth is off")
            return false
        }

        val server = manager.openGattServer(context, callback) ?: return false
        this.server = server

        val rx = BluetoothGattCharacteristic(
            Nus.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            Nus.TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    Nus.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE,
                )
            )
        }
        this.tx = tx

        val service = BluetoothGattService(Nus.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server.addService(service)

        // The 128-bit service UUID leaves too little room in a 31-byte advertisement for a
        // name as well, so the name goes into the scan response.
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val advertisement = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(Nus.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        adapter.bluetoothLeAdvertiser
            ?.startAdvertising(settings, advertisement, scanResponse, advertiseCallback)
            ?: run {
                Log.e(TAG, "no LE advertiser")
                return false
            }
        return true
    }

    fun stop() {
        manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        server?.close()
        server = null
        peer = null
        outbox.clear()
        notifyInFlight = false
        onLinkChange(false)
    }

    fun send(line: ByteArray) {
        val peer = peer ?: return
        val tx = tx ?: return
        val bytes = line
        val limit = (mtu - GATT_HEADER).coerceAtLeast(MIN_CHUNK)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + limit, bytes.size)
            outbox.addLast(bytes.copyOfRange(offset, end))
            offset = end
        }
        pump(peer, tx)
    }

    private fun pump(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) {
        if (notifyInFlight) return
        val chunk = outbox.removeFirstOrNull() ?: return
        notifyInFlight = true
        val server = server ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, false, chunk)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            server.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    /**
     * Drops the peer. A session that ended for a protocol reason will not recover by staying
     * connected; reconnecting restarts the handshake from scratch.
     */
    fun disconnect() {
        val peer = peer ?: return
        server?.cancelConnection(peer)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "advertising failed: $errorCode")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // One host at a time. A second one is refused rather than interleaved:
                // a shared queue makes it ambiguous on screen whose command is on screen.
                if (peer != null && peer != device) {
                    server?.cancelConnection(device)
                    return
                }
                peer = device
                mtu = DEFAULT_MTU
                onLinkChange(true)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED && device == peer) {
                peer = null
                outbox.clear()
                notifyInFlight = false
                onLinkChange(false)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            this@GattPeripheral.mtu = mtu
            Log.i(TAG, "mtu $mtu")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == Nus.RX) {
                for (line in assembler.feed(value)) {
                    onLine(line)
                }
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyInFlight = false
            val tx = tx ?: return
            pump(device, tx)
        }
    }

    private companion object {
        const val TAG = "GattPeripheral"
        const val DEFAULT_MTU = 23
        const val GATT_HEADER = 3
        const val MIN_CHUNK = 20
    }
}
