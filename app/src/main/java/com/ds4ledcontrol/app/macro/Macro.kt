package com.ds4ledcontrol.app.macro

/**
 * Botoes do DS4 usados como gatilho/alvo de macro. Os mesmos nomes usados em
 * Ds4InputState (ver dualshock4/Ds4InputState.kt) -- aqui como enum para
 * facilitar o seletor de botao na UI.
 */
enum class Ds4Button(val label: String) {
    CROSS("X"), CIRCLE("○"), SQUARE("□"), TRIANGLE("△"),
    L1("L1"), R1("R1"), L2("L2"), R2("R2"), L3("L3"), R3("R3"),
    SHARE("Share"), OPTIONS("Options"), PS("PS"), TOUCHPAD("Touchpad"),
    DPAD_UP("D-pad ↑"), DPAD_DOWN("D-pad ↓"), DPAD_LEFT("D-pad ←"), DPAD_RIGHT("D-pad →")
}

enum class MacroMode(val label: String) {
    /** Botao -> N apertos, com intervalo entre cada um. */
    COUNT("Quantidade de apertos"),
    /** Repete o botao enquanto o gatilho estiver pressionado. */
    HOLD_REPEAT("Repetir enquanto segurar"),
    /** Varios botoes ao mesmo tempo. */
    COMBO_SIMULTANEOUS("Combinação simultânea"),
    /** Botoes em ordem, com intervalo entre cada passo. */
    SEQUENCE("Sequência")
}

/** Um passo gravado no modo "Gravar macro": quais botoes estavam pressionados juntos, e quanto tempo depois do passo anterior. */
data class RecordedStep(
    val buttons: Set<Ds4Button>,
    val delayFromPreviousMs: Long
)

data class Macro(
    val id: String,
    val name: String,
    val triggerButton: Ds4Button,
    val mode: MacroMode,
    /** Para COUNT/HOLD_REPEAT: 1 botao. Para COMBO_SIMULTANEOUS/SEQUENCE: varios, na ordem escolhida. */
    val targetButtons: List<Ds4Button> = emptyList(),
    val repeatCount: Int = 1,
    val intervalMs: Long = 100,
    val repeatWhileHeld: Boolean = false,
    val enabled: Boolean = true,
    /** Preenchido quando a macro veio do modo "Gravar macro" -- substitui targetButtons/intervalMs. */
    val recordedSteps: List<RecordedStep>? = null
)
