# MGGX Relay: contrato esperado por Android 1.0.2

Android se comunica únicamente con el Relay configurado por el usuario. El Relay es responsable de hablar con el MGGX PC Agent por LAN. Las solicitudes autenticadas usan `Authorization: Bearer <RELAY_TOKEN>`; `/health` no usa autenticación. El cliente no sigue redirects para que el token no pueda salir a otro host.

## Health

`GET /health` devuelve `200`:

```json
{"ok":true,"service":"mggx-relay","version":1}
```

`version` puede ser 1 o posterior mientras mantenga compatibilidad. Esta ruta verifica Android → Tailscale → Relay sin mezclar credenciales ni PC.

## Estado

`GET /api/v1/status` devuelve `200` y admite tanto el formato legado como las propiedades nuevas opcionales:

```json
{
  "ok": true,
  "apiVersion": 1,
  "pcId": "main",
  "name": "MGGX PC",
  "state": "online",
  "lastSeen": "2026-08-29T04:00:00Z",
  "agent": {"reachable": true, "version": "1.0.0", "uptimeSeconds": 12345},
  "sunshine": {"installed": true, "running": true},
  "tailscale": {"installed": true, "running": true, "ip": "100.x.x.x"},
  "capabilities": {"wake": true, "shutdown": true, "restart": true, "sleep": true, "hibernate": true, "lock": true, "sunshineRestart": true}
}
```

Estados soportados: `unknown`, `offline`, `waking`, `online`, `shutting_down`, `restarting`, `sleeping`, `hibernating`, `connecting` y `error`. `offline` es una respuesta válida: Relay y autenticación están correctos, la PC está apagada. Un Relay legado puede omitir los bloques `agent`, `sunshine`, `tailscale` y `capabilities`; Android los muestra como desconocidos y conserva Wake disponible.

## Comandos

Todas las rutas son `POST` autenticados y devuelven `202 Accepted` cuando la orden fue aceptada, no necesariamente cuando terminó:

| Acción | Ruta |
| --- | --- |
| Wake | `/api/v1/power/wake` |
| Apagar | `/api/v1/power/shutdown` |
| Reiniciar | `/api/v1/power/restart` |
| Suspender | `/api/v1/power/sleep` |
| Hibernar | `/api/v1/power/hibernate` |
| Bloquear | `/api/v1/power/lock` |
| Reiniciar Sunshine | `/api/v1/services/sunshine/restart` |

Para Wake Android sondea status cada dos segundos, hasta 120 segundos. Para restart observa temporalmente `offline` y después `online`, hasta tres minutos. Shutdown, sleep e hibernate se confirman solamente cuando status dice `offline`. Sunshine se reinicia como máximo una vez durante Abrir PC.

## Errores

`401` significa token incorrecto, `403` autorización denegada, `404` versión/ruta incompatible, `409` error funcional (por ejemplo `pc_offline` o `sunshine_not_installed`), `429` exceso de solicitudes y `5xx` error de servidor alcanzable. Un `409` debería incluir `{ "error": "machine_readable_code" }`. El Relay no debe redirigir solicitudes autenticadas.
