package com.ds4ledcontrol.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val presetColors = listOf(
    "Vermelho" to Triple(255, 0, 0),
    "Verde" to Triple(0, 255, 0),
    "Azul" to Triple(0, 0, 255),
    "Amarelo" to Triple(255, 255, 0),
    "Roxo" to Triple(160, 0, 255),
    "Ciano" to Triple(0, 255, 255),
    "Branco" to Triple(255, 255, 255)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: ControllerViewModel,
    onRequestBluetoothPermissions: () -> Unit,
    onScan: () -> Unit,
    onConnectUsb: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("DualShock 4 LED", fontWeight = FontWeight.Bold) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(vm)

            vm.errorMessage?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(msg, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            if (vm.connectionState != ConnectionState.CONNECTED) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        onRequestBluetoothPermissions()
                        onScan()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Procurar (BT)")
                    }
                    OutlinedButton(onClick = onConnectUsb, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Usb, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Conectar (USB)")
                    }
                }

                if (vm.foundDevices.value.isNotEmpty()) {
                    Text("Dispositivos encontrados:", fontWeight = FontWeight.SemiBold)
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(vm.foundDevices.value) { d ->
                            ListItem(
                                headlineContent = { Text(d.name) },
                                supportingContent = { Text(d.address) },
                                trailingContent = {
                                    if (d.isLikelyDs4) {
                                        AssistChip(onClick = {}, label = { Text("DS4?") })
                                    }
                                },
                                modifier = Modifier.clickableConnect { vm.connectBluetooth(d) }
                            )
                            Divider()
                        }
                    }
                }
            } else {
                var selectedTab by remember { mutableStateOf(0) }
                val tabs = listOf("LED", "Macros", "Bateria", "Testar controle", "Áudio")
                ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
                    tabs.forEachIndexed { i, title ->
                        Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(title) })
                    }
                }
                when (selectedTab) {
                    0 -> LedControlSection(vm)
                    1 -> MacrosTab(vm)
                    2 -> BatteryTab(vm)
                    3 -> ControllerTestTab(vm)
                    else -> AudioTab(vm)
                }
                OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Desconectar")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(vm: ControllerViewModel) {
    val (label, color) = when (vm.connectionState) {
        ConnectionState.DISCONNECTED -> "Status: Desconectado" to MaterialTheme.colorScheme.surfaceVariant
        ConnectionState.SCANNING -> "Procurando controle..." to MaterialTheme.colorScheme.secondaryContainer
        ConnectionState.CONNECTING -> "Conectando..." to MaterialTheme.colorScheme.secondaryContainer
        ConnectionState.CONNECTED -> "CONTROLE CONECTADO ✓" to MaterialTheme.colorScheme.primaryContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(28.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (vm.connectionState == ConnectionState.CONNECTED) {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.connectionState == ConnectionState.SCANNING || vm.connectionState == ConnectionState.CONNECTING) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Text(label, fontWeight = FontWeight.Bold)
            }
            if (vm.connectionState == ConnectionState.CONNECTED) {
                val transportLabel = when (vm.transportUsed) {
                    TransportUsed.USB -> "via cabo USB"
                    TransportUsed.BLUETOOTH_EXPERIMENTAL -> "via Bluetooth (experimental)"
                    TransportUsed.NONE -> ""
                }
                Text(transportLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LedControlSection(vm: ControllerViewModel) {
    val (r, g, b) = vm.currentColor
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cor do LED", fontWeight = FontWeight.SemiBold)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(r, g, b))
        )

        RgbSliders(r, g, b) { nr, ng, nb -> vm.setColor(nr, ng, nb) }

        Text("Ou arraste a bolinha na roda de cores:", fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ColorWheelPicker(r, g, b) { nr, ng, nb -> vm.setColor(nr, ng, nb) }
        }

        Text("Cores rápidas:", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presetColors.forEach { (name, rgb) ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(rgb.first, rgb.second, rgb.third))
                        .clickableConnect { vm.setColor(rgb.first, rgb.second, rgb.third) }
                )
            }
        }

        Text("Brilho:", fontWeight = FontWeight.SemiBold)
        Slider(value = vm.brightness, onValueChange = { vm.updateBrightness(it) })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.pulseEnabled, onCheckedChange = { vm.setPulse(it) })
            Text("Efeito de pulsação (piscar)")
        }

        Button(onClick = { vm.applyColor() }, modifier = Modifier.fillMaxWidth()) {
            Text("Aplicar cor")
        }
        Text(
            "A cor é reenviada automaticamente se o controle reconectar.",
            style = MaterialTheme.typography.bodySmall
        )

        var showEffects by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { showEffects = !showEffects }, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.activeEffect == EffectType.NONE) "Efeitos ✨" else "Efeitos ✨ (ativo: ${effectLabel(vm.activeEffect)})")
        }
        if (showEffects) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Cores brincalhonas: o app reenvia a cor em loop, já que o controle não faz isso sozinho.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EffectButton("Pisca-pisca", vm.activeEffect == EffectType.BLINK) { vm.startEffect(EffectType.BLINK) }
                    EffectButton("Sirene de polícia", vm.activeEffect == EffectType.POLICE) { vm.startEffect(EffectType.POLICE) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EffectButton("Arco-íris", vm.activeEffect == EffectType.RAINBOW) { vm.startEffect(EffectType.RAINBOW) }
                    EffectButton("Respiração", vm.activeEffect == EffectType.BREATHING) { vm.startEffect(EffectType.BREATHING) }
                }
                if (vm.activeEffect != EffectType.NONE) {
                    OutlinedButton(onClick = { vm.stopEffect() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Parar efeito")
                    }
                }
            }
        }
    }
}

private fun effectLabel(e: EffectType): String = when (e) {
    EffectType.BLINK -> "pisca-pisca"
    EffectType.POLICE -> "sirene"
    EffectType.RAINBOW -> "arco-íris"
    EffectType.BREATHING -> "respiração"
    EffectType.NONE -> ""
}

@Composable
private fun RowScope.EffectButton(label: String, active: Boolean, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
    }
}

@Composable
private fun BatteryTab(vm: ControllerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (vm.transportUsed == TransportUsed.BLUETOOTH_EXPERIMENTAL) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(
                    "A percentagem exata de bateria não fica disponível para apps comuns via Bluetooth " +
                        "sem root (é uma API interna do sistema Android). Conecte por cabo USB para ver o valor real.",
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else if (!vm.batteryAvailable) {
            Text("Aguardando o primeiro relatório de bateria do controle...")
        } else {
            val pct = vm.batteryPercent ?: 0
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (pct <= 20) Color(0xFFE53935) else Color(0xFF43A047))
                )
            }
            Text("$pct%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (vm.batteryCharging) "Carregando (cabo conectado)" else "Na bateria")
            Text(
                "Valor aproximado: o firmware do DS4 só informa 11 níveis, não uma % exata.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun AudioTab(vm: ControllerViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(vm.connectionState, vm.transportUsed) {
        vm.refreshAudioStatus(context)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Entrada de fone (P2) do controle", fontWeight = FontWeight.SemiBold)
        val (msg, detail) = when (vm.audioState) {
            com.ds4ledcontrol.app.audio.Ds4AudioState.AVAILABLE_VIA_USB ->
                "🟢 Disponível — o Android detectou o áudio USB do controle" to
                    "Selecione o DS4 como saída de som nas configurações de áudio do sistema, se ele não vier selecionado automaticamente."
            com.ds4ledcontrol.app.audio.Ds4AudioState.UNAVAILABLE_BLUETOOTH ->
                "🔴 Indisponível via Bluetooth" to
                    "O DS4 não implementa nenhum perfil de áudio Bluetooth padrão — nem o próprio PS4 usa A2DP (a Sony evita de propósito, por causa do atraso que esse perfil introduz). O fone funciona no PS4 sem fio através de um protocolo proprietário da Sony que a comunidade de engenharia reversa nunca decifrou publicamente: a wiki oficial do DS4Windows confirma que isso exige o dongle Bluetooth oficial da PlayStation, não funcionando com nenhum adaptador Bluetooth genérico — nem no PC, nem, portanto, no celular. Não é uma restrição do Android, e nem root resolveria sem esse protocolo, que não existe documentado em lugar nenhum."
            com.ds4ledcontrol.app.audio.Ds4AudioState.UNAVAILABLE_USB_NOT_DETECTED ->
                "🟡 Conectado por USB, mas sem áudio detectado" to
                    "O controle está ligado por cabo, mas o Android ainda não reconheceu a interface de áudio dele. Tente desconectar e reconectar o cabo, ou verifique se o adaptador OTG suporta os 4 contatos de áudio."
            com.ds4ledcontrol.app.audio.Ds4AudioState.UNKNOWN ->
                "Conecte o controle para verificar" to
                    "O status do fone só pode ser checado com o DS4 conectado."
        }
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(msg, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = { vm.refreshAudioStatus(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Verificar novamente")
        }
    }
}

@Composable
private fun ControllerTestTab(vm: ControllerViewModel) {
    val s = vm.testState
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            if (vm.transportUsed == TransportUsed.BLUETOOTH_EXPERIMENTAL)
                "Pressione botões e mexa os analógicos do controle para testar (via eventos de gamepad do Android)."
            else
                "Pressione botões e mexa os analógicos do controle para testar (leitura direta via USB).",
            style = MaterialTheme.typography.bodySmall
        )

        Text("Analógicos", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StickPad("Esquerdo", s.leftStickX, s.leftStickY)
            StickPad("Direito", s.rightStickX, s.rightStickY)
        }

        Text("Gatilhos", fontWeight = FontWeight.SemiBold)
        LabeledSlider("L2", (s.l2 * 255).toInt()) {}
        LabeledSlider("R2", (s.r2 * 255).toInt()) {}

        Text("Botões", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonChip("△", s.triangle)
            ButtonChip("○", s.circle)
            ButtonChip("X", s.cross)
            ButtonChip("□", s.square)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonChip("L1", s.l1)
            ButtonChip("R1", s.r1)
            ButtonChip("L3", s.l3)
            ButtonChip("R3", s.r3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonChip("Share", s.share)
            ButtonChip("Options", s.options)
            ButtonChip("PS", s.ps)
            ButtonChip("Touchpad", s.touchpadClick)
        }

        Text("D-pad", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ButtonChip("↑", s.dpadUp)
            ButtonChip("↓", s.dpadDown)
            ButtonChip("←", s.dpadLeft)
            ButtonChip("→", s.dpadRight)
        }
    }
}

@Composable
private fun StickPad(label: String, x: Float, y: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .offset(x = (35 * x).dp, y = (35 * y).dp)
                    .align(Alignment.Center)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonChip(label: String, active: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            labelColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
private fun ColorWheelPicker(r: Int, g: Int, b: Int, onColorChange: (Int, Int, Int) -> Unit) {
    val sizeDp = 200.dp
    // Posicao atual da bolinha, calculada a partir da cor (R,G,B -> matiz/saturacao).
    val hsv = remember { FloatArray(3) }
    android.graphics.Color.RGBToHSV(r, g, b, hsv)

    Box(
        modifier = Modifier
            .size(sizeDp)
            .pointerInput(Unit) {
                fun updateFromOffset(offset: Offset) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dx = offset.x - center.x
                    val dy = offset.y - center.y
                    val radius = min(size.width, size.height) / 2f
                    val dist = sqrt(dx * dx + dy * dy).coerceAtMost(radius)
                    var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                    if (angle < 0) angle += 360f
                    val sat = (dist / radius).coerceIn(0f, 1f)
                    val argb = android.graphics.Color.HSVToColor(floatArrayOf(angle, sat, 1f))
                    onColorChange(
                        android.graphics.Color.red(argb),
                        android.graphics.Color.green(argb),
                        android.graphics.Color.blue(argb)
                    )
                }
                detectDragGestures(
                    onDragStart = { updateFromOffset(it) }
                ) { change, _ -> updateFromOffset(change.position) }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            // Matiz (hue) em volta do circulo.
            val hueColors = (0..360 step 30).map { deg ->
                val argb = android.graphics.Color.HSVToColor(floatArrayOf(deg.toFloat(), 1f, 1f))
                Color(argb)
            }
            drawCircle(
                brush = Brush.sweepGradient(hueColors, center = center),
                radius = radius,
                center = center,
                style = Fill
            )
            // Saturacao: branco no centro (desaturado) ate transparente na borda.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center,
                style = Fill
            )
            // Bolinha na posicao da cor atual.
            val angleRad = Math.toRadians(hsv[0].toDouble())
            val dist = hsv[1] * radius
            val knobX = center.x + (cos(angleRad) * dist).toFloat()
            val knobY = center.y + (sin(angleRad) * dist).toFloat()
            drawCircle(
                color = Color.Black.copy(alpha = 0.4f),
                radius = 12f,
                center = Offset(knobX, knobY)
            )
            drawCircle(
                color = Color(r, g, b),
                radius = 9f,
                center = Offset(knobX, knobY)
            )
        }
    }
}

@Composable
private fun RgbSliders(r: Int, g: Int, b: Int, onChange: (Int, Int, Int) -> Unit) {
    Column {
        LabeledSlider("R", r) { onChange(it, g, b) }
        LabeledSlider("G", g) { onChange(r, it, b) }
        LabeledSlider("B", b) { onChange(r, g, it) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(20.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f)
        )
        Text(value.toString(), modifier = Modifier.width(40.dp))
    }
}

// Pequeno helper para tornar qualquer Modifier clicavel.
private fun Modifier.clickableConnect(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
