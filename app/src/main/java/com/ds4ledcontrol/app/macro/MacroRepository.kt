package com.ds4ledcontrol.app.macro

/**
 * Ponto de compartilhamento simples entre a ControllerViewModel (que edita
 * as macros na tela) e o Ds4MacroAccessibilityService (que roda em segundo
 * plano, mesmo com outro app/jogo em primeiro piano, e so PRECISA saber
 * qual botao de gatilho observar). Como o servico de acessibilidade roda no
 * mesmo processo do app, um objeto singleton e suficiente -- nao precisa de
 * Binder/AIDL.
 */
object MacroRepository {
    @Volatile
    var macros: List<Macro> = emptyList()

    @Volatile
    var onBackgroundTrigger: ((Macro) -> Unit)? = null
}
