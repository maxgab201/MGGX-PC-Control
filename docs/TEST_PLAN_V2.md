# Test plan v2

Unit tests cover versioned QR parsing, invalid hosts/ports, expiry, secret entropy shape,
constant-time comparison, single-use sessions and Agent Bearer forwarding. Existing copied Relay
tests retain URL normalization, legacy compatibility, 401/403/409/429, timeout and state-machine
coverage for the Advanced migration path.

Integration coverage is run with MockWebServer for the Agent gateway. The home server and actual
Android Keystore/foreground lifecycle need instrumented/device testing. CI builds both applications
and publishes the second APK separately. Hardware acceptance still requires two Android phones, a
Windows PC Agent, Tailscale, Sunshine and Moonlight.
