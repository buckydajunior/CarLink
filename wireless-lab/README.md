# F-150 Wireless Lab

A separate Android app for investigating wireless CarPlay on the user's 2018
F-150 Linkswell. This branch starts from the wired v1.2.5 source, commit
`76948d36b351b2a37ecdfd898307f0af00906df6`.

**Version 0.1.2 is a read-only radio and OEM-app identification reporter, not a CarPlay receiver.**
The wired APK, its package, USB behavior, settings and EQ are unchanged.

## Install and use

Install `F150-Wireless-Lab-0.1.2-test.apk`, then open **F-150 Wireless Lab** from
the Linkswell app list. Tap **Check compatibility**, then **Share report** and
return the text file for review. If sharing isn't available on the head unit,
the on-screen report is selectable and can also be photographed while scrolling.
The report can be collected without an iPhone connected or paired to this app.

The working Linkswell Bluetooth app connects to the user's iPhone, but the Android
page opened by 0.1.1 finds nothing in either direction. Do not repeat pairing on
that page. Tap **Check compatibility** and **Share report** instead. The optional
Android settings shortcut remains available for other diagnostic use; returning
refreshes the passive report once. An accepted start request is not evidence of
a usable Bluetooth stack.

Android 8.1 uses the normal Bluetooth permission granted at installation. On
Android 12 or newer, Check requests Bluetooth Connect permission; denied access
is recorded while the remaining checks continue. The app itself never enables radios,
scans, pairs, creates a hotspot, connects to a phone or starts a projection session.

The first report should be taken with Bluetooth and Wi-Fi already on through the
head unit's normal controls. The app does not change these controls. If either is
off, dependent results are left untested rather than interpreted as absent hardware.

## What it checks

- Android model/build/CPU details, feature flags and visible framework services.
- Android Bluetooth adapter visibility, enabled state and paired-device **count**.
- Android Wi-Fi enabled state and advertised 5 GHz support while Wi-Fi is on.
- Wi-Fi Direct feature/service visibility.
- Narrow Bluetooth-settings handler and enabled/exported Android page metadata.
- Default Home component, Android Bluetooth and ZLink package versions, name-matched
  Bluetooth/projection candidates and up to four ZXW vendor-name fallbacks.
- Declared service names/exported/enabled/required permission, bounded to 24 per app;
  candidates are not confirmed usable interfaces. Only ZLink's `TD_CHANNEL_ID`
  metadata is read, with a restricted value format; other metadata is not requested.
- `/dev/rf_serial` visibility and read/write permission metadata only. The node is
  **never opened**, and no service is started, bound or contacted. Permission flags
  do not prove actual access, transport ownership or suitability.

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

The report includes relevant app versions and declared component names, but no full
app inventory, private app files, phone names, Bluetooth MACs, Wi-Fi SSIDs/BSSIDs, pairing
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
reporting. Twelve native layout renders cover landscape head unit, portrait head
unit and phone; gallery report values are synthetic layout fixtures, not measured
radio results. The earlier Android capability report and settings navigation were tested on the
Linkswell. The new OEM-app report fields and wireless CarPlay remain unverified on
the unit until the user returns the new report and a later connection test runs.

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

## Baseline 0.1.0 validation — 2026-11-21

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

## Linkswell follow-up — 2026-11-21

The user reports that Android Connected devices offers Cast and USB but no Bluetooth
entry. Returned reports show a visible/enabled adapter with read permission and an
Android paired-device count of 0, Wi-Fi enabled with advertised 5GHz support No, and
Wi-Fi Direct feature/service Yes. The first report was taken before phone pairing;
a subsequent photo still showed 0 after the requested pairing step. This suggests
an OEM-managed pairing path, but neither module identity nor socket usability is
established. The 0.1.1 shortcut is the next navigation check; no phone connection,
radio change, hotspot or projection is initiated by the lab. User photos are not
stored in the repository.

## Version 0.1.1 validation — 2026-11-21

- 47 ordinary test executions passed: 9 report cases, 10 API27/33 isolation cases,
 22 API27/33 navigation cases and 6 API27/33 Activity return/failure/visibility cases.
- Native gallery runner passed and generated 9 refreshed head-unit/portrait/phone
 renders. Debug, R8 release, release vital lint and full debug lint passed.
- Signed APK is 109,524 bytes; SHA-256
 `310bd340b334ff499e5ed6ef97754e660006db9f7c371ae6688a9f21ab0813df`. V2/V3 signature uses the same local test certificate as 0.1.0.
- Verified package/version 101, unchanged read-only permissions, one launcher,
 scoped provider, narrow Settings queries, licenses and no engine/native/auth assets.
- Existing wired 1.3.0 APK/latest alias and previous Lab 0.1.0 APK hashes are unchanged.
- The actual Linkswell settings route remains unverified until the user tries the
 button. No Bluetooth socket, hotspot, authentication or CarPlay test has run.

Known public Bluetooth settings components follow the
[AOSP Android 8.1 Settings manifest](https://raw.githubusercontent.com/aosp-mirror/platform_packages_apps_settings/android-8.1.0_r81/AndroidManifest.xml).
The shortcut may still be launched when metadata queries return no handler, following
[Android package visibility guidance](https://developer.android.com/training/package-visibility/use-cases).

## Version 0.1.2 investigation — 2026-11-21

The user confirmed that OEM Bluetooth connects, while discovery in both directions
through the Android page finds nothing. Its device-name field is blank and address
footer says `null`; a visible/enabled framework adapter does not establish a usable
Android radio path. These observations support investigating an OEM interface, not
another pairing attempt. Exact module identity remains unknown.

[Linkswell's official applications page](https://www.linkswellinc.com/support/t-style-applications/)
links ZLink 4.0.34 for specified T-Style generations. Static inspection of that
public APK found `com.zjinnova.zlink`, channel `zhuoxw`, and `libzhuoxw_rk3399.so`.
The selected plugin's exports cover USB/identity hooks; generic native-library
Bluetooth strings cannot establish which route this user's firmware uses. No
vendor APK was installed, run, modified or committed. This is not a recommendation
to install ZLink on an unconfirmed unit generation.

The report uses public PackageManager APIs and narrow visibility queries; modern
Android may hide apps ([package visibility](https://developer.android.com/training/package-visibility)).
It retains missing/denied/error states and bounds candidate/service output. Broad
vendor-name fallbacks may include non-Bluetooth apps and are labelled accordingly.
It does not enumerate running services, read raw metadata bundles, open device
nodes, send module commands, attempt sockets, or load code from installed apps.
An OEM interface requiring firmware privileges may make an APK-only receiver
impractical; identification is a feasibility gate, not proof of wireless support.

## Version 0.1.2 validation — 2026-11-21

- 65 ordinary test executions passed with no failures/skips across API27/33,
  including OEM metadata privacy, candidate/service bounds, default Home matching,
  independent node errors and retention of known packages if enumeration fails.
- Debug, R8 release, release vital lint and full debug lint passed. The native
  gallery runner generated 12 head-unit/portrait/phone renders; representative
  ready and OEM report views were inspected for readable, unclipped wrapping.
- Signed APK: 113,620 bytes; SHA-256
  `8cf2405974f6a96510309d3d8147c571398c81324b7fd584f3a1ab55345ed65b`.
  Alignment, V2/V3 signatures and unchanged local test certificate were verified.
- Package/version 0.1.2/102, limited read-only permissions, single launcher,
  scoped report provider, narrow queries and absence of native/engine/auth payload
  were verified. No release/tag is published.
- Both wired 1.3.0 APK hashes and previous Lab 0.1.1 APK hash are unchanged.
- OEM identification has not yet run on the Linkswell. No Bluetooth connection,
  hotspot, authentication or wireless CarPlay success is claimed.
