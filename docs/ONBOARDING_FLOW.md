# Onboarding flow

The progress step is persisted in DataStore and resumes after process death.

1. Select **celular de uso cotidiano** or **celular que quedará en casa**.
2. Control phone verifies Tailscale and Moonlight installation, then explains same-account setup.
3. Home phone verifies Tailscale, guides Always-on VPN and battery exemption, then starts its
   foreground connection service.
4. PC/phone pairing uses short-lived QR secrets. Advanced legacy fields are intentionally excluded
   from normal setup.
5. Sunshine is explained in plain Spanish.
6. Moonlight is configured **first with the LAN address**, pairs its PIN, and then adds the
   Tailscale address without deleting the LAN connection.
7. The final verification checks control phone → home phone → Agent → Sunshine.

Permissions are contextual: camera is only for QR scanning, notification permission only for home
mode, and battery exemption is requested only from its dedicated step.
