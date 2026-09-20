package com.ds4ledcontrol.app.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.ds4ledcontrol.app.dualshock4.Ds4Protocol
import com.ds4ledcontrol.app.dualshock4.Ds4BatteryStatus
import com.ds4ledcontrol.app.dualshock4.Ds4InputState

/**
 * CAMINHO QUE REALMENTE FUNCIONA SEM ROOT: conexao por CABO USB-OTG.
 *
 * Diferente do Bluetooth (ver BluetoothLedSender.kt para a explicacao
 * completa da limitacao), o Android EXPOE uma API publica e sem necessidade
 * de root para conversar diretamente com um dispositivo USB "cru": a classe
 * android.hardware.usb.UsbManager / UsbDeviceConnection. E assim que este
 * controlador envia o output report 0x05 (ver Ds4Protocol.buildUsbReport)
 * de verdade para o hardware do DualShock 4, via um cabo OTG (USB-C/micro-USB
 * do celular -> cabo USB-A -> cabo micro-USB do DS4).
 *
 * Isso NAO e uma simulacao: os bytes sao escritos via
 * UsbDeviceConnection.controlTransfer(...) usando um SET_REPORT HID padrao
 * (bmRequestType 0x21, bRequest 0x09 = HID SET_REPORT), que e o mesmo
 * mecanismo usado por ferramentas como o DS4Windows no Windows.
 */
class Ds4UsbController(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ds4ledcontrol.app.USB_PERMISSION"

        // Requisicao HID padrao (USB HID spec, secao 7.2): SET_REPORT
        private const val HID_SET_REPORT = 0x09
        private const val HID_REPORT_TYPE_OUTPUT = 0x0200 // (ReportType=Output<<8 | ReportID=0)
        private const val USB_TIMEOUT_MS = 1000
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var device: UsbDevice? = null
    private var inEndpoint: UsbEndpoint? = null
    private var readThread: Thread? = null
    @Volatile private var keepReading = false

    var onStatusChanged: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Chamado a cada relatorio de entrada lido com sucesso, com a bateria decodificada. */
    var onBatteryUpdate: ((Ds4BatteryStatus) -> Unit)? = null
    /** Chamado a cada relatorio de entrada lido com sucesso, com botoes/analogicos decodificados. */
    var onInputUpdate: ((Ds4InputState) -> Unit)? = null

    /** Procura, entre os dispositivos USB conectados, um DualShock 4 (por Vendor/Product ID). */
    fun findAttachedDs4(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { d ->
            d.vendorId == Ds4Protocol.VENDOR_ID_SONY &&
                (d.productId == Ds4Protocol.PRODUCT_ID_DS4_V1 || d.productId == Ds4Protocol.PRODUCT_ID_DS4_V2)
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val dev = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (granted && dev != null) {
                        openConnection(dev)
                    } else {
                        onError?.invoke("Permissao USB negada pelo usuario.")
                    }
                }
            }
        }
    }

    @Volatile private var receiverRegistered = false

    fun requestPermissionAndConnect(dev: UsbDevice) {
        try {
            if (usbManager.hasPermission(dev)) {
                openConnection(dev)
                return
            }
            val flags = PendingIntent.FLAG_MUTABLE
            // Android 14+ (API 34) proibe PendingIntent mutavel com Intent
            // "implicito" (sem app de destino). Precisamos continuar mutavel
            // porque o proprio Android preenche os extras (EXTRA_DEVICE,
            // EXTRA_PERMISSION_GRANTED) nesse Intent ao devolver a resposta
            // da permissao -- a solucao e tornar o Intent EXPLICITO, restrito
            // ao nosso proprio pacote, com setPackage().
            val usbPermissionIntent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0, usbPermissionIntent, flags
            )
            if (!receiverRegistered) {
                // Context.RECEIVER_NOT_EXPORTED e a sobrecarga de registerReceiver
                // com 3 argumentos (receiver, filter, flags) so existem a partir
                // do Android 13 (API 33). Em Android 12/12L (API 31/32) -- que
                // este app declara suportar via minSdk 31 -- essa chamada
                // simplesmente nao existe em tempo de execucao. Por isso o branch
                // abaixo usa a forma antiga (sem flags) nesses casos.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        permissionReceiver,
                        IntentFilter(ACTION_USB_PERMISSION),
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION))
                }
                receiverRegistered = true
            }
            onStatusChanged?.invoke("Conectando...")
            usbManager.requestPermission(dev, permissionIntent)
        } catch (e: Exception) {
            onError?.invoke("Erro inesperado ao pedir permissao USB: ${e.message}")
        }
    }

    private fun openConnection(dev: UsbDevice) {
        // Workaround para uma instabilidade conhecida da API USB do Android:
        // em alguns aparelhos, o objeto UsbDevice que vem na resposta da
        // permissao (via Parcelable) as vezes nao "bate" mais com o que o
        // UsbManager tem registrado internamente naquele instante, fazendo
        // openDevice() retornar null mesmo com a permissao concedida.
        // Buscar de novo pelo nome direto de usbManager.deviceList pega a
        // referencia canonica mais atual antes de tentar abrir.
        val freshDev = usbManager.deviceList[dev.deviceName] ?: dev

        val iface = (0 until freshDev.interfaceCount)
            .map { freshDev.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID }
            ?: freshDev.getInterface(0)

        var conn = usbManager.openDevice(freshDev)
        if (conn == null) {
            // Segunda tentativa, com um pequeno atraso: em alguns aparelhos
            // o enumerador USB ainda esta terminando de registrar o
            // dispositivo no exato instante em que a permissao e concedida.
            Thread.sleep(300)
            conn = usbManager.openDevice(usbManager.deviceList[dev.deviceName] ?: freshDev)
        }
        if (conn == null) {
            onError?.invoke(
                "Nao foi possivel abrir a conexao USB com o controle (o sistema Android recusou, " +
                    "mesmo com a permissao concedida). Tente desconectar e reconectar o cabo, ou " +
                    "trocar de porta/adaptador OTG."
            )
            return
        }
        if (!conn.claimInterface(iface, true)) {
            onError?.invoke("Nao foi possivel reivindicar a interface HID do controle (outro app/driver pode estar usando).")
            conn.close()
            return
        }
        device = freshDev
        usbInterface = iface
        connection = conn
        inEndpoint = (0 until iface.endpointCount)
            .map { iface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        onStatusChanged?.invoke("Controle conectado")
        startReadLoop()
    }

    /**
     * Le continuamente o relatorio de entrada (botoes/analogicos/bateria) no
     * endpoint IN da interface HID, em uma thread separada, e repassa os
     * valores decodificados via onBatteryUpdate/onInputUpdate. Isto e o que
     * alimenta a aba "Testar controle" e o indicador de bateria quando
     * conectado por USB (ver documentacao completa em Ds4Protocol.kt).
     */
    private fun startReadLoop() {
        val conn = connection
        val ep = inEndpoint
        if (conn == null || ep == null) {
            onError?.invoke("Endpoint de entrada do controle nao encontrado; teste/bateria indisponiveis.")
            return
        }
        keepReading = true
        readThread = Thread {
            val buffer = ByteArray(Ds4Protocol.USB_INPUT_REPORT_SIZE)
            while (keepReading) {
                val read = try {
                    conn.bulkTransfer(ep, buffer, buffer.size, 500)
                } catch (_: Exception) {
                    -1
                }
                if (read > 0) {
                    Ds4Protocol.parseUsbBattery(buffer)?.let { onBatteryUpdate?.invoke(it) }
                    Ds4Protocol.parseUsbInputReport(buffer)?.let { onInputUpdate?.invoke(it) }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    /** Envia o comando de LED via SET_REPORT no endpoint de controle (endpoint 0). */
    fun sendLedCommand(cmd: Ds4Protocol.LedCommand): Boolean {
        val conn = connection
        val iface = usbInterface
        if (conn == null || iface == null) {
            onError?.invoke("Controle nao esta conectado via USB.")
            return false
        }
        val report = Ds4Protocol.buildUsbReport(cmd)
        val result = conn.controlTransfer(
            /* requestType = */ 0x21, // Host->Device, Class, Interface
            /* request = */ HID_SET_REPORT,
            /* value = */ HID_REPORT_TYPE_OUTPUT or (report[0].toInt() and 0xFF),
            /* index = */ iface.id,
            report,
            report.size,
            USB_TIMEOUT_MS
        )
        return if (result >= 0) {
            true
        } else {
            onError?.invoke("Falha ao enviar comando para a LED (controlTransfer retornou $result).")
            false
        }
    }

    fun disconnect() {
        // keepReading=false ja e suficiente para a thread (daemon) parar
        // sozinha no proximo ciclo do loop (no maximo ~500ms depois, o
        // timeout do bulkTransfer). NAO usamos Thread.join() aqui: como
        // disconnect() e chamado a partir da UI (thread principal), dar
        // join() bloquearia a tela por ate ~600ms a cada desconexao.
        keepReading = false
        readThread = null
        try {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {
        } finally {
            connection = null
            usbInterface = null
            inEndpoint = null
            device = null
            onStatusChanged?.invoke("Controle desconectado")
            try {
                context.unregisterReceiver(permissionReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
    }

    fun isConnected(): Boolean = connection != null
}
