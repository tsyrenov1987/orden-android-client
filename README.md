# Orden — Android VPN Client

![platform](https://img.shields.io/badge/platform-Android-3ddc84)
![core](https://img.shields.io/badge/core-sing--box-blue)
![protocols](https://img.shields.io/badge/protocols-VLESS--Reality%20%7C%20Hysteria2-orange)
![license](https://img.shields.io/badge/license-MIT-green)
![logs](https://img.shields.io/badge/logs-none-brightgreen)

A privacy-first Android VPN client built on the [sing-box](https://github.com/SagerNet/sing-box) core.
Paste a subscription link (or redeem an access code) and connect — no account, on-device, no logs.

**Website:** [joinorden.com](https://joinorden.com) · **Download:** [latest release](../../releases/latest)

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

## What survives DPI filtering in 2026 — field notes

Operational notes from running this client and its nodes against Russian ТСПУ filtering. They are
protocol-level and apply to any sing-box/Xray client, not just this one.

- **The brand of a VPN does not matter; the protocol does.** Blocking is done by traffic signature,
  so switching server country changes nothing while the protocol stays recognisable.
- **Plain WireGuard and OpenVPN go first.** WireGuard's handshake is a fixed-size, fixed-shape first
  packet; OpenVPN carries a recognisable opcode. Both are detectable on the first packet, before any
  payload is seen.
- **VLESS + XTLS-Reality survives longest.** Reality borrows the real TLS handshake of a large cover
  site, so the SNI, the certificate and the handshake all belong to a genuine popular domain. No own
  domain or certificate is required. Blocking it means blocking the cover site.
- **Hysteria2 (QUIC/UDP, Salamander obfuscation) wins on lossy links** — mobile networks, long routes,
  packet loss — because it does not collapse on retransmits. But some networks throttle or drop UDP
  wholesale, so it is a second protocol, not a replacement: keep both and switch automatically.
- **Endpoint reputation matters more than country.** Addresses shared by thousands of users are
  identified by volume and blocklisted in batches; rotation and a pool of addresses beat picking a
  "better" country.
- **Split-tunnelling is not a convenience, it is a requirement.** Russian banking and government
  services break on a foreign IP, so `.ru` traffic must keep the real IP while everything else is
  tunnelled — otherwise people simply turn the VPN off.
- **Health-checks must target an IP, not a domain** — a DNS lookup made through a half-dead tunnel
  hangs, and the check reports "alive" long after the node stopped passing traffic. This and five
  more production gotchas are written up in
  [orden-singbox-configs](https://github.com/tsyrenov1987/orden-singbox-configs).

Longer write-ups with the user-facing symptoms: [what actually works in Russia in
2026](https://joinorden.com/kakoy-vpn-rabotaet-v-rossii-2026) ·
[why a VPN that worked yesterday stops today](https://joinorden.com/pochemu-vpn-ne-rabotaet) ·
[YouTube still broken with a VPN on](https://joinorden.com/yutub-ne-rabotaet-s-vpn) ·
[what ТСПУ is](https://joinorden.com/chto-takoe-tspu). A plain-text dump of all of them, for
offline or machine reading, lives at [llms-full.txt](https://joinorden.com/llms-full.txt).

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

The sing-box configuration this client generates is published separately, with credentials stripped:
**[orden-singbox-configs](https://github.com/tsyrenov1987/orden-singbox-configs)** — VLESS-Reality ↔
Hysteria2 auto-failover, Russian split-tunnel, and six production gotchas explained (why the `urltest`
health-check must target an IP, why rule-sets have to be bundled rather than fetched, why tunnelled
IPv6 has to be rejected, and three more).

## Privacy

The client collects nothing. It talks only to (a) the node(s) in your subscription and (b) the backend
that redeems your access code and returns your account state. No third-party analytics.

## На русском

Orden — приватный VPN-клиент для Android с открытым исходным кодом (ядро sing-box). Современные
DPI-устойчивые протоколы **VLESS-Reality** и **Hysteria2/Salamander** с автоматическим переключением
между серверами: подписка сама обновляет список рабочих узлов, не нужно каждую неделю вручную искать
новые ключи. Всё на устройстве, без аккаунта, без логов, без сторонней аналитики. Код открыт — можно
проверить, что именно делает приложение, перед запуском. Лицензия MIT.

## Guides / Гайды

Troubleshooting and setup guides (RU) for people running this client on restricted networks:

- [Почему VPN не работает](https://joinorden.com/pochemu-vpn-ne-rabotaet) · [Интернет пропадает при включённом VPN](https://joinorden.com/internet-propadaet-s-vpn)
- [VPN на Android без Google Play](https://joinorden.com/vpn-android-without-google-play) · [Как настроить на iPhone](https://joinorden.com/kak-ustanovit-vpn-na-iphone-v-rossii)
- [Что такое ТСПУ](https://joinorden.com/chto-takoe-tspu) · [Какой VPN работает в России 2026](https://joinorden.com/kakoy-vpn-rabotaet-v-rossii-2026)
- [sing-box: configuration is invalid](https://joinorden.com/sing-box-configuration-is-invalid) · [sing-box не запускается](https://joinorden.com/sing-box-ne-zapuskaetsya)
- [Почему ключи из каналов умирают](https://joinorden.com/pochemu-klyuchi-umirayut) · [Что такое VLESS Reality](https://joinorden.com/vless-reality) · [Hysteria2](https://joinorden.com/hysteria2)
- На мобильной сети: [МТС](https://joinorden.com/vpn-ne-rabotaet-na-mts) · [Билайн](https://joinorden.com/vpn-ne-rabotaet-na-beeline) · [все операторы](https://joinorden.com/vpn-dlya-mobilnyh-operatorov-rossii)

## License

MIT — see [LICENSE](LICENSE).

---

*Orden is operated outside the Russian Federation. This client is a general-purpose privacy tool; use it
in accordance with the laws that apply to you.*
