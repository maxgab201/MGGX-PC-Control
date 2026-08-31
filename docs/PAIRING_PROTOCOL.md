# Pairing protocol v1

Pairing QR data uses a strict versioned URI:

```text
mggx://pair/v1?host=<host>&port=<port>&secret=<base64url-32-bytes>&expires=<epoch-ms>&role=<home_phone|control_phone>
```

The scanner accepts only scheme `mggx`, host `pair`, path `/v1`, a valid port, a 43-character
base64url secret and an unexpired timestamp. Unknown versions fail closed. The secret is 256-bit,
temporary (default ten minutes) and consumed once. A six digit code is a visual confirmation only;
it is never sufficient to authenticate a pairing request.

The home phone exposes `POST /api/v1/pair/claim` while an offer is active. The request provides the
secret. On success the home phone creates a new controller credential, encrypts it with Android
Keystore, consumes the offer, and returns the new credential only over the already private
Tailscale path. Pairing attempts must be rate-limited by the production UI/service before wider
distribution.

PC Agent pairing is specified for Agent 1.1: the Agent must offer the same temporary-secret
exchange and return Agent credential, PC ID, display name, LAN/Tailnet addresses and WoL data. The
legacy manual path remains under Advanced solely for migration/testing.
# Implementation status

Alpha2 includes an in-app CameraX + ML Kit QR scanner, strict payload validation, expiry checks, role checks, single-use claims, QR generation, countdown, and Keystore storage. PC Agent automatic pairing additionally depends on the Agent 1.1 contract in [PC_AGENT_PAIRING_V1.md](PC_AGENT_PAIRING_V1.md); Agent 1.0 has no claim endpoint.
