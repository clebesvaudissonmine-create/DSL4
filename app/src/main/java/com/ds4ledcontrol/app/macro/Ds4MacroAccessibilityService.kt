package com.ds4ledcontrol.app.macro

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ds4ledcontrol.app.bluetooth.AndroidGamepadMapper

/**
 * ============================================================================
 *  DETECCAO DE MACRO EM SEGUNDO PLANO (COM OUTRO APP/JOGO ABERTO)
 * ============================================================================
 *
 * Isto e OPCIONAL -- o app funciona sem este servico, so que as macros so
 * disparam enquanto o proprio app DS4 LED Control estiver na tela. Ativando
 * este servico (Configuracoes > Acessibilidade > DS4 LED Control), o
 * gatilho da macro passa a ser detectado mesmo com outro jogo em primeiro
 * plano.
 *
 * COMO FUNCIONA (API publica, documentada, sem root): um AccessibilityService
 * pode pedir para RECEBER eventos de tecla de hardware do sistema inteiro,
 * ativando a flag FLAG_REQUEST_FILTER_KEY_EVENTS no seu AccessibilityServiceInfo
 * (configurada no XML de recursos, ver res/xml/macro_accessibility_service.xml).
 * Isso inclui os KeyEvent que o DS4 gera quando conectado por Bluetooth
 * (KEYCODE_BUTTON_R2 etc, os mesmos que a aba "Testar controle" ja le).
 *
 * O QUE ESTE SERVICO NAO FAZ: ele NAO consegue fazer a macro "apertar" o
 * botao dentro do jogo que esta em primeiro plano. AccessibilityService so
 * pode responder com dispatchGesture() (toques na tela simulados) ou
 * consumir/bloquear a propria tecla (retornando true) -- nao existe API de
 * acessibilidade para reinjetar um KeyEvent de gamepad em outro app. Por
 * isso, ao detectar o gatilho aqui, o servico apenas notifica a macro
 * configurada (feedback local -- ver MacroRepository/MainActivity), do
 * mesmo jeito que documentado em MacroEngine.kt.
 */
class Ds4MacroAccessibilityService : AccessibilityService() {

    private var lastPressedButtons: Set<Ds4Button> = emptySet()

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nao precisamos de eventos de UI de outros apps, so dos KeyEvents
        // filtrados abaixo -- este metodo fica vazio de proposito.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!AndroidGamepadMapper.isGamepadEvent(event.device)) return super.onKeyEvent(event)

        val pressed = event.action == KeyEvent.ACTION_DOWN
        val current = mapKeyToButton(event.keyCode)?.let { btn ->
            if (pressed) lastPressedButtons + btn else lastPressedButtons - btn
        } ?: lastPressedButtons

        val newlyPressed = current - lastPressedButtons
        lastPressedButtons = current

        for (macro in MacroRepository.macros) {
            if (macro.enabled && macro.triggerButton in newlyPressed) {
                MacroRepository.onBackgroundTrigger?.invoke(macro)
            }
        }
        // Nao consumimos o evento: deixamos passar para o jogo/app em uso normalmente.
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() { }

    private fun mapKeyToButton(keyCode: Int): Ds4Button? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Ds4Button.CROSS
        KeyEvent.KEYCODE_BUTTON_B -> Ds4Button.CIRCLE
        KeyEvent.KEYCODE_BUTTON_X -> Ds4Button.SQUARE
        KeyEvent.KEYCODE_BUTTON_Y -> Ds4Button.TRIANGLE
        KeyEvent.KEYCODE_BUTTON_L1 -> Ds4Button.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> Ds4Button.R1
        KeyEvent.KEYCODE_BUTTON_THUMBL -> Ds4Button.L3
        KeyEvent.KEYCODE_BUTTON_THUMBR -> Ds4Button.R3
        KeyEvent.KEYCODE_BUTTON_START -> Ds4Button.OPTIONS
        KeyEvent.KEYCODE_BUTTON_SELECT -> Ds4Button.SHARE
        KeyEvent.KEYCODE_BUTTON_MODE -> Ds4Button.PS
        KeyEvent.KEYCODE_DPAD_UP -> Ds4Button.DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Ds4Button.DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Ds4Button.DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Ds4Button.DPAD_RIGHT
        else -> null
    }
}
