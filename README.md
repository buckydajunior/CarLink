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
a ten-band music equalizer, call echo control, larger projected CarPlay icon presets, connection-log export, source
credits and complete licenses. Bar changes apply on return to CarPlay using the engine's existing
inset handling and may briefly reconnect it; the bottom bar is Android's navigation/taskbar, subject
to the Linkswell firmware.
Extra large is the default projected size; unplug and reconnect after changing it. Video resolution
stays unchanged.

The optional equalizer affects decoded CarPlay music/media only. Navigation prompts, Siri, phone
calls and microphone audio bypass it. Ten bands at 32/63/125/250/500/1000/2000/4000/8000/16000 Hz
have names from Deep bass through Mids, Highs and Treble, with ±12 dB in 1 dB steps. A touchable
curve and ten frequency/gain readouts show every control level at once; select or drag a point,
or use the large selected-band slider and Previous/Next buttons. The curve previews band settings,
not the combined filter response. Bars behind it show real post-EQ CarPlay music levels in the
same ten frequency ranges, including when EQ is Off. The two displays have separate scales:
EQ gain is ±12 dB; music bars use relative level (-60..0 dBFS internally). Edits apply on release.
It starts Off and flat and keeps its enabled
state when Reset to flat is pressed. Each audio renderer has independent filter history, with
short crossfades on changes and stereo-linked protection for actual over-range sample peaks. Stable Off/flat leaves PCM bytes
unchanged. Unsupported formats bypass it; no global/system equalizer or audio queue is added.

Version 1.3.0 expands the EQ and migrates the previous five gains to their nearest new centers:
60→63, 230→250, 910→1000, 3600→4000 and 14000→16000 Hz. The five inserted bands stay flat and
On/Off is preserved. The shifted centers approximate the former tuning; they are not an identical
frequency response. Frequency-named settings and a schema marker prevent repeated index migration.
The live analyzer subscribes only while the EQ screen is visible. PCM is copied into a bounded,
nonblocking capture slot; a separate worker performs spectral analysis and publishes roughly ten
updates per second. Idle/stopped music clears the display. No microphone, recording file or added
playback queue is used, and no analyzer runs during startup or on other screens. Only nonzero EQ
bands run. The expanded DSP, visual reaction and CPU use need verification on the Linkswell.

Version 1.2.5 removed the static preamp cut that made the entire song quieter whenever a band was
boosted. A single +6 dB band previously reduced unrelated audio by roughly 6.5 dB, even when the
signal had ample room. Requested boosts now pass at their actual gain until output peaks require
limiting. Both stereo channels share one instantaneous reduction per filter bank, with a 10 ms
hold and 100 ms smooth release. Banks are limited before the 20 ms settings crossfade, so turning
EQ Off or resetting to flat fades smoothly back to exact dry playback. There is no lookahead,
automatic makeup gain or added audio queue. Large boosts on loud music can still trigger gain
reduction; this is sample-peak protection, not transparent or true-peak mastering. Audible behavior
and CPU use still need testing on the Linkswell.

Settings > Call audio exposes the engine's experimental software echo canceller. It is **Off by
default** in 1.2.4. A one-time migration turns off the default enabled by 1.2.3 without changing
display/music preferences; later explicit opt-ins remain under the driver's control. Unplug and
reconnect after changing the setting. Matching mono call audio can use the speaker reference;
unsupported/failed software processing keeps/restores Android's available call effects. Software
and platform AEC are not deliberately stacked. Music EQ still bypasses calls.

The external-microphone test on this Linkswell found that software AEC made voices muffled/robotic
or distorted without fixing the caller hearing their own voice. Turning it Off and reconnecting
restored the previous voice quality, while the original echo remained. This isolates the new
quality regression to that processing mode; it does not establish the cause of the original echo.
A call log with AEC Off is needed before selecting another processing or routing change.

The 1.2.3 call-buffer policy remains: one microphone packet frame per read, an application start
threshold based on rate/device minimum, and an Android 12+ native start-threshold request or
older-device effective-buffer fallback. The user's A/B comparison did not implicate these buffer
changes. Actual accepted sizes/effects/rates are exported; end-to-end delay has not been measured,
and a separate delay was not confirmed beyond the repeated echo.

The 1.2.1 stability patch prevents a late close callback from a replaced or explicitly retired
video stream from ending the live CarPlay session. Genuine EOF/errors from the current stream
still trigger normal session teardown. This fixes a reproducible engine race; a log from the
head unit is needed to establish whether it caused a particular rare interruption.

The 1.2.2 startup patch scans immediately, then briefly rescans while the USB controller comes up
(at 0.5/1.5/3/5/10/20/30 seconds, stopping as soon as a phone is found). Boot/ACC wake or opening
Home can retry an inactive, already-listed phone without a physical replug. Working sessions and
manual Disconnect remain protected. A lifecycle gate uses an actual resumed app Activity rather
than a potentially late process-importance snapshot; canceled or superseded launches are discarded.

On Android 10+, a fully background app still needs an allowed foreground launch. If F-150 CarPlay
does not open with the tablet, use the Linkswell launcher's startup-app option if it provides one.
The app does not bypass [Android's background-start restrictions](https://developer.android.com/guide/components/activities/secure-bal)
or automatically approve USB/VPN/microphone consent.

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
on/off/music balance while navigation or calls play. The user reports that v1.2.2 startup works
well. The caller hears their own voice through an external microphone plugged into the Linkswell.
The 1.2.3 software AEC trial worsened voice quality; Off restored it without resolving echo.
The cause of the earlier rare automatic reconnects is still unconfirmed. **The original call echo
and full hardware test matrix need diagnosis/validation on the Linkswell.** Settings > Share connection log exports
`WIRED_TIMING`, boot/wake/USB readiness and app events for diagnosis. No on-car speed is claimed
from unit tests.

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
