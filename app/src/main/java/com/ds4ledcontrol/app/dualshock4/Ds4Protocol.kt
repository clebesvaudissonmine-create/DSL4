package com.ds4ledcontrol.app.dualshock4

import java.util.zip.CRC32

/**
 * ============================================================================
 *  PROTOCOLO DUALSHOCK 4 - REPORTS DE SAIDA (OUTPUT REPORTS) PARA A LED/RUMBLE
 * ============================================================================
 *
 * Estes valores NAO foram inventados: sao os mesmos usados por projetos de
 * codigo aberto amplamente testados que fazem engenharia reversa do DS4:
 *   - driver "hid-sony" do kernel Linux (drivers/hid/hid-sony.c)
 *   - DS4Windows / Ryochan7's DS4Windows (open source, Windows)
 *   - Chromium "device/gamepad/dualshock4_controller.cc"
 *     (define kReportId05 = 0x05 (USB) e kReportId11 = 0x11 (Bluetooth))
 *   - exemplos publicos de escrita direta em /dev/hidraw (ex.: gist.github.com/Ape/8630957)
 *
 * O DualShock 4 usa DOIS formatos de output report diferentes dependendo do
 * meio fisico de transporte:
 *
 *   USB  -> Report ID 0x05, 32 bytes
 *   BT   -> Report ID 0x11, 78 bytes (78 bytes é o tamanho do relatorio "completo";
 *           os ultimos 4 bytes sao um CRC32 exigido pelo transporte Bluetooth
 *           HID, nao pelo relatorio do controle em si)
 *
 * Layout do PAYLOAD (a parte que realmente importa: rumble/LED), a partir do
 * Report ID, é o MESMO nos dois casos, apenas com um deslocamento diferente:
 *
 *   offset  campo
 *   ------  -----------------------------------------
 *     0     Report ID (0x05 USB / 0x11 BT)
 *     1     Flags: quais campos abaixo devem ser aplicados
 *             bit0 (0x01) = aplicar rumble
 *             bit1 (0x02) = aplicar LED
 *             bit2 (0x04) = aplicar flash (pulsacao) da LED
 *           0xF7 / 0xFF aplicam tudo de uma vez (valor usado por DS4Windows/hid-sony)
 *     2     Flags secundarias (nao documentadas oficialmente; 0x00 funciona)
 *     3..4  Reservado / padding (0x00)
 *     5     Motor de rumble fraco (right)
 *     6     Motor de rumble forte (left)
 *     7     LED - componente Vermelho (R)   [no BT ha 1 byte extra antes, ver abaixo]
 *     8     LED - componente Verde   (G)
 *     9     LED - componente Azul    (B)
 *    10     Duracao "aceso" do flash (0-255, 255 ~= 2.5s)
 *    11     Duracao "apagado" do flash (0-255, 255 ~= 2.5s)
 *
 *  IMPORTANTE - DIFERENCA USB x BT:
 *  No transporte BLUETOOTH existe 1 byte a mais logo depois do Report ID
 *  (offset 1 e reservado como no gist publico referenciado acima, que usa
 *  report[1]=0x80). Por isso os offsets de rumble/LED no relatorio Bluetooth
 *  ficam deslocados +1 byte em relacao ao relatorio USB. O buffer abaixo usa
 *  exatamente os offsets confirmados no exemplo publico (report[6],[7] =
 *  rumble; report[8],[9],[10] = LED R,G,B; report[11],[12] = flash on/off).
 *
 *  DIFERENCAS ENTRE REVISOES DE HARDWARE:
 *   - DS4 revisao 1 (CUH-ZCT1x, USB PID 0x05C4): aceita o output report acima
 *     sem exigir CRC valido via Bluetooth.
 *   - DS4 revisao 2 (CUH-ZCT2x, USB PID 0x09CC, tambem usada no controle da
 *     "Slim/Pro"): o firmware mais novo passou a EXIGIR um CRC32 valido nos
 *     ultimos 4 bytes do relatorio Bluetooth, ou o comando e silenciosamente
 *     ignorado (documentado no driver hid-sony do kernel Linux, funcao
 *     dualshock4_send_output_report / uso de crc32(0xA2 + payload)).
 *   Este arquivo calcula o CRC32 sempre, o que e seguro para as duas revisoes
 *   (controles antigos simplesmente ignoram os bytes extras).
 */
object Ds4Protocol {

    // Sony Interactive Entertainment - Vendor ID USB oficial
    const val VENDOR_ID_SONY = 0x054C // 1356

    // Product IDs publicos do DualShock 4 (USB-IF)
    const val PRODUCT_ID_DS4_V1 = 0x05C4 // 1476 - CUH-ZCT1x
    const val PRODUCT_ID_DS4_V2 = 0x09CC // 2508 - CUH-ZCT2x

    const val REPORT_ID_USB = 0x05
    const val REPORT_ID_BLUETOOTH = 0x11

    // "Transaction header" usado pelo protocolo HIDP do Bluetooth ao enviar
    // um SET_REPORT de tipo OUTPUT. E o byte usado como semente do CRC32
    // (ver hid-sony.c / especificacao HID sobre Bluetooth, secao 7.9.1).
    private const val BT_HIDP_HEADER: Byte = 0xA2.toByte()

    data class LedCommand(
        val red: Int,
        val green: Int,
        val blue: Int,
        val rumbleWeak: Int = 0,
        val rumbleStrong: Int = 0,
        /** 0 = sem pulsacao (luz solida). Caso contrario, tempo aceso em unidades de ~10ms (0-255). */
        val flashOnDuration: Int = 0,
        /** Tempo apagado entre pulsacoes, mesma unidade de flashOnDuration. */
        val flashOffDuration: Int = 0
    )

    /** Monta o output report de 32 bytes usado quando o DS4 esta conectado por CABO USB. */
    fun buildUsbReport(cmd: LedCommand): ByteArray {
        val report = ByteArray(32)
        report[0] = REPORT_ID_USB.toByte()
        report[1] = 0xFF.toByte() // aplica rumble + LED + flash
        report[2] = 0x04
        // 3,4 reservados
        report[5] = cmd.rumbleWeak.coerceIn(0, 255).toByte()
        report[6] = cmd.rumbleStrong.coerceIn(0, 255).toByte()
        report[7] = cmd.red.coerceIn(0, 255).toByte()
        report[8] = cmd.green.coerceIn(0, 255).toByte()
        report[9] = cmd.blue.coerceIn(0, 255).toByte()
        report[10] = cmd.flashOnDuration.coerceIn(0, 255).toByte()
        report[11] = cmd.flashOffDuration.coerceIn(0, 255).toByte()
        return report
    }

    /**
     * Monta o output report de 78 bytes usado quando o DS4 esta conectado por
     * BLUETOOTH, incluindo o CRC32 final exigido pelas revisoes mais novas
     * do controle (ver documentacao no topo deste arquivo).
     */
    fun buildBluetoothReport(cmd: LedCommand): ByteArray {
        val report = ByteArray(78)
        report[0] = REPORT_ID_BLUETOOTH.toByte()
        report[1] = 0x80.toByte()
        report[3] = 0xFF.toByte() // aplica rumble + LED + flash
        report[6] = cmd.rumbleWeak.coerceIn(0, 255).toByte()
        report[7] = cmd.rumbleStrong.coerceIn(0, 255).toByte()
        report[8] = cmd.red.coerceIn(0, 255).toByte()
        report[9] = cmd.green.coerceIn(0, 255).toByte()
        report[10] = cmd.blue.coerceIn(0, 255).toByte()
        report[11] = cmd.flashOnDuration.coerceIn(0, 255).toByte()
        report[12] = cmd.flashOffDuration.coerceIn(0, 255).toByte()

        // Calcula CRC32 sobre [header HIDP 0xA2][todos os bytes ate antes do CRC]
        val crc = CRC32()
        crc.update(byteArrayOf(BT_HIDP_HEADER))
        crc.update(report, 0, 74)
        val crcValue = crc.value // 32 bits, little-endian no relatorio
        report[74] = (crcValue and 0xFF).toByte()
        report[75] = ((crcValue shr 8) and 0xFF).toByte()
        report[76] = ((crcValue shr 16) and 0xFF).toByte()
        report[77] = ((crcValue shr 24) and 0xFF).toByte()
        return report
    }

    /**
     * ------------------------------------------------------------------
     *  RELATORIO DE ENTRADA (INPUT REPORT) - BOTOES, ANALOGICOS, BATERIA
     * ------------------------------------------------------------------
     * Usado pela aba "Testar controle" e pelo indicador de bateria quando o
     * DS4 esta ligado por CABO USB (lido em Ds4UsbController via leitura
     * bruta do endpoint USB). O layout abaixo e o mesmo documentado em
     * referencias publicas de engenharia reversa do DS4 (ex.: wiki
     * "DualShock 4" da eleccelerator.com, e confirmado pelo driver hid-sony
     * do kernel Linux, funcao dualshock4_parse_report). Relatorio de 64
     * bytes, Report ID 0x01:
     *
     *   offset  campo
     *   ------  -----------------------------------------
     *     1     Analogico esquerdo, eixo X (0-255, 128 = centro)
     *     2     Analogico esquerdo, eixo Y
     *     3     Analogico direito, eixo X
     *     4     Analogico direito, eixo Y
     *     5     bits 0-3: D-pad (0=cima ... 7=cima-esquerda, 8=neutro)
     *           bit 4: Quadrado, bit 5: X (cross), bit 6: Circulo, bit 7: Triangulo
     *     6     bit0: L1  bit1: R1  bit2: L2(digital)  bit3: R2(digital)
     *           bit4: Share  bit5: Options  bit6: L3  bit7: R3
     *     7     bit0: PS  bit1: Touchpad click
     *     8     Gatilho L2 analogico (0-255)
     *     9     Gatilho R2 analogico (0-255)
     *    30     Bateria: bits 0-3 = nivel (0-11), bit 4 = carregando (cabo USB)
     *
     *  IMPORTANTE: a leitura bruta do USB (via UsbDeviceConnection) reivindica
     *  a interface HID com exclusividade, o que normalmente faz o Android
     *  parar de expor o DS4 como InputDevice/joystick padrao ENQUANTO o app
     *  estiver conectado por USB. Por isso, no modo USB, os botoes/analogicos
     *  sao lidos DAQUI (relatorio bruto), e nao da API padrao de gamepad do
     *  Android.
     *
     *  Ja no modo BLUETOOTH (pareado nas Configuracoes do Android), o proprio
     *  sistema operacional expoe o DS4 como um InputDevice/joystick comum
     *  (API publica android.view.MotionEvent / KeyEvent), sem nenhuma
     *  limitacao e sem necessidade de root -- e o caminho usado por
     *  MainActivity.dispatchGenericMotionEvent/dispatchKeyEvent.
     */
    const val INPUT_REPORT_ID_USB = 0x01
    const val USB_INPUT_REPORT_SIZE = 64
    private const val BATTERY_OFFSET_USB = 30

    fun parseUsbInputReport(report: ByteArray): Ds4InputState? {
        if (report.size < 10) return null
        fun axis(byteVal: Int): Float = ((byteVal and 0xFF) - 128) / 128f
        val dpad = report[5].toInt() and 0x0F
        return Ds4InputState(
            leftStickX = axis(report[1].toInt()),
            leftStickY = axis(report[2].toInt()),
            rightStickX = axis(report[3].toInt()),
            rightStickY = axis(report[4].toInt()),
            l2 = (report[8].toInt() and 0xFF) / 255f,
            r2 = (report[9].toInt() and 0xFF) / 255f,
            dpadUp = dpad == 0 || dpad == 1 || dpad == 7,
            dpadRight = dpad == 1 || dpad == 2 || dpad == 3,
            dpadDown = dpad == 3 || dpad == 4 || dpad == 5,
            dpadLeft = dpad == 5 || dpad == 6 || dpad == 7,
            square = (report[5].toInt() and 0x10) != 0,
            cross = (report[5].toInt() and 0x20) != 0,
            circle = (report[5].toInt() and 0x40) != 0,
            triangle = (report[5].toInt() and 0x80) != 0,
            l1 = (report[6].toInt() and 0x01) != 0,
            r1 = (report[6].toInt() and 0x02) != 0,
            share = (report[6].toInt() and 0x10) != 0,
            options = (report[6].toInt() and 0x20) != 0,
            l3 = (report[6].toInt() and 0x40) != 0,
            r3 = (report[6].toInt() and 0x80) != 0,
            ps = (report[7].toInt() and 0x01) != 0,
            touchpadClick = (report[7].toInt() and 0x02) != 0
        )
    }

    /** Aproximado: o firmware do DS4 nao reporta uma percentagem exata, so um nivel de 0 a 11. */
    fun parseUsbBattery(report: ByteArray): Ds4BatteryStatus? {
        if (report.size <= BATTERY_OFFSET_USB) return null
        val raw = report[BATTERY_OFFSET_USB].toInt() and 0xFF
        val level = raw and 0x0F
        val charging = (raw and 0x10) != 0
        val percent = if (charging) {
            (level.coerceIn(0, 11) * 100 / 11)
        } else {
            (level.coerceIn(0, 10) * 100 / 10)
        }
        return Ds4BatteryStatus(
            percent = percent.coerceIn(0, 100),
            isCharging = charging,
            isFull = charging && level >= 11
        )
    }
}
