# Home-device mode

The home phone requires Android 8+ and should remain on Wi-Fi and power. Its onboarding guides the
user to enable Tailscale Always-on VPN without enabling “block connections without VPN”, and to
remove battery restrictions for MGGX PC Control 2 and Tailscale.

`HomeDeviceService` publishes these independent states: server starting/ready, network unavailable,
Tailscale unavailable, PC offline/online, Agent authorization error and unexpected error. A PC
being off is never represented as Tailscale or service failure.

The embedded server defaults to port 8765 and exposes `/health` without credentials. `/api/v1/*`
requires the controller credential. It forwards authenticated Agent actions without redirects and
only sends Wake-on-LAN from the home network. The foreground notification contains no endpoint,
token, pairing secret or Agent credential.
