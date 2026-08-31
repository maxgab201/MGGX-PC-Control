# MGGX PC Control 2 architecture

`pccontrol2` is a second Android application. Its `applicationId` is
`com.mggx.pccontrol.next`; it has its own app sandbox, DataStore name, Keystore aliases,
notification channel, services, widgets and tile classes. The existing `:app` module remains
unchanged and installable.

There are two persisted roles:

- **Control phone**: speaks only to the home phone for PC control. Moonlight may directly reach
  the PC through Tailscale for streaming.
- **Home phone**: owns the native private HTTP server, sends Wake-on-LAN, and speaks to the PC
  Agent on the home network.

The home phone uses `HomeDeviceService` only while its home role is enabled. It runs an embedded
Ktor CIO server in a foreground `dataSync` service, reports `HomeRuntimeState`, and restores after
boot using a receiver with a WorkManager fallback. It does not use Termux, Python, proot or shell
scripts.

Secrets are isolated in `NextSecureCredentialStore`: `agent`, `home_controller`,
`home_control`, and `legacy_relay` use separate Android Keystore AES-GCM keys. No credential is
placed in DataStore, diagnostics, QR logs or notifications.
