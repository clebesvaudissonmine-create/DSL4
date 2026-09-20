# DS4 LED Control

App Android (Kotlin + Jetpack Compose) para descobrir, parear/conectar um
controle DualShock 4 e enviar comandos reais para trocar a cor do LED.

## Estrutura de pastas
```
DS4LedController/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/ds4ledcontrol/app/
        │   ├── MainActivity.kt
        │   ├── bluetooth/BluetoothScanner.kt
        │   ├── bluetooth/BluetoothLedSender.kt
        │   ├── dualshock4/Ds4Protocol.kt
        │   ├── usb/Ds4UsbController.kt
        │   └── ui/ControllerViewModel.kt, MainScreen.kt
        └── res/
            ├── values/{strings,colors,themes}.xml
            ├── xml/usb_device_filter.xml
            ├── drawable/ic_launcher_*.xml
            └── mipmap-anydpi-v26/ic_launcher*.xml
```

## Como compilar o APK
1. Abra a pasta `DS4LedController/` no Android Studio (Hedgehog ou mais
   recente) via *File > Open*. O Android Studio baixa o Gradle Wrapper
   automaticamente na primeira sincronização.
2. Aguarde o *Gradle Sync*.
3. `Build > Build Bundle(s)/APK(s) > Build APK(s)`, ou pela linha de comando
   dentro da pasta do projeto: `./gradlew assembleDebug`.
4. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## LEIA ANTES DE USAR: a limitação real do Bluetooth no Android

Este é o ponto mais importante do projeto e por isso está explicado tanto
aqui quanto nos comentários de `BluetoothLedSender.kt`.

O DualShock 4 fala o protocolo **Bluetooth HID clássico**. Trocar a cor do
LED exige enviar um *output report* pelo **canal L2CAP de Controle (PSM
0x11)**, usando um `SET_REPORT`. Isso é confirmado tanto pela especificação
Bluetooth HID quanto pelo próprio driver `hid-sony` do kernel Linux.

O problema é que, assim que você pareia o DS4 nas *Configurações do
Android*, é o **próprio sistema operacional** (o processo `com.android.bluetooth`)
que abre esse canal L2CAP, para tratar o controle como um gamepad de entrada
padrão. O Android **não oferece nenhuma API pública** para um app comum:
- interceptar esse canal já aberto pelo sistema, ou
- enviar um `SET_REPORT` de saída para um periférico HID que o próprio
  Android já está gerenciando como dispositivo de entrada.

A classe pública `BluetoothHidDevice` faz o oposto do que precisamos (serve
para o celular *se anunciar como* periférico HID para outro host, não para
enviar comandos a um periférico já conectado).

**Na prática**: em qualquer Android de fábrica, sem root, a tentativa de
abrir um socket L2CAP direto no PSM 0x11 (implementada em
`BluetoothLedSender.kt`, usando `BluetoothSocket.createInsecureL2capChannel`,
disponível a partir do Android 10) **falha com uma IOException de conexão
recusada**, porque o canal já está ocupado pelo sistema. O app **detecta e
informa esse erro claramente na tela**, ao invés de fingir sucesso — não
existe simulação: se a LED não mudou de verdade, o app mostra a mensagem de
falha.

### A alternativa que realmente funciona, sem root: cabo USB-OTG

O Android expõe uma API pública completa para USB
(`UsbManager`/`UsbDeviceConnection`), sem nenhum bloqueio do sistema. Por
isso, `Ds4UsbController.kt` implementa o caminho **que de fato funciona**:
conectar o DS4 ao celular usando um **cabo OTG** (adaptador USB-C ou
micro-USB do celular para USB-A + cabo USB-A para micro-USB do controle) e
enviar o output report real (0x05) via `controlTransfer` (SET_REPORT HID
padrão). Este é o caminho recomendado para controlar a LED de verdade hoje.

### Se você tiver um celular com root

Com root é possível liberar o PSM 0x11 (por exemplo, desabilitando
temporariamente o perfil HID do Bluetooth do sistema para aquele
dispositivo, ou escrevendo diretamente em `/dev/uhidX` via shell) e então o
mesmo `BluetoothLedSender.kt` tende a funcionar, pois o canal fica livre
para o app abrir.

## Como parear o DualShock 4 com o celular
1. No próprio controle, pressione e segure **Compartilhar (Share)** +
   **PS** ao mesmo tempo até a barra de luz piscar rapidamente em branco
   (modo de pareamento).
2. No celular: *Configurações > Bluetooth*, ative o Bluetooth e toque em
   "Parear novo dispositivo".
3. O controle aparece como **"Wireless Controller"** — toque para parear.
4. Depois de pareado, abra o app DS4 LED Control e toque em
   "Procurar (BT)" para ele aparecer na lista (ou já conectar
   automaticamente se estiver na lista de pareados).

Lembre-se: mesmo pareado corretamente, o controle da LED por Bluetooth
esbarra na limitação explicada acima em aparelhos sem root — use o cabo USB
para controle real da cor.

## Como usar o aplicativo
1. Abra o app. A tela inicial mostra "Status: Desconectado".
2. Toque em "Procurar (BT)" para localizar o controle por Bluetooth (ou
   "Conectar (USB)" se estiver usando um cabo OTG).
3. Conceda as permissões de Bluetooth solicitadas (Android 12+: 
   "Dispositivos próximos").
4. Após conectar, a tela muda para "CONTROLE CONECTADO ✓" e mostra os
   controles de cor: sliders RGB, cores rápidas, brilho e pulsação.
5. Ajuste a cor e toque em "Aplicar cor".

## Limitações conhecidas
- **Controle real da LED via Bluetooth não funciona em Android sem root**,
  pelo motivo detalhado acima — isto é uma limitação do sistema Android, não
  do app. O app tenta, mas mostra claramente a falha em vez de simular
  sucesso.
- O caminho **funcional sem root é apenas via cabo USB-OTG**.
- A identificação do DS4 na varredura Bluetooth é feita pelo nome anunciado
  ("Wireless Controller"), pois o Android não expõe um UUID de serviço
  específico e trivial para diferenciar modelos de gamepad na varredura.
- O CRC32 exigido pelo firmware mais novo (revisão 2 / CUH-ZCT2x) é
  calculado e enviado sempre; controles antigos ignoram os bytes extras sem
  problema.
- O app não implementa leitura de botões/analógicos (fora do escopo pedido:
  apenas controle da LED).

## Novidades: bateria e aba de teste do controle

- **Indicador de bateria (%)**: funciona de verdade quando o DS4 esta ligado
  por **cabo USB** (le o relatorio de entrada bruto do controle, que traz o
  nivel de bateria). Via Bluetooth sem root **nao da** pra pegar a % exata,
  porque a API que faria isso (`BluetoothDevice.getBatteryLevel()`) e uma API
  interna do sistema Android (`@hide`), inacessivel a apps comuns -- o app
  avisa isso claramente na aba, em vez de inventar um numero.
- **Aba "Testar controle"**: mostra ao vivo os analogicos, gatilhos L2/R2,
  botoes e D-pad.
  - Por **Bluetooth**: funciona sem root! Diferente de escrever a cor da LED,
    LER botoes/analogicos e um recurso padrao e publico do Android (o DS4
    aparece como um gamepad comum assim que pareado).
  - Por **USB**: os valores vem do mesmo relatorio bruto usado pela bateria.

## Novidades: efeitos brincalhões e roda de cores

- **Botão "Efeitos ✨"** (abaixo de "Aplicar cor"): abre pisca-pisca, sirene
  de polícia (vermelho/azul alternando), arco-íris e respiração. Como o
  hardware do DS4 só sabe pulsar UMA cor sozinho, esses efeitos funcionam
  com o app reenviando comandos de cor em loop, em uma thread separada, até
  você tocar em "Parar efeito" (ou aplicar uma cor manual, que também para
  o efeito).
- **Roda de cores (bolinha arrastável)**: abaixo dos sliders RGB, uma roda
  de matiz/saturação onde você arrasta uma bolinha para escolher a cor
  visualmente, além dos sliders e das cores rápidas.


## Macros — o que funciona de verdade e o que não

**Antes de tudo, a limitação mais importante deste recurso:** o Android **não
permite que nenhum app comum injete um aperto de botão dentro de outro
app/jogo**. Isso exige a permissão de sistema `android.permission.INJECT_EVENTS`,
concedida só a apps assinados com a assinatura do próprio Android — nenhum
app instalado normalmente consegue isso, com ou sem as configurações certas.
Não é uma limitação deste projeto: é assim que funcionam os apps reais desse
tipo na Play Store (ex.: "Buttons Remapper"), que soam parecidos mas, na
prática, simulam **toques na tela** (não apertos de gamepad) via
Accessibility, ou dependem de ferramentas externas como o Shizuku.

Por isso, o que este app entrega é honesto sobre essa fronteira:

- **Detecção do botão de gatilho**: funciona de verdade, sem root — tanto com
  o app em primeiro plano (leitura padrão de gamepad do Android) quanto em
  segundo plano, com outro jogo aberto, através de um serviço de
  Acessibilidade opcional (`Ds4MacroAccessibilityService`, ative em
  Configurações > Acessibilidade > DS4 LED Control). Esse serviço só
  **observa** os botões — nunca controla outros apps.
  - Limitação: o serviço de acessibilidade só recebe `KeyEvent` (botões
    digitais). Os gatilhos analógicos L2/R2 não têm evento de tecla — eles só
    são detectáveis com o app em primeiro plano (via `MotionEvent`). Uma
    macro com gatilho L2/R2 só dispara com o app aberto na tela.
  - Macros do tipo "repetir enquanto segurar" também só funcionam com o app
    em primeiro plano — o serviço de segundo plano não tem como saber com
    segurança quando o botão foi solto entre processos diferentes.
- **Execução da macro**: como o app não consegue "apertar" o botão dentro de
  outro jogo, a execução é **local**: você vê a macro rodando nos chips da
  aba Macros e na aba "Testar controle", e ela usa o mesmo canal de
  comandos já existente da LED (então dá pra usar rumble/flash como
  confirmação de que a macro disparou de verdade).
- **Caminho avançado (fora do escopo deste projeto, mas real)**: com o app
  gratuito [Shizuku](https://shizuku.rikka.app/) (não requer root — funciona
  via depuração sem fio do Android 11+, pareada uma vez), um app pode rodar
  com privilégios de shell ADB, que TEM permissão para `input keyevent` — e
  aí sim seria possível repassar a macro para outros jogos de verdade. Isso
  não foi implementado aqui porque depende de uma ferramenta externa que o
  usuário precisa instalar e parear manualmente; se quiser, posso adicionar
  a integração com o SDK do Shizuku num próximo passo.

### Tipos de macro implementados
- Botão → quantidade de apertos (com intervalo configurável)
- Botão → repetir enquanto segurar (intervalo configurável)
- Combinação simultânea de botões
- Sequência de botões com intervalo
- Gravação: pressione os botões do controle e o app grava a ordem, o tempo
  entre cada um e quais foram simultâneos; depois é só nomear e escolher o
  gatilho.

## Áudio — entrada de fone (P2) do controle

Investigação feita antes de implementar qualquer coisa (nenhum comando foi
inventado):

- **Via Bluetooth: pesquisado a fundo (mais uma rodada, com mais
  profundidade) e confirmado como impossível — e não é limitação do
  Android.** O DS4 não implementa nenhum perfil de áudio Bluetooth padrão
  (nem A2DP nem HFP) — e isso vale até para o próprio **PS4**: a Sony
  confirmou publicamente que o PS4 nunca suportou A2DP, de propósito, por
  causa do atraso de 100–200ms que esse perfil introduz.
  Então como o fone funciona sem fio num PS4 de verdade? Por um
  **protocolo proprietário da Sony**, embutido no mesmo link Bluetooth
  customizado dos botões — não um perfil Bluetooth padrão que qualquer chip
  entenda. A wiki oficial do projeto DS4Windows confirma isso literalmente:
  "o áudio do DS4 só funciona por USB com dongles Bluetooth genéricos; para
  áudio por Bluetooth, é necessário o dongle Bluetooth oficial da
  PlayStation." Ou seja, nem no PC com Bluetooth genérico funciona.
  Na própria *issue* pública pedindo esse recurso no DS4Windows
  (github.com/Jays2Kings/DS4Windows/issues/17), os mantenedores — que já
  tinham decifrado rumble, LED, touchpad e giroscópio do controle — relatam
  que, mesmo capturando pacotes reais por anos, nunca descobriram como esse
  áudio é transmitido; o pedido está sem solução pública até hoje.
  Conclusão: isso não é uma restrição do Android (diferente da LED via
  Bluetooth, que só precisa de root) — é um protocolo proprietário da Sony
  que a comunidade de engenharia reversa nunca decifrou publicamente, e que
  parece depender de hardware/firmware Bluetooth específico do dongle
  oficial, não do chip genérico de qualquer celular. Nem com root isso
  seria implementável sem essa informação, que simplesmente não existe
  documentada em lugar nenhum. O app mostra essa limitação claramente na
  aba Áudio, sem fingir que funciona.
- **Via cabo USB: pode funcionar automaticamente.** O DS4 revisão 2 expõe
  uma interface separada de "USB Audio Class" quando ligado por cabo — a
  mesma tecnologia de qualquer fone/placa de som USB padrão, sem driver
  especial (é por isso que em um PC o fone do DS4 funciona plugando o cabo,
  sem instalar nada). O Android também tem suporte nativo a isso desde a
  versão 5. Como o app só reivindica a interface HID (não o dispositivo USB
  inteiro), a interface de áudio fica livre para o Android gerenciar
  sozinho — o app não faz nada para isso funcionar, só consegue **checar e
  mostrar** se o Android reconheceu essa saída de áudio (via `AudioManager`).
