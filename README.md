# MGGX PC Control

Aplicación Android nativa para controlar una PC mediante un relay Android en casa, Tailscale, Wake-on-LAN y Moonlight.

## Compilar e instalar

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

La aplicación requiere Android 6.0 o superior. Abrila y activá `Ajustes → Modo Demo` para probar los estados sin hardware. En modo real configurá la URL del relay, PC ID y token Bearer; instalá Tailscale y Moonlight por separado. MGGX PC Control abre las aplicaciones instaladas mediante intents estándar y usa un fallback a Play Store si no están disponibles.

El contrato completo está en `docs/RELAY_API.md`.
