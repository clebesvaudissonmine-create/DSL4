package com.ds4ledcontrol.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Responsavel apenas pela DESCOBERTA e PAREAMENTO de dispositivos Bluetooth
 * classico (o DS4 usa Bluetooth Classic / BR-EDR com perfil HID, nao BLE).
 *
 * Este componente funciona 100% com APIs publicas do Android, sem
 * necessidade de root, seguindo o modelo de permissoes do Android 12+
 * (BLUETOOTH_SCAN / BLUETOOTH_CONNECT).
 */
class BluetoothScanner(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    var onDeviceFound: ((BluetoothDevice) -> Unit)? = null
    var onScanFinished: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.let { onDeviceFound?.invoke(it) }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    onScanFinished?.invoke()
                }
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /** Dispositivos ja pareados anteriormente pelo usuario nas configuracoes do Android. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): Set<BluetoothDevice> = adapter?.bondedDevices ?: emptySet()

    /**
     * Heuristica para identificar um DualShock 4 pelo nome anunciado
     * ("Wireless Controller" e o nome padrao que o firmware do DS4 divulga)
     * -- nao ha um UUID de servico exclusivo exposto de forma simples via
     * BluetoothDevice para diferenciar de outros gamepads Sony/terceiros,
     * entao usamos nome + classe de dispositivo (Peripheral/Gamepad).
     */
    fun looksLikeDualShock4(device: BluetoothDevice): Boolean {
        val name = try { device.name } catch (_: SecurityException) { null } ?: return false
        return name.equals("Wireless Controller", ignoreCase = true) ||
            name.contains("DualShock", ignoreCase = true) ||
            name.contains("DS4", ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter ?: run {
            onError?.invoke("Este dispositivo nao possui adaptador Bluetooth.")
            return
        }
        if (!a.isEnabled) {
            onError?.invoke("Bluetooth esta desligado. Ative o Bluetooth para procurar o controle.")
            return
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(receiver, filter)
            receiverRegistered = true
        }
        if (a.isDiscovering) a.cancelDiscovery()
        val started = a.startDiscovery()
        if (!started) {
            onError?.invoke("Nao foi possivel iniciar a busca por dispositivos Bluetooth.")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("MissingPermission")
    fun stopScan() {
        adapter?.let { if (it.isDiscovering) it.cancelDiscovery() }
    }

    fun teardown() {
        if (receiverRegistered) {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    /** Lista de permissoes de runtime necessarias, de acordo com a versao do Android. */
    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.BLUETOOTH,
                android.Manifest.permission.BLUETOOTH_ADMIN
            )
        }
    }
}
