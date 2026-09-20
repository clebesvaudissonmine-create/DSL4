package com.ds4ledcontrol.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import com.ds4ledcontrol.app.dualshock4.Ds4Protocol
import java.io.IOException

/**
 * ============================================================================
 *  LIMITACAO REAL E DOCUMENTADA DO ANDROID (leia antes de usar esta classe)
 * ============================================================================
 *
 * O DualShock 4, quando conectado por Bluetooth, e um dispositivo do perfil
 * HID (Human Interface Device) classico. O comando de cor de LED e um
 * "output report" HID que, na especificacao Bluetooth HID (HID_SPEC_V10),
 * deve ser enviado como um SET_REPORT no CANAL DE CONTROLE L2CAP (PSM 0x11),
 * conforme confirmado inclusive na propria lista de discussao do kernel
 * Linux ao corrigir o driver "hidp" para o Sixaxis/DS4
 * (thread "[PATCH 2/2] bt hidp: send Output reports using SET_REPORT on the
 * Control channel", linux-bluetooth@vger.kernel.org).
 *
 * O PROBLEMA: assim que voce pareia o DS4 nas configuracoes de Bluetooth do
 * Android, o proprio SISTEMA OPERACIONAL (o stack Bluedroid/Fluoride do
 * Android) reconhece o perfil HID do controle e abre ele mesmo os canais
 * L2CAP de Controle (PSM 0x11) e Interrupcao (PSM 0x13) para tratar o DS4
 * como um gamepad de entrada padrao (para D-pad, botoes, analogicos...).
 * O Android NAO expõe, para apps comuns via SDK publico, nenhuma API para:
 *   a) interceptar esses canais ja abertos pelo sistema, nem
 *   b) enviar um SET_REPORT de output arbitrario para um dispositivo HID
 *      que o proprio Android ja esta tratando como "input device".
 *
 * A classe android.bluetooth.BluetoothHidDevice (API 28+) faz o CONTRARIO do
 * que precisamos: ela serve para o proprio CELULAR se anunciar como um
 * periferico HID para outro host (ex.: o celular virar um teclado/gamepad
 * de um PC) -- nao para o celular atuar como HOST enviando output reports
 * para um periferico ja conectado.
 *
 * A unica via teoricamente possivel via SDK publico seria abrir um canal
 * L2CAP proprio (BluetoothSocket.createInsecureL2capChannel(psm), disponivel
 * a partir do Android 10 / API 29) diretamente no PSM 0x11 do DS4 -- mas,
 * na pratica, em Android de fabrica (sem root), essa conexao FALHA com
 * connection refused/timeout, porque o PSM 0x11 ja esta ocupado pelo
 * processo de sistema "com.android.bluetooth" que gerencia o dispositivo
 * como gamepad de entrada assim que ele fica pareado e conectado.
 *
 * RESULTADO NA PRATICA:
 *   - Em Android sem root: este metodo abaixo normalmente vai FALHAR
 *     (IOException ao conectar o socket L2CAP). Isso NAO e um bug do app:
 *     e uma limitacao do sistema operacional, documentada acima.
 *   - Em Android com ROOT (ou build customizada onde voce pode desabilitar
 *     o BluetoothHidHost do sistema para este dispositivo especifico via
 *     `bt_hid_disable` / ferramentas equivalentes, ou usar `su` para escrever
 *     direto em /dev/uhidNN via um socket de dominio criado com privilegios
 *     elevados), a conexao pode ter sucesso porque o canal fica livre.
 *
 * A MELHOR ALTERNATIVA REAL, sem root, esta implementada em
 * usb/Ds4UsbController.kt: conectar o DS4 por CABO USB-OTG. O Android expoe
 * uma API publica completa (UsbManager/UsbDeviceConnection) para conversar
 * diretamente com o hardware nesse caso, sem nenhum bloqueio do sistema.
 *
 * Esta classe e mantida no projeto para (1) documentar corretamente o
 * protocolo, (2) funcionar em aparelhos com root, e (3) permitir que, se a
 * Google mudar essa restricao no futuro, o app funcione sem alteracoes.
 */
class BluetoothLedSender(private val device: BluetoothDevice) {

    companion object {
        // PSM (Protocol/Service Multiplexer) do canal de Controle HID,
        // definido pela especificacao Bluetooth HID (fixo em 0x11 = 17).
        private const val HID_CONTROL_PSM = 0x11
    }

    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException(
                "Canais L2CAP diretos exigem Android 10 (API 29) ou superior."
            )
        }
        val s = device.createInsecureL2capChannel(HID_CONTROL_PSM)
        s.connect() // Em Android sem root, geralmente lanca IOException aqui (ver documentacao acima)
        socket = s
    }

    @Throws(IOException::class)
    fun sendLedCommand(cmd: Ds4Protocol.LedCommand) {
        val s = socket ?: throw IOException("Canal L2CAP nao conectado.")
        val report = Ds4Protocol.buildBluetoothReport(cmd)
        // Um SET_REPORT HID sobre o canal de controle comeca com o byte de
        // transacao 0xA2 (HIDP_TRANS_SET_REPORT | HIDP_DATA_RTYPE_OUTPUT)
        // seguido do proprio report. Ver a thread do kernel citada acima.
        val transaction = byteArrayOf(0xA2.toByte()) + report
        s.outputStream.write(transaction)
        s.outputStream.flush()
    }

    fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }
}
