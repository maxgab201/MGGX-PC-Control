# MGGX PC Control 1.0.2

Cliente Android nativo para la arquitectura privada **Android → MGGX Relay → MGGX PC Agent**. La aplicación nunca se conecta directamente al Agent de Windows. Requiere Android 6.0+ (minSdk 23), apunta a Android 16 (target/compile SDK 36) y usa Tailscale y Moonlight instalados por separado.

## Funciones

- Abrir PC: despierta una PC apagada, espera Windows y Sunshine, y después abre Moonlight.
- Apagar, reiniciar, suspender, hibernar, bloquear Windows y reiniciar Sunshine, siempre mediante el Relay.
- Estado de Relay, Agent Windows, Sunshine y Tailscale de la PC; no confunde una PC apagada con un Relay inaccesible.
- Diagnóstico por capas: URL, VPN, TCP, `/health`, autenticación y `/api/v1/status`.
- Modo Demo, widgets Glance y Quick Settings Tile.

Monitores, cámara, terminal, archivos, administración y Task Manager no forman parte de 1.0.2.

## Compilar, probar e instalar

```bash
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El workflow también ejecuta `connectedDebugAndroidTest` en un emulador API 35 y publica `MGGX-PC-Control-universal-debug-v1.0.2.apk`, su SHA-256 y estado de firma.

## Configurar

En **Ajustes**, usá la URL del Relay, por ejemplo `100.x.x.x:8765` o `http://relay.tailnet.ts.net:8765`, el token Bearer y el PC ID (por defecto `main`). Las direcciones privadas/Tailnet admiten HTTP; destinos públicos exigen HTTPS. **Guardar y probar** persiste DataStore y Keystore antes de probar exactamente esa configuración.

Activá **Modo Demo** para recorrer todos los estados sin Relay ni PC reales. El contrato que deberá cumplir el Relay está en [docs/RELAY_EXPECTATIONS.md](docs/RELAY_EXPECTATIONS.md).

## Firma estable

La 1.0.2 establece una nueva raíz de firma. El repositorio no contiene claves. Para builds actualizables se configuran en GitHub Actions `MGGX_KEYSTORE_BASE64`, `MGGX_KEYSTORE_PASSWORD`, `MGGX_KEY_ALIAS`, `MGGX_KEY_PASSWORD` y opcionalmente `MGGX_EXPECTED_CERT_SHA256`. Si faltan, CI genera un APK debug instalable, pero marca explícitamente que la firma persistente está bloqueada. Puede ser necesaria una última desinstalación de 1.0.1.
