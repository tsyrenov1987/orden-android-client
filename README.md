# Orden — Android VPN Client

![platform](https://img.shields.io/badge/platform-Android-3ddc84)
![core](https://img.shields.io/badge/core-sing--box-blue)
![protocols](https://img.shields.io/badge/protocols-VLESS--Reality%20%7C%20Hysteria2-orange)
![license](https://img.shields.io/badge/license-MIT-green)
![logs](https://img.shields.io/badge/logs-none-brightgreen)

A privacy-first Android VPN client built on the [sing-box](https://github.com/SagerNet/sing-box) core.
Paste a subscription link (or redeem an access code) and connect — no account, on-device, no logs.

**Website:** [orden-351.pages.dev](https://orden-351.pages.dev) · **Download:** [latest release](../../releases/latest)

Modern DPI-resistant protocols (VLESS-Reality, Hysteria2/Salamander) with automatic failover — built to
keep working on restricted, deep-packet-inspected networks where plain WireGuard/OpenVPN get blocked.

## Why

Most mobile VPN clients are closed-source and ask you to trust a black box with all your traffic.
Orden is the opposite: the client is open, the connection config lives on your device, and there is
no analytics or logging built in. You can read exactly what it does before you run it.

## Features

- **Modern, censorship-resistant protocols:** VLESS + XTLS-Reality and Hysteria2 (Salamander obfuscation),
  with automatic per-node failover (`urltest`) so a dead server is skipped without you noticing.
- **Subscription-based:** paste one subscription URL; the client fetches and refreshes the node list itself.
- **On-device, no account, no logs:** no telemetry, no analytics SDKs, no sign-up.
- **Self-host friendly:** point it at your own sing-box / Xray nodes — the parser understands standard
  `vless://`, `ss://` and `hysteria2://` share links.
- **Small & focused:** a clean Kotlin/Compose UI over the sing-box core, nothing else.

## Build

Standard Android project (Kotlin + Jetpack Compose).

```bash
./gradlew :app:assembleDebug        # debug build
./gradlew :app:assembleRelease -PabiSplit   # per-ABI release (requires signing config)
```

Release signing is loaded from a local `keystore.properties` (gitignored) — supply your own to build a
signed release. The sing-box core (`Libbox`) is built separately; see the build notes in `server/`.

## Configuration

App endpoints live in `app/src/main/java/club/orden/vpn/TunnelConfig.kt`. There are **no credentials in
source** — you supply your own node via a subscription URL at runtime.

## Privacy

The client collects nothing. It talks only to (a) the node(s) in your subscription and (b) the backend
that redeems your access code and returns your account state. No third-party analytics.

## На русском

Orden — приватный VPN-клиент для Android с открытым исходным кодом (ядро sing-box). Современные
DPI-устойчивые протоколы **VLESS-Reality** и **Hysteria2/Salamander** с автоматическим переключением
между серверами: подписка сама обновляет список рабочих узлов, не нужно каждую неделю вручную искать
новые ключи. Всё на устройстве, без аккаунта, без логов, без сторонней аналитики. Код открыт — можно
проверить, что именно делает приложение, перед запуском. Лицензия MIT.

## License

MIT — see [LICENSE](LICENSE).

---

*Orden is operated outside the Russian Federation. This client is a general-purpose privacy tool; use it
in accordance with the laws that apply to you.*
