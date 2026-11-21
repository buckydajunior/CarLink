# F-150 Wireless Lab

A separate Android app for investigating wireless CarPlay on the user's 2018
F-150 Linkswell. This branch starts from the wired v1.2.5 source, commit
`76948d36b351b2a37ecdfd898307f0af00906df6`.

**Version 0.1.0 is a passive compatibility reporter, not a CarPlay receiver.**
The wired APK, its package, USB behavior, settings and EQ are unchanged.

## Install and use

Install `F150-Wireless-Lab-0.1.0-test.apk`, then open **F-150 Wireless Lab** from
the Linkswell app list. Tap **Check compatibility**, then **Share report** and
return the text file for review. If sharing isn't available on the head unit,
the on-screen report is selectable and can also be photographed while scrolling.
The report can be collected without an iPhone connected or paired to this app.

Android 8.1 uses the normal Bluetooth permission granted at installation. On
Android 12 or newer, Check requests Bluetooth Connect permission; denied access
is recorded while the remaining checks continue. The app never enables radios,
scans, pairs, creates a hotspot, connects to a phone or starts a projection session.

The first report should be taken with Bluetooth and Wi-Fi already on through the
head unit's normal controls. The app does not change these controls. If either is
off, dependent results are left untested rather than interpreted as absent hardware.

## What it checks

- Android model/build/CPU details, feature flags and visible framework services.
- Android Bluetooth adapter visibility, enabled state and paired-device **count**.
- Android Wi-Fi enabled state and advertised 5 GHz support while Wi-Fi is on.
- Wi-Fi Direct feature/service visibility.

The user's photos show Android 8.1.0/API 27, arm64-v8a and RK3399/PX6-class
hardware (`rkpx6` in About tablet). They do not establish the exact Linkswell
commercial generation. The user pairs calls/music inside Linkswell's Bluetooth
app and will test with an iPhone Air; exact iOS version is not yet recorded.
No photos or device identifiers from those photos are included in source.

This check cannot prove that Android RFCOMM sockets, hotspot creation or a CarPlay
handshake will work. An OEM Bluetooth app working does not prove ordinary apps
can use that radio. An adapter being absent from Android also does not by itself
identify a specific external module. A zero paired-device count can reflect
framework limitations. The next stage needs the report and a deliberate live
connection test, with the appropriate transport selected from that evidence.

## Isolation and privacy

Package: `com.buckydajunior.carlink.f150.wirelesslab`.
There is one ordinary launcher and a nonexported report FileProvider. No USB or
boot intent filters, receiver, service, VPN, projection engine, runtime identity,
network connection, microphone access or radio-changing permissions are included.
The optional transitive profile installer is excluded; this small native diagnostic app
does not need a startup initializer or its exported profile-install receiver.

The report contains no phone names, Bluetooth MACs, Wi-Fi SSIDs/BSSIDs, pairing
keys, IMEI, serial or Android ID. Binder failures retain only exception class
names. Reports stay in private cache until the user explicitly opens Android's
share chooser. Only `cache/reports/` can be shared through the provider, with a
temporary read grant. At most five report files are kept; clear app data or
uninstall to remove them. No automatic upload exists (no Internet permission).

## Development

With JDK/Android SDK configured, from the repository root:

```sh
./gradlew --no-daemon :wireless-lab:testDebugUnitTest :wireless-lab:assembleDebug :wireless-lab:assembleRelease
./gradlew --no-daemon :wireless-lab:testDebugUnitTest -Pcarlink.lab.screenshots=true --tests 'com.carlink.wirelesslab.gallery.*'
```

Release runs R8 and resource shrinking. Gradle's release output is unsigned;
sign the aligned APK externally with `apksigner` and a local key. No signing
material belongs in Git. The distributed local test APK uses the existing local
debug certificate; debug and release lab builds share one package and certificate.
The module does not need CarPlay authentication assets.

Ordinary tests cover API 27/33 merged-manifest isolation and partial/denied/error
reporting. Nine native layout renders cover landscape head unit, portrait head
unit and phone; gallery report values are synthetic layout fixtures, not measured
radio results. Physical Linkswell capabilities and wireless CarPlay remain
unverified until the user returns a report and a later live connection test runs.

## Sources and licenses

[Android Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
explain the API 31 Connect permission boundary.
[AOSP Android 8.1 BluetoothAdapter](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-8.1.0_r81/core/java/android/bluetooth/BluetoothAdapter.java)
returns an empty bonded set when the radio is off.
[AOSP Android 8.1 WifiVendorHal](https://android.googlesource.com/platform/frameworks/opt/net/wifi/+/refs/tags/android-8.1.0_r81/service/java/com/android/server/wifi/WifiVendorHal.java)
can report no capabilities while the HAL is stopped.
[Apple's wireless CarPlay architecture](https://developer.apple.com/videos/play/wwdc2017/717/)
uses Bluetooth setup and Wi-Fi transport; radio presence alone is insufficient.

Based on [CarLink](https://github.com/F-e-n-y-x/CarLink) and its contributors.
The repository's AGPL-3.0 license applies. Bundled GPL/AGPL texts and original
component notices remain accessible through About & licenses; no vendor engine
source is changed or packaged by this module.

## Local validation — 2026-11-21

- 18 test executions passed: seven report cases, ten manifest/provider cases across
  API 27/33, and one native gallery runner producing nine inspected images.
- Debug and R8 release builds passed, including release vital lint. Full debug lint
  passed with three advisory warnings (dependency version, data-extraction rules,
  and the explicit Robolectric dependency outside the version catalog).
- Signed APK v2/v3 verification, package/version/permissions/components, packaged
  licenses and absence of engine/native/authentication payload were checked.
- Test APK: 105,428 bytes; SHA-256
  `d894a160b306160a7e46f489abdf9bbcce84a7a51d6c6c677fb94690cc6c531c`.
- Both existing wired APK hashes and wired source HEAD remained unchanged.
- No actual Linkswell/iPhone wireless handshake, socket or hotspot test has run.
