# Security v2

- No public port forwarding or cloud relay is used. Tailscale carries remote traffic.
- Controller, Agent and pairing credentials are separate.
- Tokens are AES-GCM encrypted using Android Keystore aliases unique to the second application.
- Pairing QR payloads contain a high-entropy temporary secret; six-digit codes are not credentials.
- HTTP clients disable normal and SSL redirects before sending Authorization headers.
- Logs and reports omit Authorization headers, tokens, secrets and ciphertext.
- The server accepts only explicit power/service endpoints. It has no arbitrary command endpoint.
- WOL config is received during Agent pairing when available. Manual entry exists only as an
  Advanced migration fallback.
