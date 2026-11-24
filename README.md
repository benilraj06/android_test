# NetScope

A native Android network toolkit: Wi-Fi analyzer, device discovery, port scanner and network utilities.
Built with Kotlin + Jetpack Compose (Material 3).

> Only scan networks you own or have permission to test.

## Features

| Area | What it does |
|---|---|
| **Overview** | Current Wi-Fi, signal ring, IP/gateway/DNS, public IP + ISP, latency to router/internet/DNS, network security rating |
| **Wi-Fi analyzer** | Nearby networks (signal, channel, band, width, Wi-Fi standard, security, WPS, vendor), channel occupancy graph, best-channel recommendation, live signal meter |
| **Device discovery** | Subnet sweep (TCP + reachability), mDNS/Bonjour, SSDP/UPnP, NetBIOS, reverse DNS; device type detection; radar UI |
| **Device details** | Rename, mark trusted, notes, open web UI, ping/traceroute/WOL shortcuts |
| **Port scanner** | Quick / 1–1024 / all 65535 / custom ranges, banner grabbing (HTTP server, TLS cert, greetings), risk hints |
| **Tools** | Speed test (Cloudflare), ping, traceroute, DNS lookup (DoH), IP lookup, Wake-on-LAN, subnet calculator |
| **Monitoring** | Background scans with "new device joined" notifications (WorkManager) |
| **Data** | Room history, CSV/JSON export, MAC vendor database download |

## Android limits

- Android 10+ hides other devices' MAC addresses; vendor detection relies on names/services (NetBIOS can still reveal Windows MACs).
- Wi-Fi scans are throttled to 4 per 2 minutes per app.
- Location permission (and Location turned on) is required to read Wi-Fi names and scan results.
- No root: TCP connect scans only (no SYN/UDP scans).

## Build

- AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.11.1 · JDK 17
- minSdk 24 · target/compileSdk 35

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. CI builds it on every push and uploads it as the `netscope-debug` artifact.
