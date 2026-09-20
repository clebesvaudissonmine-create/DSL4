package com.ds4ledcontrol.app.macro

import com.ds4ledcontrol.app.dualshock4.Ds4InputState

/**
 * ============================================================================
 *  LEITURA HONESTA DO QUE ESTE MOTOR DE MACROS CONSEGUE FAZER
 * ============================================================================
 *
 * DETECTAR o botao de gatilho: funciona de verdade, sem root. Ler entrada de
 * um gamepad Bluetooth ja pareado e uma API publica padrao do Android
 * (MotionEvent/KeyEvent), a mesma usada pela aba "Testar controle".
 *
 * EXECUTAR a macro DENTRO de outro app/jogo (fazer o R2 realmente "apertar"
 * la no jogo que voce esta jogando): isto e o que a maioria das pessoas
 * espera de uma macro de controle, e a resposta honesta e que o Android NAO
 * permite isso para um app comum. Injetar KeyEvent/MotionEvent em OUTRO
 * processo exige a permissao de sistema android.permission.INJECT_EVENTS,
 * que so e concedida a apps assinados com a assinatura do sistema
 * operacional -- nao existe forma de um app instalado normalmente (mesmo
 * pedindo permissao ao usuario) conseguir isso, com ou sem os botoes
 * corretos configurados. Isto NAO e uma limitacao de implementacao deste
 * projeto: e assim que funcionam os apps reais desse genero na Play Store
 * (ex.: "Buttons Remapper") -- eles usam AccessibilityService para OBSERVAR
 * botoes e, na versao paga, "executam" simulando TOQUES NA TELA (nao
 * apertos de gamepad de verdade), porque e o unico tipo de injecao que a
 * API de acessibilidade permite sem root.
 *
 * Por isso este motor executa as macros de forma REAL, mas LOCAL a este
 * app: ele calcula exatamente quais botoes estariam pressionados a cada
 * instante (para a aba "Testar controle" refletir a macro rodando, e para
 * disparar feedback real no proprio DS4 -- rumble/LED, que SIM sabemos
 * mandar de verdade) -- mas nao envia nada para outros aplicativos. A opcao
 * avancada com Shizuku (ver README) e o unico caminho, sem root pleno, que
 * de fato consegue injetar eventos em outros apps -- e fica de fora deste
 * projeto por exigir uma ferramenta externa que o usuario precisa instalar
 * e parear manualmente.
 */
class MacroEngine(
    /** Chamado a cada mudanca no conjunto de botoes "virtualmente" pressionados pela execucao da macro. */
    private val onVirtualStateChanged: (pressed: Set<Ds4Button>) -> Unit,
    /** Chamado quando uma macro comeca/termina de executar, para a UI mostrar qual esta ativa. */
    private val onMacroRunningChanged: (macroId: String?) -> Unit
) {
    private var previousButtons: Set<Ds4Button> = emptySet()
    private val holdThreads = mutableMapOf<String, Thread>()
    private val holdRunning = mutableMapOf<String, Boolean>()

    fun buttonsFrom(state: Ds4InputState): Set<Ds4Button> {
        val s = mutableSetOf<Ds4Button>()
        if (state.cross) s += Ds4Button.CROSS
        if (state.circle) s += Ds4Button.CIRCLE
        if (state.square) s += Ds4Button.SQUARE
        if (state.triangle) s += Ds4Button.TRIANGLE
        if (state.l1) s += Ds4Button.L1
        if (state.r1) s += Ds4Button.R1
        if (state.l2 > 0.5f) s += Ds4Button.L2
        if (state.r2 > 0.5f) s += Ds4Button.R2
        if (state.l3) s += Ds4Button.L3
        if (state.r3) s += Ds4Button.R3
        if (state.share) s += Ds4Button.SHARE
        if (state.options) s += Ds4Button.OPTIONS
        if (state.ps) s += Ds4Button.PS
        if (state.touchpadClick) s += Ds4Button.TOUCHPAD
        if (state.dpadUp) s += Ds4Button.DPAD_UP
        if (state.dpadDown) s += Ds4Button.DPAD_DOWN
        if (state.dpadLeft) s += Ds4Button.DPAD_LEFT
        if (state.dpadRight) s += Ds4Button.DPAD_RIGHT
        return s
    }

    /** Chame a cada novo Ds4InputState real (do controle de verdade) para detectar gatilhos de macro. */
    fun onRealInputState(state: Ds4InputState, macros: List<Macro>) {
        val current = buttonsFrom(state)
        val pressedNow = current - previousButtons
        val releasedNow = previousButtons - current

        for (macro in macros) {
            if (!macro.enabled) continue
            if (macro.triggerButton in pressedNow) {
                when (macro.mode) {
                    MacroMode.HOLD_REPEAT -> startHold(macro)
                    else -> fireOnce(macro)
                }
            }
            if (macro.mode == MacroMode.HOLD_REPEAT && macro.triggerButton in releasedNow) {
                stopHold(macro.id)
            }
        }
        previousButtons = current
    }

    /** Disparo manual (ex.: vindo do Ds4MacroAccessibilityService, em segundo plano). */
    fun triggerFromExternal(macro: Macro) {
        if (macro.mode == MacroMode.HOLD_REPEAT) return // "segurar" nao e suportado em segundo plano (sem evento de solturas confiavel entre processos)
        fireOnce(macro)
    }

    private fun fireOnce(macro: Macro) {
        Thread {
            onMacroRunningChanged(macro.id)
            try {
                val steps = macro.recordedSteps
                if (steps != null && steps.isNotEmpty()) {
                    for (step in steps) {
                        Thread.sleep(step.delayFromPreviousMs.coerceAtLeast(0))
                        onVirtualStateChanged(step.buttons)
                        Thread.sleep(60)
                        onVirtualStateChanged(emptySet())
                    }
                    return@Thread
                }
                when (macro.mode) {
                    MacroMode.COUNT -> {
                        val target = macro.targetButtons.firstOrNull() ?: return@Thread
                        repeat(macro.repeatCount.coerceAtLeast(1)) {
                            onVirtualStateChanged(setOf(target))
                            Thread.sleep(60)
                            onVirtualStateChanged(emptySet())
                            Thread.sleep(macro.intervalMs.coerceAtLeast(20))
                        }
                    }
                    MacroMode.COMBO_SIMULTANEOUS -> {
                        onVirtualStateChanged(macro.targetButtons.toSet())
                        Thread.sleep(120)
                        onVirtualStateChanged(emptySet())
                    }
                    MacroMode.SEQUENCE -> {
                        for (button in macro.targetButtons) {
                            onVirtualStateChanged(setOf(button))
                            Thread.sleep(60)
                            onVirtualStateChanged(emptySet())
                            Thread.sleep(macro.intervalMs.coerceAtLeast(20))
                        }
                    }
                    MacroMode.HOLD_REPEAT -> { /* tratado em startHold */ }
                }
            } finally {
                onMacroRunningChanged(null)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startHold(macro: Macro) {
        if (holdRunning[macro.id] == true) return
        val target = macro.targetButtons.firstOrNull() ?: return
        holdRunning[macro.id] = true
        holdThreads[macro.id] = Thread {
            onMacroRunningChanged(macro.id)
            while (holdRunning[macro.id] == true) {
                onVirtualStateChanged(setOf(target))
                Thread.sleep(50)
                onVirtualStateChanged(emptySet())
                Thread.sleep(macro.intervalMs.coerceAtLeast(20))
            }
            onMacroRunningChanged(null)
        }.apply { isDaemon = true; start() }
    }

    private fun stopHold(macroId: String) {
        holdRunning[macroId] = false
        holdThreads.remove(macroId)
    }

    fun stopAll() {
        holdRunning.keys.toList().forEach { stopHold(it) }
    }
}
