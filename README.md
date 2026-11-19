# F-150 CarPlay

A dedicated **USB CarPlay receiver** for a **2018 Ford F-150 Lariat with a Linkswell head unit**,
forked from [CarLink](https://github.com/F-e-n-y-x/CarLink).

Plug an iPhone into the Linkswell USB data port. The app connects automatically, with no welcome
wizard, wireless setup or phone switching. Home and the USB connection screen share a large local
clock, date and live connection status. The clock follows the tablet's 12/24-hour setting, updates
once per minute and never delays automatic projection. There is no logo or animated loading loop.
The app has one launcher icon and one visible recent-apps card. Projection keeps its own hidden
task so opening Settings preserves the running phone connection; Open CarPlay returns to it.

Settings has independent tablet status-bar and bottom-bar/dock switches (either, both or neither),
a five-band music equalizer, larger projected CarPlay icon presets, connection-log export, source
credits and complete licenses. Bar changes apply on return to CarPlay using the engine's existing
inset handling and may briefly reconnect it; the bottom bar is Android's navigation/taskbar, subject
to the Linkswell firmware.
Extra large is the default projected size; unplug and reconnect after changing it. Video resolution
stays unchanged.

The optional equalizer affects decoded CarPlay music/media only. Navigation prompts, Siri, phone
calls and microphone audio bypass it. Five bands at 60/230/910/3600/14000 Hz offer ±12 dB in 1 dB
steps. It starts Off and flat, applies adjustments when a slider is released, and keeps its enabled
state when Reset to flat is pressed. Each audio renderer has independent filter history, with
short crossfades on changes and automatic headroom for boosts. Stable Off/flat leaves PCM bytes
unchanged. Unsupported formats bypass it; no global/system equalizer or new audio buffers are used.

The 1.2.1 stability patch prevents a late close callback from a replaced or explicitly retired
video stream from ending the live CarPlay session. Genuine EOF/errors from the current stream
still trigger normal session teardown. This fixes a reproducible engine race; a log from the
head unit is needed to establish whether it caused a particular rare interruption.

## Removed

Android Auto's engine/native libraries and module dependencies, generic Bluetooth/Wi-Fi/hotspot
orchestration, pairing/brand/root setup, floating switcher, old settings screens, automatic updater,
QR code UI, unused app resources and obsolete feature tests were removed. `carlink-core` now owns
one wired CarPlay session rather than selecting an engine or wireless path. A small JNI reset
helper preserves the existing wired recovery hook without depending on DiAuto/libusb.

The shared DiPlay engine still supplies the USB/iAP2/NCM, local IP/AirPlay, video and audio
implementation. Its upstream helper code remains vendored; unreferenced features are shrunk by
R8, and standalone diagnostic/updater/widget entry points are excluded from this product.

## Install and test

Install the local test APK on the **Linkswell**, open it once and allow microphone access.
Plug in an unlocked iPhone and accept the initial CarPlay, USB and wired VPN consent prompts.
If Android asks which app should handle USB, select **F-150 CarPlay**. Its package
`com.buckydajunior.carlink.f150` is separate from upstream CarLink; run one projection app at a time.

Test cold startup, quick replug, phone left connected across ignition, Siri/calls/music,
steering-wheel controls, reverse-camera return, icon sizes, all four bar combinations and EQ
on/off/music balance while navigation or calls play. **These have not yet been verified
on this user's Linkswell.** Settings > Share connection log exports `WIRED_TIMING` and app events
for measuring plug-to-first-frame latency. No on-car speed is claimed from unit tests.

## Build

Use JDK 25, Android SDK 36/37, NDK `28.2.13676358` and CMake `3.22.1`. The wrapper can resolve its
JDK toolchain. Set `JAVA_HOME` for the launcher and `ANDROID_HOME` for the SDK.

```sh
bash .claude/skills/carlink-dev/scripts/dev.sh test
bash .claude/skills/carlink-dev/scripts/dev.sh build
bash .claude/skills/carlink-dev/scripts/dev.sh build-release
./gradlew :app:recordScreenshots
```

The four remaining suites are app, core, CarPlay shared and CarPlay common. Unit tests and UI
renders verify local behavior; real connection/audio/ignition tests require the head unit.

Ordinary source builds omit the CarPlay runtime identity and cannot connect until the two local
files are provided through `DIPLAY_AUTH_ASSETS_DIR`:

```text
runtime-assets/offline-mfi/identity.pk8
runtime-assets/offline-mfi/certificate.p7b
```

Runtime identity files and Android signing keys are never committed. A local test APK may reuse
the experimental identity from the checksum-verified public upstream v1.0.16 APK. This is not an
Apple-certified implementation and iOS acceptance is uncertain. The upstream updater is absent;
install personal builds manually. No GitHub release is published by the development helper.

## Credits and licenses

CarLink: [F-e-n-y-x](https://github.com/F-e-n-y-x/CarLink). CarPlay engine:
[DiPlay by shihabal3amri](https://github.com/shihabal3amri/DiPlay), based on
[xcertplay by shilapi](https://github.com/shilapi/xcertplay). Earlier CarLink work incorporated
[DiAuto](https://github.com/shihabal3amri/DiAuto), Open Headunit, Headunit Revived and Michael Reid's
headunit; those original credits remain applicable to derived work even though the Android Auto
engine tree was removed. Inter is distributed under the SIL Open Font License.

This fork remains **AGPL-3.0** ([LICENSE](LICENSE)); DiPlay retains GPL-3.0 and its component notices.
The app's About page includes complete GPL/AGPL and font license texts plus the existing engine
component notices. Original engine documentation remains under `vendor/diplay/docs`; older
`docs`/`design` documents describe upstream history and may mention removed modules.

CarPlay is Apple's trademark. This project is independent of Apple, Ford and Linkswell.
