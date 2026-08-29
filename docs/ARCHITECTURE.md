# Architecture

The app is a native Kotlin/Compose Android application. `domain` contains the state model and `PcRelayApi` contract. `data` contains `DemoRelayApi` and `HttpRelayApi`; UI code depends on the contract rather than a protocol. `PcViewModel` owns the state machine and cancels coroutine work with its lifecycle. DataStore/Keystore integration is reserved in the settings boundary for the next hardware-connected iteration; the demo build never stores secrets.

The offline-first UI starts without a relay and uses Demo Mode. The HTTP client uses explicit endpoints, bounded timeouts and bearer authentication.
