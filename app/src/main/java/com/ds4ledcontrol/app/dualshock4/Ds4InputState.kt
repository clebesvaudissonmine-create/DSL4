package com.ds4ledcontrol.app.dualshock4

/**
 * Estado "achatado" do controle (botoes, analogicos, gatilhos, bateria) usado
 * pela aba de teste e pelo indicador de bateria. E preenchido de duas formas
 * diferentes dependendo de como o DS4 esta conectado -- ver os comentarios
 * em Ds4UsbController.kt e em MainActivity.kt (dispatchGenericMotionEvent /
 * dispatchKeyEvent) para o motivo de cada caminho existir.
 */
data class Ds4InputState(
    val leftStickX: Float = 0f,   // -1f (esquerda) .. 1f (direita)
    val leftStickY: Float = 0f,   // -1f (cima) .. 1f (baixo)
    val rightStickX: Float = 0f,
    val rightStickY: Float = 0f,
    val l2: Float = 0f,           // 0f..1f (gatilho analogico)
    val r2: Float = 0f,
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
    val cross: Boolean = false,   // X
    val circle: Boolean = false,  // O
    val square: Boolean = false,  // □
    val triangle: Boolean = false,// △
    val l1: Boolean = false,
    val r1: Boolean = false,
    val l3: Boolean = false,      // clique do analogico esquerdo
    val r3: Boolean = false,
    val share: Boolean = false,
    val options: Boolean = false,
    val ps: Boolean = false,
    val touchpadClick: Boolean = false
)

data class Ds4BatteryStatus(
    val percent: Int,
    val isCharging: Boolean,
    val isFull: Boolean
)
