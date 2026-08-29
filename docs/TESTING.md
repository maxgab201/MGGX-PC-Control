# Testing MGGX PC Control 1.0.2

## Local y CI

```bash
./gradlew clean
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

La CI ejecuta tests unitarios con MockWebServer, tests instrumentados Compose en emulador API 35, lint, ensamblado, inspección mediante `apksigner` y SHA-256 del APK.

## Cobertura principal

- Normalización de URL privada/Tailnet, MagicDNS, IPv6 y rechazo de HTTP público.
- Health del Relay, autenticación Bearer, status legado y status Agent-aware.
- Respuestas 202, 401, 403, 404, 409, 429, 5xx, JSON inválido y timeout.
- Orquestación Abrir PC, Wake, Sunshine y transiciones de apagado/reinicio.
- Estados visuales Idle, Loading, Success y Error de botones Compose.

## Límites de validación

No se prueba hardware físico desde CI: Tailscale real, MGGX Relay real, Wake-on-LAN, Windows Agent, Sunshine y el lanzamiento real de Moonlight requieren los dispositivos. El modo Demo cubre la interfaz sin hardware.
