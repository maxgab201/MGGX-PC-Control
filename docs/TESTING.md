# Testing

Run:

```bash
./gradlew clean assembleDebug test lint
```

The emulator/device acceptance path is: open app → Home → Demo Mode → select Offline → Prender PC → Waking → Online. Hardware validation remains required for relay reachability, Wake-on-LAN, Sunshine and real Moonlight streaming.

GitHub Actions runs the same tasks and publishes `MGGX-PC-Control-universal-debug.apk` as a workflow artifact.
