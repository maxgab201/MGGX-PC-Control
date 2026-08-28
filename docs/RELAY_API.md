# MGGX Relay API v1

La URL configurada es la raíz del relay. La app normaliza de forma segura `100.x.x.x:8765`, `http://100.x.x.x:8765/`, `/api/v1` y `/health`. HTTP se acepta solo para hosts privados, localhost, MagicDNS o `.ts.net`; para hosts públicos se exige HTTPS.

La prueba de conexión ejecuta, en orden: validación de URL, observación de VPN, TCP, `GET /health`, `GET /api/v1/status` y parsing. Los redirects están deshabilitados para que el Bearer token nunca salga del host configurado.

## Endpoints

- `GET /health` → `{ "ok": true, "service": "mggx-relay", "version": 1 }`
- `GET /api/v1/status` con `Authorization: Bearer <token>` → `{ "ok": true, "apiVersion": 1, "pcId": "main", "state": "offline", "lastSeen": null, "monitors": [] }`
- `POST /api/v1/power/wake` → `202 Accepted` es éxito: la orden Wake-on-LAN fue aceptada, no implica todavía que la PC esté online.
- `POST /api/v1/power/shutdown|restart|sleep|hibernate`
- `GET /api/v1/monitors`
- `POST /api/v1/monitors/{id}/activate`
- `POST /api/v1/actions/camera|terminal|files|task-manager`

## Errores

`401` significa relay alcanzable con autenticación fallida; `403` autorización denegada; `404` endpoint/API incompatible; `409` error funcional del relay; `5xx` error de servidor alcanzable. DNS, timeout, conexión rechazada, falta de ruta y permisos de red son categorías diferentes y aparecen por etapa en Diagnostics.
