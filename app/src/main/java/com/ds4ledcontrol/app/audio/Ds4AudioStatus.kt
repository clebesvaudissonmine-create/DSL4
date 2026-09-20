package com.ds4ledcontrol.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

enum class Ds4AudioState {
    /** Controle nao conectado ou nada a reportar ainda. */
    UNKNOWN,
    /** Entrada P2 do DS4 detectada como saida de audio ativa do Android (via cabo USB). */
    AVAILABLE_VIA_USB,
    /** Conectado por Bluetooth: o DS4 nao tem NENHUM perfil de audio Bluetooth (confirmado abaixo). */
    UNAVAILABLE_BLUETOOTH,
    /** Conectado por USB mas o Android nao reportou a interface de audio (cabo/adaptador sem os 4 contatos, etc). */
    UNAVAILABLE_USB_NOT_DETECTED
}

/**
 * ============================================================================
 *  ENTRADA DE FONE DE OUVIDO (P2) DO DUALSHOCK 4 - O QUE E REALMENTE POSSIVEL
 * ============================================================================
 *
 * Investigacao antes de implementar (sem simular nada que nao funciona):
 *
 * VIA BLUETOOTH: o radio Bluetooth do DS4 implementa APENAS o perfil HID
 * (para botoes/analogicos). Ele NAO implementa nenhum perfil de audio
 * Bluetooth padrao (nem A2DP, nem HFP/HSP) -- e isso vale ate para o proprio
 * PS4: a Sony confirmou publicamente que o PS4 nunca suportou A2DP (por
 * causa do atraso de 100-200ms que esse perfil introduz em jogos).
 *
 * Entao como o fone do DS4 funciona sem fio em um PS4 de verdade? Por um
 * PROTOCOLO PROPRIETARIO da Sony, embutido no mesmo link Bluetooth
 * customizado usado para os botoes -- nao um perfil Bluetooth padrao que
 * qualquer chip Bluetooth generico entenda. A propria wiki oficial do
 * projeto DS4Windows confirma isso: "DS4 audio only works on USB with
 * generic BT dongles. If you want controller audio over BT, then an
 * official PlayStation BT Dongle is needed." Ou seja: mesmo no PC, com
 * Bluetooth generico, o audio nao funciona -- e preciso o dongle Bluetooth
 * OFICIAL da PlayStation, com chip/firmware proprios.
 *
 * Na propria issue publica que pedia esse recurso no DS4Windows
 * (github.com/Jays2Kings/DS4Windows/issues/17), os mantenedores -- que ja
 * tinham decifrado rumble, LED, touchpad e giroscopio do DS4 -- relatam que,
 * mesmo capturando pacotes reais do controle por anos, NUNCA descobriram
 * como esse audio e transmitido, e o pedido continua sem solucao publica
 * ate hoje.
 *
 * Conclusao: isto NAO e uma restricao do Android (diferente da LED via
 * Bluetooth, que so precisa de root). E um protocolo proprietario da Sony
 * que a comunidade de engenharia reversa nunca decifrou publicamente, e que
 * aparentemente depende de hardware/firmware Bluetooth especifico do
 * dongle oficial -- nao do chip Bluetooth generico de qualquer celular
 * Android. Nem com root isso seria implementavel sem essa informacao, que
 * simplesmente nao existe publicamente. Por isso o app NAO tenta simular
 * este recurso via Bluetooth.
 *
 * VIA CABO USB: quando ligado por USB (o mesmo cabo OTG usado para a LED),
 * o DS4 revisao 2 expoe uma interface separada de "USB Audio Class"
 * (a mesma tecnologia de qualquer fone/placa de som USB padrao) -- e por
 * isso que em um PC, plugar o DS4 por cabo faz o fone dele aparecer sozinho
 * como dispositivo de audio, sem instalar driver nenhum. O Android tambem
 * tem suporte nativo a dispositivos USB Audio Class desde a versao 5 -- e,
 * como o app reivindica so a interface HID (nao o dispositivo USB inteiro),
 * a interface de audio fica livre para o proprio Android gerenciar sozinho,
 * em paralelo ao app. Ou seja: PODE funcionar automaticamente, mas quem faz
 * isso e o sistema operacional -- este app so consegue LER se o Android
 * reconheceu essa saida (via AudioManager) e mostrar isso na tela; ele nao
 * tem como forcar nem simular esse reconhecimento.
 */
object Ds4AudioStatus {

    fun check(context: Context): Ds4AudioState {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return Ds4AudioState.UNKNOWN
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasUsbAudioOutput = outputs.any { d ->
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        return if (hasUsbAudioOutput) Ds4AudioState.AVAILABLE_VIA_USB else Ds4AudioState.UNAVAILABLE_USB_NOT_DETECTED
    }
}
