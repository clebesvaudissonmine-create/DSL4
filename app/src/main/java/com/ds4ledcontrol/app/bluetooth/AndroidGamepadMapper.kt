package com.ds4ledcontrol.app.bluetooth

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ds4ledcontrol.app.dualshock4.Ds4InputState

/**
 * Traduz os eventos PADRAO de gamepad do Android (KeyEvent / MotionEvent)
 * para o nosso Ds4InputState.
 *
 * Este e o caminho que funciona quando o DS4 esta conectado por BLUETOOTH:
 * assim que o controle e pareado nas Configuracoes do Android, o proprio
 * sistema operacional o reconhece como um InputDevice do tipo
 * SOURCE_JOYSTICK/SOURCE_GAMEPAD e entrega botoes e analogicos por essas
 * APIs publicas e documentadas (developer.android.com > Guides > Game
 * controllers). Isso e diferente e NAO tem a limitacao de escrever na LED:
 * LER entrada de um HID ja e um recurso padrao do Android, sem precisar de
 * root.
 *
 * Os codigos abaixo (BUTTON_A, AXIS_Z etc.) sao o mapeamento generico que o
 * Android usa para qualquer gamepad estilo PlayStation/Xbox -- podem variar
 * levemente conforme o fabricante do celular, por isso a aba de teste no
 * app tambem mostra os valores brutos para conferencia.
 */
object AndroidGamepadMapper {

    fun isGamepadEvent(device: InputDevice?): Boolean {
        val sources = device?.sources ?: return false
        return (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    }

    fun applyKeyEvent(current: Ds4InputState, event: KeyEvent, pressed: Boolean): Ds4InputState {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> current.copy(cross = pressed)
            KeyEvent.KEYCODE_BUTTON_B -> current.copy(circle = pressed)
            KeyEvent.KEYCODE_BUTTON_X -> current.copy(square = pressed)
            KeyEvent.KEYCODE_BUTTON_Y -> current.copy(triangle = pressed)
            KeyEvent.KEYCODE_BUTTON_L1 -> current.copy(l1 = pressed)
            KeyEvent.KEYCODE_BUTTON_R1 -> current.copy(r1 = pressed)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> current.copy(l3 = pressed)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> current.copy(r3 = pressed)
            KeyEvent.KEYCODE_BUTTON_START -> current.copy(options = pressed)
            KeyEvent.KEYCODE_BUTTON_SELECT -> current.copy(share = pressed)
            KeyEvent.KEYCODE_BUTTON_MODE -> current.copy(ps = pressed)
            KeyEvent.KEYCODE_DPAD_UP -> current.copy(dpadUp = pressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> current.copy(dpadDown = pressed)
            KeyEvent.KEYCODE_DPAD_LEFT -> current.copy(dpadLeft = pressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> current.copy(dpadRight = pressed)
            else -> current
        }
    }

    fun applyMotionEvent(current: Ds4InputState, event: MotionEvent): Ds4InputState {
        fun axis(code: Int): Float = event.getAxisValue(code)
        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        return current.copy(
            leftStickX = axis(MotionEvent.AXIS_X),
            leftStickY = axis(MotionEvent.AXIS_Y),
            rightStickX = axis(MotionEvent.AXIS_Z),
            rightStickY = axis(MotionEvent.AXIS_RZ),
            l2 = axis(MotionEvent.AXIS_LTRIGGER).takeIf { it != 0f } ?: axis(MotionEvent.AXIS_BRAKE),
            r2 = axis(MotionEvent.AXIS_RTRIGGER).takeIf { it != 0f } ?: axis(MotionEvent.AXIS_GAS),
            dpadLeft = hatX < -0.5f,
            dpadRight = hatX > 0.5f,
            dpadUp = hatY < -0.5f,
            dpadDown = hatY > 0.5f
        )
    }
}
