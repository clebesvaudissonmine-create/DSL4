package com.ds4ledcontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ds4ledcontrol.app.macro.Ds4Button
import com.ds4ledcontrol.app.macro.Macro
import com.ds4ledcontrol.app.macro.MacroMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacrosTab(vm: ControllerViewModel) {
    var showCreateForm by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(
                "As macros aqui detectam o botão de gatilho de verdade (sem root). Mas o Android não deixa " +
                    "nenhum app comum \"apertar\" um botão dentro de outro jogo — isso exige uma permissão de " +
                    "sistema que apps normais não recebem. Por isso a execução é local: você vê a macro rodando " +
                    "nos chips abaixo e na aba \"Testar controle\", e ela também pode acionar o rumble/LED do " +
                    "controle como confirmação. Veja o README para o caminho avançado (Shizuku) que consegue " +
                    "controlar jogos de verdade.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (vm.runningMacroId != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Macro em execução ✨", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        vm.virtualMacroButtons.forEach { b -> MiniChip(b.label) }
                    }
                }
            }
        }

        RecordingSection(vm)

        Text("Minhas macros", fontWeight = FontWeight.SemiBold)
        if (vm.macros.value.isEmpty()) {
            Text("Nenhuma macro criada ainda.", style = MaterialTheme.typography.bodySmall)
        } else {
            vm.macros.value.forEach { macro -> MacroRow(macro, vm) }
        }

        OutlinedButton(onClick = { showCreateForm = !showCreateForm }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showCreateForm) "Fechar" else "Criar macro")
        }
        if (showCreateForm) {
            CreateMacroForm(vm) { showCreateForm = false }
        }
    }
}

@Composable
private fun MacroRow(macro: Macro, vm: ControllerViewModel) {
    Card {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(macro.name, fontWeight = FontWeight.Bold)
                val desc = when (macro.mode) {
                    MacroMode.COUNT -> "${macro.triggerButton.label} → ${macro.targetButtons.firstOrNull()?.label ?: "?"} x${macro.repeatCount}, ${macro.intervalMs}ms"
                    MacroMode.HOLD_REPEAT -> "${macro.triggerButton.label} → repete ${macro.targetButtons.firstOrNull()?.label ?: "?"} enquanto segurar (${macro.intervalMs}ms)"
                    MacroMode.COMBO_SIMULTANEOUS -> "${macro.triggerButton.label} → ${macro.targetButtons.joinToString(" + ") { it.label }}"
                    MacroMode.SEQUENCE -> if (macro.recordedSteps != null)
                        "${macro.triggerButton.label} → macro gravada (${macro.recordedSteps.size} passos)"
                    else "${macro.triggerButton.label} → ${macro.targetButtons.joinToString(" → ") { it.label }}"
                }
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = macro.enabled, onCheckedChange = { vm.setMacroEnabled(macro.id, it) })
            IconButton(onClick = { vm.deleteMacro(macro.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir")
            }
        }
    }
}

@Composable
private fun RecordingSection(vm: ControllerViewModel) {
    if (!vm.isRecordingMacro) {
        OutlinedButton(onClick = { vm.startRecordingMacro() }, modifier = Modifier.fillMaxWidth()) {
            Text("● Gravar macro")
        }
        return
    }
    var name by remember { mutableStateOf("Minha macro gravada") }
    var trigger by remember { mutableStateOf(Ds4Button.L1) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("● Gravando... pressione os botões do controle na ordem desejada", fontWeight = FontWeight.Bold)
            Text(
                "${vm.recordedStepsPreview.size} passo(s) capturado(s)",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome da macro") }, modifier = Modifier.fillMaxWidth())
            Text("Botão de ativação:", style = MaterialTheme.typography.bodySmall)
            ButtonPicker(selected = setOf(trigger), multi = false) { trigger = it.first() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.finishRecordingMacro(name, trigger) }, modifier = Modifier.weight(1f)) {
                    Text("Salvar")
                }
                OutlinedButton(onClick = { vm.cancelRecordingMacro() }, modifier = Modifier.weight(1f)) {
                    Text("Cancelar")
                }
            }
        }
    }
}

@Composable
private fun CreateMacroForm(vm: ControllerViewModel, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf(Ds4Button.L1) }
    var mode by remember { mutableStateOf(MacroMode.COUNT) }
    var targets by remember { mutableStateOf(setOf(Ds4Button.R2)) }
    var repeatCount by remember { mutableStateOf(5f) }
    var intervalMs by remember { mutableStateOf(150f) }

    Card {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome da macro") }, modifier = Modifier.fillMaxWidth())

            Text("Botão de ativação (gatilho):", style = MaterialTheme.typography.bodySmall)
            ButtonPicker(selected = setOf(trigger), multi = false) { trigger = it.first() }

            Text("Tipo de macro:", style = MaterialTheme.typography.bodySmall)
            Column {
                MacroMode.values().forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = mode == m, onClick = { mode = m })
                        Text(m.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            val multiTarget = mode == MacroMode.COMBO_SIMULTANEOUS || mode == MacroMode.SEQUENCE
            Text(
                if (multiTarget) "Botões (na ordem desejada):" else "Botão alvo:",
                style = MaterialTheme.typography.bodySmall
            )
            ButtonPicker(selected = targets, multi = multiTarget) { targets = it }

            if (mode == MacroMode.COUNT) {
                Text("Repetições: ${repeatCount.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = repeatCount, onValueChange = { repeatCount = it }, valueRange = 1f..30f)
            }
            if (mode != MacroMode.COMBO_SIMULTANEOUS) {
                Text("Intervalo: ${intervalMs.toInt()}ms", style = MaterialTheme.typography.bodySmall)
                Slider(value = intervalMs, onValueChange = { intervalMs = it }, valueRange = 20f..1000f)
            }

            Button(
                onClick = {
                    if (name.isNotBlank() && targets.isNotEmpty()) {
                        vm.addMacro(
                            name = name,
                            triggerButton = trigger,
                            mode = mode,
                            targetButtons = targets.toList(),
                            repeatCount = repeatCount.toInt(),
                            intervalMs = intervalMs.toLong()
                        )
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Salvar macro")
            }
        }
    }
}

/** Seletor de botao(oes) do DS4 em uma lista horizontal de chips. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonPicker(selected: Set<Ds4Button>, multi: Boolean, onChange: (Set<Ds4Button>) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(Ds4Button.values().toList()) { b ->
            FilterChip(
                selected = b in selected,
                onClick = {
                    onChange(
                        if (multi) {
                            if (b in selected) selected - b else selected + b
                        } else {
                            setOf(b)
                        }
                    )
                },
                label = { Text(b.label) }
            )
        }
    }
}

@Composable
private fun MiniChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
    }
}
