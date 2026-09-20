package com.ds4ledcontrol.app.ui

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ds4ledcontrol.app.audio.Ds4AudioState
import com.ds4ledcontrol.app.audio.Ds4AudioStatus
import com.ds4ledcontrol.app.bluetooth.BluetoothLedSender
import com.ds4ledcontrol.app.bluetooth.BluetoothScanner
import com.ds4ledcontrol.app.dualshock4.Ds4BatteryStatus
import com.ds4ledcontrol.app.dualshock4.Ds4InputState
import com.ds4ledcontrol.app.dualshock4.Ds4Protocol
import com.ds4ledcontrol.app.macro.Ds4Button
import com.ds4ledcontrol.app.macro.Macro
import com.ds4ledcontrol.app.macro.MacroEngine
import com.ds4ledcontrol.app.macro.MacroMode
import com.ds4ledcontrol.app.macro.MacroRepository
import com.ds4ledcontrol.app.macro.RecordedStep
import com.ds4ledcontrol.app.usb.Ds4UsbController
import java.util.UUID

enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }
enum class TransportUsed { NONE, USB, BLUETOOTH_EXPERIMENTAL }
enum class EffectType { NONE, BLINK, POLICE, RAINBOW, BREATHING }

data class FoundDevice(val device: BluetoothDevice, val name: String, val address: String, val isLikelyDs4: Boolean)

class ControllerViewModel : ViewModel() {

    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var transportUsed by mutableStateOf(TransportUsed.NONE)
        private set
    var statusMessage by mutableStateOf("Controle desconectado")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var foundDevices = mutableStateOf<List<FoundDevice>>(emptyList())
        private set
    var brightness by mutableStateOf(1f) // 0f..1f, aplicado como escala sobre R/G/B
        private set
    var pulseEnabled by mutableStateOf(false)
        private set
    var currentColor by mutableStateOf(Triple(0, 96, 255)) // azul PlayStation por padrao

    // Bateria: so e preenchida de verdade no modo USB (leitura bruta do
    // relatorio HID). Em Bluetooth sem root a informacao de bateria exata
    // nao esta disponivel para apps comuns -- ver getBatteryLevel() no
    // README, que e API de sistema (@hide), inacessivel a apps de terceiros.
    var batteryPercent by mutableStateOf<Int?>(null)
        private set
    var batteryCharging by mutableStateOf(false)
        private set
    var batteryAvailable by mutableStateOf(false)
        private set

    // Estado ao vivo dos botoes/analogicos, usado pela aba "Testar controle".
    // Preenchido pelo Ds4UsbController quando conectado via USB, ou por
    // eventos padrao de gamepad do Android (KeyEvent/MotionEvent) repassados
    // pela MainActivity quando conectado via Bluetooth.
    var testState by mutableStateOf(Ds4InputState())
        private set

    // Motor de "efeitos brincalhoes" (pisca-pisca, sirene, arco-iris,
    // respiracao). O DS4 so suporta pulsacao de UMA cor por hardware -- para
    // trocar de cor continuamente, o app precisa reenviar comandos em loop.
    var activeEffect by mutableStateOf(EffectType.NONE)
        private set
    private var effectThread: Thread? = null
    @Volatile private var effectRunning = false

    // ------------------------------------------------------------------
    // MACROS -- ver macro/MacroEngine.kt para a explicacao completa e
    // honesta do que funciona (deteccao de gatilho, sim) e do que NAO
    // funciona sem root/Shizuku (injetar o aperto em outro jogo).
    // ------------------------------------------------------------------
    var macros = mutableStateOf<List<Macro>>(emptyList())
        private set
    /** Botoes que a macro em execucao esta "pressionando" localmente agora (para a UI mostrar). */
    var virtualMacroButtons by mutableStateOf<Set<Ds4Button>>(emptySet())
        private set
    var runningMacroId by mutableStateOf<String?>(null)
        private set
    var isRecordingMacro by mutableStateOf(false)
        private set
    var recordedStepsPreview by mutableStateOf<List<RecordedStep>>(emptyList())
        private set
    private val recordingBuffer = mutableListOf<RecordedStep>()
    private var recordingLastTimestamp = 0L
    private var recordingLastButtons: Set<Ds4Button> = emptySet()
    private lateinit var macroEngine: MacroEngine

    var audioState by mutableStateOf(Ds4AudioState.UNKNOWN)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private var scanner: BluetoothScanner? = null
    private var bluetoothSender: BluetoothLedSender? = null
    private var usbController: Ds4UsbController? = null

    // Guarda o ultimo comando de LED aplicado com sucesso pelo usuario, para
    // poder reenvia-lo automaticamente quando o controle reconectar (o DS4
    // nao guarda a cor na propria memoria -- ela precisa ser reenviada a
    // cada nova conexao).
    private var lastAppliedCommand: Ds4Protocol.LedCommand? = null

    fun init(context: Context) {
        if (scanner != null) return
        macroEngine = MacroEngine(
            onVirtualStateChanged = { pressed -> runOnMain { virtualMacroButtons = pressed } },
            onMacroRunningChanged = { id -> runOnMain { runningMacroId = id } }
        )
        // Disparo vindo do Ds4MacroAccessibilityService (segundo plano, com
        // outro app/jogo em primeiro plano). Ver macro/MacroRepository.kt.
        MacroRepository.onBackgroundTrigger = { macro ->
            runOnMain { macroEngine.triggerFromExternal(macro) }
        }
        scanner = BluetoothScanner(context).apply {
            onDeviceFound = { d ->
                val name = try { d.name ?: "Dispositivo desconhecido" } catch (_: SecurityException) { "Dispositivo desconhecido" }
                val entry = FoundDevice(d, name, d.address, looksLikeDualShock4(d))
                if (foundDevices.value.none { it.address == entry.address }) {
                    foundDevices.value = foundDevices.value + entry
                }
            }
            onScanFinished = {
                if (connectionState == ConnectionState.SCANNING) {
                    connectionState = ConnectionState.DISCONNECTED
                    statusMessage = "Controle desconectado"
                }
            }
            onError = { errorMessage = it }
        }
        usbController = Ds4UsbController(context).apply {
            onStatusChanged = { msg ->
                statusMessage = msg
                connectionState = when (msg) {
                    "Conectando..." -> ConnectionState.CONNECTING
                    "Controle conectado" -> ConnectionState.CONNECTED
                    else -> ConnectionState.DISCONNECTED
                }
                if (connectionState == ConnectionState.CONNECTED) {
                    transportUsed = TransportUsed.USB
                    reapplyLastColorIfAny()
                }
            }
            onError = { msg -> runOnMain { errorMessage = msg } }
            onBatteryUpdate = { status: Ds4BatteryStatus ->
                runOnMain {
                    batteryPercent = status.percent
                    batteryCharging = status.isCharging
                    batteryAvailable = true
                }
            }
            onInputUpdate = { state: Ds4InputState ->
                runOnMain {
                    testState = state
                    feedRecording(state)
                    if (::macroEngine.isInitialized) macroEngine.onRealInputState(state, macros.value)
                }
            }
        }
    }

    fun scanBluetooth() {
        errorMessage = null
        foundDevices.value = emptyList()
        connectionState = ConnectionState.SCANNING
        statusMessage = "Procurando controle..."
        scanner?.startScan()
    }

    fun stopScan() = scanner?.stopScan()

    fun bluetoothPermissions(): Array<String> = scanner?.requiredPermissions() ?: emptyArray()

    /**
     * Tenta conectar via Bluetooth para envio do comando de LED.
     * ATENCAO: ver BluetoothLedSender.kt -- isto tende a falhar em Android
     * sem root, por limitacao do proprio sistema operacional. O app informa
     * o erro claramente e sugere a alternativa via cabo USB.
     */
    fun connectBluetooth(target: FoundDevice) {
        errorMessage = null
        connectionState = ConnectionState.CONNECTING
        statusMessage = "Conectando..."
        try {
            val sender = BluetoothLedSender(target.device)
            sender.connect()
            bluetoothSender = sender
            transportUsed = TransportUsed.BLUETOOTH_EXPERIMENTAL
            connectionState = ConnectionState.CONNECTED
            statusMessage = "Controle conectado"
            reapplyLastColorIfAny()
        } catch (e: Exception) {
            connectionState = ConnectionState.DISCONNECTED
            statusMessage = "Controle desconectado"
            errorMessage = "Nao foi possivel controlar a LED via Bluetooth neste aparelho " +
                "(limitacao do Android sem root - veja o botao de ajuda). Detalhe tecnico: ${e.message}"
        }
    }

    fun connectUsbDevice(context: Context) {
        errorMessage = null
        val ctrl = usbController ?: return
        val dev = ctrl.findAttachedDs4()
        if (dev == null) {
            errorMessage = "Nenhum DualShock 4 encontrado via cabo USB-OTG. Conecte o cabo e tente novamente."
            return
        }
        ctrl.requestPermissionAndConnect(dev)
    }

    fun disconnect() {
        stopEffect()
        if (::macroEngine.isInitialized) macroEngine.stopAll()
        bluetoothSender?.disconnect()
        bluetoothSender = null
        usbController?.disconnect()
        connectionState = ConnectionState.DISCONNECTED
        transportUsed = TransportUsed.NONE
        statusMessage = "Controle desconectado"
        batteryPercent = null
        batteryAvailable = false
        testState = Ds4InputState()
        audioState = Ds4AudioState.UNKNOWN
    }

    /**
     * Chamado pela MainActivity com o estado decodificado a partir dos
     * eventos padrao de gamepad do Android (dispatchKeyEvent /
     * dispatchGenericMotionEvent). Este e o caminho usado quando o DS4 esta
     * conectado por BLUETOOTH -- funciona sem root, pois ler entrada de um
     * gamepad e uma API publica normal do Android (diferente de ESCREVER na
     * LED, que e o que exige root via Bluetooth).
     */
    fun updateFromAndroidGamepad(state: Ds4InputState) {
        if (transportUsed == TransportUsed.BLUETOOTH_EXPERIMENTAL || transportUsed == TransportUsed.NONE) {
            testState = state
            feedRecording(state)
            if (::macroEngine.isInitialized) macroEngine.onRealInputState(state, macros.value)
        }
    }

    fun updateBrightness(value: Float) { brightness = value.coerceIn(0f, 1f) }
    fun setPulse(enabled: Boolean) { pulseEnabled = enabled }
    fun setColor(r: Int, g: Int, b: Int) { currentColor = Triple(r, g, b) }

    fun applyColor() {
        stopEffect()
        errorMessage = null
        val (r, g, b) = currentColor
        val scaled = Triple((r * brightness).toInt(), (g * brightness).toInt(), (b * brightness).toInt())
        val cmd = Ds4Protocol.LedCommand(
            red = scaled.first, green = scaled.second, blue = scaled.third,
            flashOnDuration = if (pulseEnabled) 60 else 0,
            flashOffDuration = if (pulseEnabled) 60 else 0
        )
        val ok = sendCommand(cmd)
        if (ok) {
            // So memorizamos o comando quando ele foi enviado com sucesso,
            // para nao reenviar algo que nunca chegou a funcionar.
            lastAppliedCommand = cmd
        } else if (errorMessage == null) {
            errorMessage = "Falha ao aplicar a cor. Verifique a conexao com o controle."
        }
    }

    /**
     * Reenvia automaticamente a ultima cor aplicada com sucesso, assim que o
     * controle (re)conecta. O DS4 nao guarda a cor internamente: cada nova
     * sessao de conexao volta ao comportamento padrao de fabrica ate que um
     * novo comando seja enviado -- entao fazemos isso aqui por conta do
     * usuario, sem exigir que ele toque em "Aplicar cor" de novo.
     */
    private fun reapplyLastColorIfAny() {
        val cmd = lastAppliedCommand ?: return
        val ok = sendCommand(cmd)
        if (!ok && errorMessage == null) {
            errorMessage = "Nao foi possivel reaplicar automaticamente a ultima cor apos reconectar."
        }
    }

    private fun sendCommand(cmd: Ds4Protocol.LedCommand): Boolean {
        return when (transportUsed) {
            TransportUsed.USB -> usbController?.sendLedCommand(cmd) ?: false
            TransportUsed.BLUETOOTH_EXPERIMENTAL -> {
                try {
                    bluetoothSender?.sendLedCommand(cmd)
                    true
                } catch (e: Exception) {
                    runOnMain { errorMessage = "Falha ao enviar comando pela conexao Bluetooth: ${e.message}" }
                    false
                }
            }
            TransportUsed.NONE -> {
                runOnMain { errorMessage = "Controle nao esta conectado." }
                false
            }
        }
    }

    /**
     * Dispara um efeito "brincalhao" continuo, reenviando comandos de LED em
     * loop em uma thread separada ate ser parado. O hardware do DS4 so
     * pulsa UMA cor sozinho (flashOnDuration/flashOffDuration); trocar de
     * cor de verdade (sirene vermelho/azul, arco-iris) exige que o proprio
     * app fique reenviando comandos com timing controlado por software.
     */
    fun startEffect(type: EffectType) {
        stopEffect()
        if (transportUsed == TransportUsed.NONE) {
            errorMessage = "Controle nao esta conectado."
            return
        }
        errorMessage = null
        activeEffect = type
        effectRunning = true
        effectThread = Thread {
            var step = 0
            while (effectRunning) {
                val cmd = when (type) {
                    EffectType.BLINK -> {
                        val (r, g, b) = currentColor
                        val on = step % 2 == 0
                        Ds4Protocol.LedCommand(
                            red = if (on) r else 0,
                            green = if (on) g else 0,
                            blue = if (on) b else 0
                        )
                    }
                    EffectType.POLICE -> {
                        if (step % 2 == 0) Ds4Protocol.LedCommand(255, 0, 0)
                        else Ds4Protocol.LedCommand(0, 0, 255)
                    }
                    EffectType.RAINBOW -> {
                        val hue = (step * 8) % 360
                        val hsv = floatArrayOf(hue.toFloat(), 1f, 1f)
                        val argb = android.graphics.Color.HSVToColor(hsv)
                        Ds4Protocol.LedCommand(
                            red = android.graphics.Color.red(argb),
                            green = android.graphics.Color.green(argb),
                            blue = android.graphics.Color.blue(argb)
                        )
                    }
                    EffectType.BREATHING -> {
                        val (r, g, b) = currentColor
                        // Curva senoidal 0..1 para um efeito de "respirar" suave.
                        val phase = (Math.sin(step * 0.15) + 1.0) / 2.0
                        Ds4Protocol.LedCommand(
                            red = (r * phase).toInt(),
                            green = (g * phase).toInt(),
                            blue = (b * phase).toInt()
                        )
                    }
                    EffectType.NONE -> Ds4Protocol.LedCommand(0, 0, 0)
                }
                sendCommand(cmd)
                step++
                val delayMs = when (type) {
                    EffectType.POLICE -> 150L
                    EffectType.BLINK -> 400L
                    EffectType.RAINBOW -> 90L
                    EffectType.BREATHING -> 40L
                    EffectType.NONE -> 500L
                }
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stopEffect() {
        effectRunning = false
        effectThread?.interrupt()
        effectThread = null
        activeEffect = EffectType.NONE
    }

    // ------------------------------------------------------------------
    // Gerenciamento de macros (criar/editar/excluir/ativar-desativar)
    // ------------------------------------------------------------------
    fun addMacro(
        name: String,
        triggerButton: Ds4Button,
        mode: MacroMode,
        targetButtons: List<Ds4Button>,
        repeatCount: Int,
        intervalMs: Long
    ) {
        val macro = Macro(
            id = UUID.randomUUID().toString(),
            name = name,
            triggerButton = triggerButton,
            mode = mode,
            targetButtons = targetButtons,
            repeatCount = repeatCount,
            intervalMs = intervalMs,
            repeatWhileHeld = mode == MacroMode.HOLD_REPEAT
        )
        macros.value = macros.value + macro
        MacroRepository.macros = macros.value
    }

    fun deleteMacro(id: String) {
        macros.value = macros.value.filterNot { it.id == id }
        MacroRepository.macros = macros.value
    }

    fun setMacroEnabled(id: String, enabled: Boolean) {
        macros.value = macros.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        MacroRepository.macros = macros.value
    }

    // ------------------------------------------------------------------
    // Gravacao de macro: registra os botoes reais do controle, na ordem,
    // com o tempo entre cada mudanca e se foram simultaneos (mesmo passo).
    // ------------------------------------------------------------------
    fun startRecordingMacro() {
        isRecordingMacro = true
        recordingBuffer.clear()
        recordedStepsPreview = emptyList()
        recordingLastTimestamp = System.currentTimeMillis()
        recordingLastButtons = emptySet()
    }

    fun cancelRecordingMacro() {
        isRecordingMacro = false
        recordingBuffer.clear()
        recordedStepsPreview = emptyList()
    }

    fun finishRecordingMacro(name: String, triggerButton: Ds4Button) {
        isRecordingMacro = false
        if (recordingBuffer.isEmpty()) return
        val macro = Macro(
            id = UUID.randomUUID().toString(),
            name = name,
            triggerButton = triggerButton,
            mode = MacroMode.SEQUENCE,
            recordedSteps = recordingBuffer.toList()
        )
        macros.value = macros.value + macro
        MacroRepository.macros = macros.value
        recordingBuffer.clear()
        recordedStepsPreview = emptyList()
    }

    private fun feedRecording(state: Ds4InputState) {
        if (!isRecordingMacro || !::macroEngine.isInitialized) return
        val buttons = macroEngine.buttonsFrom(state)
        if (buttons != recordingLastButtons) {
            val now = System.currentTimeMillis()
            val delay = now - recordingLastTimestamp
            if (buttons.isNotEmpty()) {
                recordingBuffer.add(RecordedStep(buttons, delay))
                recordedStepsPreview = recordingBuffer.toList()
            }
            recordingLastTimestamp = now
            recordingLastButtons = buttons
        }
    }

    // ------------------------------------------------------------------
    // Audio (entrada de fone P2 do DS4) -- ver Ds4AudioStatus.kt para a
    // investigacao completa de por que Bluetooth nunca funciona e USB pode.
    // ------------------------------------------------------------------
    fun refreshAudioStatus(context: Context) {
        audioState = when {
            connectionState != ConnectionState.CONNECTED -> Ds4AudioState.UNKNOWN
            transportUsed == TransportUsed.BLUETOOTH_EXPERIMENTAL -> Ds4AudioState.UNAVAILABLE_BLUETOOTH
            transportUsed == TransportUsed.USB -> Ds4AudioStatus.check(context)
            else -> Ds4AudioState.UNKNOWN
        }
    }

    override fun onCleared() {
        scanner?.teardown()
        disconnect()
    }
}
