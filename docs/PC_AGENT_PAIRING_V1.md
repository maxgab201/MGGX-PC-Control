# MGGX PC Agent 1.1 pairing contract

Status: the Android client side is implemented in MGGX PC Control 2 alpha2. The current PC Agent 1.0 repository does **not** expose the pairing endpoints below; until Agent 1.1 implements them, Android shows a guided Agent 1.0 fallback and validates `/health` plus authenticated `/api/v1/status` before saving.

## Offer shown by Windows

The Agent creates a cryptographically random 32-byte, URL-safe secret. It is single-use, expires after 10 minutes, and is represented by this strictly validated QR payload:

```text
mggx://pc-agent/v1?host=<LAN-IP>&port=8766&secret=<base64url-43>&expires=<epoch-ms>
```

The QR must not contain the permanent Agent token. A six-digit display code may be shown as a human verification aid, but is never sufficient authentication by itself.

## Claim

```http
POST /api/v1/pair/claim
Content-Type: application/json
```

```json
{
  "protocolVersion": 1,
  "secret": "<single-use-secret>",
  "client": "mggx-pc-control-home"
}
```

Successful response (HTTP 200):

```json
{
  "ok": true,
  "protocolVersion": 1,
  "agentToken": "<new-permanent-agent-token>",
  "agentPort": 8766,
  "agentVersion": "1.1.0",
  "pcId": "main",
  "name": "MGGX PC",
  "lanIp": "192.168.1.20",
  "tailscaleIp": "100.64.10.20",
  "macAddress": "00:11:22:33:44:55",
  "broadcastAddress": "192.168.1.255"
}
```

Android rejects a success response missing `agentToken`, `lanIp`, `macAddress`, or `broadcastAddress`. It then verifies unauthenticated `GET /health` and authenticated `GET /api/v1/status`; only after both checks succeed does it store the token in Android Keystore and persist the non-secret network configuration.

## Required behavior

- `401`: invalid, expired, or already consumed secret.
- `404`/`501`: Agent version does not implement automatic pairing; Android presents the Agent 1.0 fallback.
- Pairing secret is consumed atomically on first successful claim.
- Rate-limit attempts and invalidate previous offers when a new one is generated.
- Never return a previously configured token. Generate a distinct token for this home phone.
- Bind the claim endpoint only to the configured private/LAN networks. Do not expose it publicly.

## Existing Agent API used after pairing

- `GET /health` without authorization.
- `GET /api/v1/status` with `Authorization: Bearer <agentToken>`.
- Explicit power and Sunshine endpoints documented in the Agent repository.

