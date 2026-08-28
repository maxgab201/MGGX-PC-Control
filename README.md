# MGGX PC Control

Aplicación Android nativa para controlar una PC mediante un relay Android en casa, Tailscale, Wake-on-LAN y Moonlight.

## Compilar e instalar

```bash
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

La aplicación requiere Android 6.0 o superior. Abrila y activá `Ajustes → Modo Demo` para probar los estados sin hardware. En modo real se aceptan URLs como `100.x.x.x:8765` o `http://100.x.x.x:8765`; al tocar **Probar conexión**, Diagnostics muestra URL, VPN, TCP, `/health`, autenticación y estado de PC por separado. Instalá Tailscale y Moonlight por separado.

El contrato completo está en `docs/RELAY_API.md`.
