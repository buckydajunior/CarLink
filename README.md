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

Version 1.3.1 fixes a reproduced host-side replug recovery gap. An automatic terminal
transport event can arrive while Android still lists the old USB device. The old
10-second anti-loop guard then rejected a returned phone while the watcher/router
permanently marked its new node handled. The app could remain on the waiting clock
until a fresh foreground retry or reboot. A guarded attach is now retained and
retried once with a freshly enumerated device after the guard, and rejected fresh
claims are released. A confirmed cable departure clears automatic suppression.

Relevant iPhone detach events also start a bounded 30-second enumeration search
for a missed reattach broadcast. The stale removed node does not end that search;
a node address can be reused after observed absence. A very quick replacement node
from an established session can restart after the existing 1.2-second grace and
completed teardown. Internal startup re-enumeration and an engine-owned retry keep
control; returned-phone evidence survives a retry that terminates later, for at most
30 seconds, and is cleared by successful connection or explicit stop/new attempt.
Manual Disconnect still suppresses scans, delayed work is token checked, and a
failed guarded recovery does not renew its own retry allowance. Success, explicit
Connect/Retry or a confirmed departure rearms that allowance.
There is no permanent USB poll, altered authentication, forced reboot or new startup
wait. Logs identify guard retention, expiry, departure, replacement and scan outcome.

The user reported fast first connection, followed by a failed replug after briefly
unplugging for Apple Pay; the app showed its clock with waiting-for-connection status
until the Linkswell was rebooted. Desktop regressions reproduce plausible lost-replug
paths; they do not establish the precise on-device event order. The 1.3.1 physical
unplug/replug behavior still needs testing on this Linkswell.

Version 1.3.2 hardens process startup after an intermittent ignition crash report
that also occurs without a phone; reopening the app succeeds. It removes the
optional decoder warm-up that ran 2.5 seconds after every process start. Codec
capabilities are still read when a USB session needs them, on its settings worker.
Memory callbacks now invalidate the decoder cache without waiting for a slow probe;
a probe completed after a trim cannot repopulate the retired cache generation.
The regression blocked the old trim path while a codec load was held and passes
with the nonblocking cache. No blanket startup delay or automatic crash restart
loop is added. Actual cold connection timing still needs measurement on the unit.

Version 1.3.3 corrects a USB startup regression in 1.3.2. Removing the router's
Direct Boot eligibility could exclude this app from Android 8.1's saved-default USB
dispatch while the user was still locked. The saved default itself is not erased.
The minimal router is eligible again, but all Application settings, private files,
authentication, USB session work and Home UI initialization wait for actual user
unlock. A main-thread FIFO registers for unlock and rechecks after registration;
Application setup precedes the latest retained USB attach. Deferred attach work
refreshes the currently enumerated iPhone and discards a phone unplugged during boot.
No private settings or identity files are moved to device-protected storage.
See [Android’s Direct Boot storage requirements](https://developer.android.com/privacy-and-security/direct-boot)
and [Android 8.1 USB default dispatch](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-8.1.0_r1/services/usb/java/com/android/server/usb/UsbProfileGroupSettingsManager.java).

A USB-started clock screen also carries a one-shot projection handoff: when its
Home route is resumed and the session is Connected, it returns to the existing
projection. Settings, EQ, About, the car tile and exiting to the Linkswell launcher
cancel that handoff. Ordinary manual Home launches do not request it. An already
connected system USB dispatch arms the same handoff only for a visible Home route,
then waits for Home to resume before reordering projection. It never starts a second
session or brings a stopped Home over another app. This addresses the reported extra Open CarPlay tap after accepting the
USB dialog; physical ignition/default retention still needs testing on this unit.
There is no fixed startup delay or background restart loop. Android still owns USB
consent/default selection; this app cannot silently grant access or save a default.

Settings > Share connection log now includes the existing saved, redacted Java
crash. Its byte/line-bounded report preserves the exception and host/engine frames,
and successful reopening/export does not erase it. The record may precede the
installed version; a missing Java record does not rule out a native crash or ANR.
The original crash handler still delegates to Android. The exact ignition failure
remains unconfirmed until the user returns its exception record; the new build can
export a previously saved crash without waiting for another failure.

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

## Version 1.3.1 validation — 2026-11-22

- Four new lost-replug regressions failed against the previous implementation.
  With the fix, all four suites passed: 4,006 passed, two existing skips, no failures
  (app36/core214/shared 1985/common1773 total4,008). USB watcher/session/router targets
  include 95 executions; the full enumeration integration cases run on API 27 and29.
- Coverage includes early/late transport ends, failed and Connected sessions,
  incomplete teardown, missed attach broadcasts, stale/reused USB nodes, NCM versus
  bootstrap identity, engine-owned Retry/Progress, retained late return evidence,
  successful-recovery invalidation, manual pause, stale callbacks and bounded retries/scans.
- Resource collisions:14 reviewed, zero unreviewed. Debug and optimized R8 test
  release builds passed; no UI geometry/resources changed and no new renders needed.
- Verified signed1.3.1/10301 package, alignment, same V2 test certificate, unchanged
  permissions/native entry inventory/licenses, and the new recovery paths in DEX.
  The exact same two checksum-verified public experimental upstream v1.0.16 runtime
  identity files as1.3.0 are packaged; source/signing material remains outside Git.
- Test APK: 4,329,835 bytes; SHA-256
  `494a128b6a924d519a4fe7b6db24266da5cd36fe695da529e4c1f2563cc992c8`.
- Actual Linkswell unplug/replug, calls, ignition recovery and connection timing
  remain hardware checks. The reported clock/waiting symptom matches the reproduced
  gap, but no device log establishes the exact event order for that occurrence.
  No release/tag is published.

## Version 1.3.2 validation — 2026-11-22

- All four suites passed: 4,033 passed, two existing skips, no failures (app 51,
  core 216, shared 1985, common 1783; total 4,035). The new nonblocking cache test first
  failed against the old synchronized trim path, then all four cache cases passed.
- API 27 checks cover post-unlock manifest contracts and inclusion/redaction of a
  previously saved Java exception without erasing it. API 27/33 crash-record cases
  cover host frames beyond framework frames, byte/line bounds and missing records.
- Debug and optimized R8 local test builds passed. Full debug lint passed with
  47 advisory warnings and no errors; resource collisions 14 reviewed/0 unreviewed.
  No UI geometry changes or new layout renders are required.
- Verified signed 1.3.2/10302 package, V2 update certificate, alignment, unchanged
  permissions/ABIs/launcher/native inventory/licenses, retained nonexported crash
  installer, DirectBootAware=false USB router, new report code and absent eager
  codec thread in DEX. The exact same two public experimental upstream v1.0.16
  identity files are packaged; raw runtime/signing source files remain outside Git.
- Test APK: 4,329,835 bytes; SHA-256
  `b177a8b8178d21588fdcbf7f87aae21b15e7adc5da0b540cc6b2affa9195644a`.
- Previous 1.3.1 versioned APK retained; 1.3.2 mapping archived with the local output.
  Actual ignition behavior, cold connection timing and the observed failure’s cause
  remain unverified on the Linkswell until its saved record is returned. No tag or
  release is published.

## Version 1.3.3 validation — 2026-11-23

- All four suites passed: 4,092 passed, two existing skips, no failures (app 85,
  core 241, shared 1985, common 1783; 4,094 total). Resource collisions remain
  14 reviewed/0 unreviewed.
- API 27/29 USB cases cover locked startup, initialization order, stale/unplugged
  nodes, latest attach replacement, destroyed router ownership and Connected dispatch
  without a second session. Ten pure queue cases cover registration races, FIFO,
  replacement/cancellation, duplicate signals and immediate unlocked startup.
- Forty focused API 27/29 app/manifest checks cover actual locked Application/Home,
  RESUMED/Home-only one-shot handoff, Settings/EQ/About/car-tile/launcher overrides,
  weak visible-Home callbacks and saved-state marker consumption. The car-settings
  race first failed before its guard; immediate USB callbacks and an old HOME frame
  can no longer override the requested Settings route before it renders.
- Debug, optimized R8 and full debug lint passed (47 advisory warnings/no errors).
  No layout geometry changed; existing native screen gallery is retained.
- Verified 1.3.3/10303 package, same V2 update certificate and alignment, minimal
  DirectBootAware=true router with private components unaware, new handoff/gate code
  and final route-render guard in the R8 mapping. Permissions, ABIs, one launcher,
  all 15 native binaries, six license assets and the same two verified public upstream
  v1.0.16 experimental identity files are unchanged. Raw identity/signing assets stay
  outside Git.
- Test APK: 4,346,219 bytes; SHA-256
  `2ba7e82c465c97e885a394dcb5c257a4dff24e1be8b7f24a1df18fce1bde65d7`. Previous 1.3.2 APK retained; matching 1.3.3 mapping archived.
  Actual Linkswell cold-start/default retention, USB reconnect, calls and connection
  timing remain hardware checks. Android owns consent/default selection; this
  build does not suppress required dialogs or establish the older crash's cause.
  No tag/release is published.
