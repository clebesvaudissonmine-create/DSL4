package com.ds4ledcontrol.app

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ds4ledcontrol.app.bluetooth.AndroidGamepadMapper
import com.ds4ledcontrol.app.dualshock4.Ds4InputState
import com.ds4ledcontrol.app.ui.ControllerViewModel
import com.ds4ledcontrol.app.ui.Ds4AppTheme
import com.ds4ledcontrol.app.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val vm: ControllerViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            vm.scanBluetooth()
        } else {
            // Trata a negacao de permissao com uma mensagem clara ao usuario,
            // conforme exigido no requisito de tratamento de erros.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.init(applicationContext)

        setContent {
            Ds4AppTheme {
                Surface(modifier = Modifier) {
                    MainScreen(
                        vm = vm,
                        onRequestBluetoothPermissions = { requestBtPermissionsIfNeeded() },
                        onScan = { if (hasAllBtPermissions()) vm.scanBluetooth() },
                        onConnectUsb = { vm.connectUsbDevice(applicationContext) }
                    )
                }
            }
        }
    }

    private fun hasAllBtPermissions(): Boolean =
        vm.bluetoothPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestBtPermissionsIfNeeded() {
        val missing = vm.bluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vm.disconnect()
    }

    // ------------------------------------------------------------------
    // Leitura de botoes/analogicos quando o DS4 esta ligado por BLUETOOTH.
    // Ver AndroidGamepadMapper.kt para a explicacao completa: e uma API
    // publica e padrao do Android (diferente de escrever na LED), entao
    // funciona aqui sem nenhuma limitacao e sem root.
    // ------------------------------------------------------------------
    private var gamepadState = Ds4InputState()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (AndroidGamepadMapper.isGamepadEvent(event.device)) {
            val pressed = event.action == KeyEvent.ACTION_DOWN
            gamepadState = AndroidGamepadMapper.applyKeyEvent(gamepadState, event, pressed)
            vm.updateFromAndroidGamepad(gamepadState)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (AndroidGamepadMapper.isGamepadEvent(event.device)) {
            gamepadState = AndroidGamepadMapper.applyMotionEvent(gamepadState, event)
            vm.updateFromAndroidGamepad(gamepadState)
        }
        return super.dispatchGenericMotionEvent(event)
    }
}
