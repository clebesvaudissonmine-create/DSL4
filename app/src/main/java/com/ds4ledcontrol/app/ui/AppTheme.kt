package com.ds4ledcontrol.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema claro em tom creme/pessego, pedido pelo usuario (referencia: tela de
 * "Configuracoes de cookies" com fundo creme). Aplicado tanto no tema nativo
 * (res/values/themes.xml, para a cor de fundo antes do Compose desenhar)
 * quanto aqui no Compose (para o conteudo em si).
 */
private val CreamBackground = Color(0xFFFDF0E1)
private val CreamSurface = Color(0xFFFFF8EF)
private val CreamSurfaceVariant = Color(0xFFF3E4D0)
private val WarmAccent = Color(0xFFB5651D)
private val WarmOnAccent = Color(0xFFFFFFFF)
private val WarmText = Color(0xFF2B2118)

private val Ds4LightColorScheme = lightColorScheme(
    primary = WarmAccent,
    onPrimary = WarmOnAccent,
    secondary = WarmAccent,
    background = CreamBackground,
    onBackground = WarmText,
    surface = CreamSurface,
    onSurface = WarmText,
    surfaceVariant = CreamSurfaceVariant,
    onSurfaceVariant = WarmText,
    primaryContainer = CreamSurfaceVariant,
    onPrimaryContainer = WarmText,
    secondaryContainer = CreamSurfaceVariant,
    onSecondaryContainer = WarmText
)

@Composable
fun Ds4AppTheme(content: @Composable () -> Unit) {
    // Fundo creme fixo (nao segue tema escuro do sistema), como pedido.
    MaterialTheme(
        colorScheme = Ds4LightColorScheme,
        content = content
    )
}
