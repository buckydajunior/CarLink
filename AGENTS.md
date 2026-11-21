# F-150 Linkswell fork

This repository belongs to `buckydajunior/CarLink`; `F-e-n-y-x/CarLink` is upstream.
The product targets a 2018 Ford F-150 Lariat with an unspecified Linkswell model.

- The app and core are **USB CarPlay only**. Android Auto, generic wireless orchestration,
  pairing/root/brand setup, the updater and floating switcher were removed from source/build.
- The requested larger icons are **inside projected CarPlay**. Preserve video resolution;
  settings control the dimensions reported to the iPhone and apply on reconnect.
- Preserve USB authentication, VPN consent, teardown ordering, re-enumeration grace, bounded
  retries, ignition re-scanning, adaptive deadlines and `WIRED_TIMING` logs. Measure latency on
  the actual Linkswell before claiming a speed. Keep probes and file work off the UI thread.
- Build modules are `:app`, `:carlink-core`, `:carplay-common`, `:carplay-shared` and the development
  `:baselineprofile` harness. Core's small JNI USB reset helper replaces the removed DiAuto/libusb dependency.
- `bash .claude/skills/carlink-dev/scripts/dev.sh test` runs the resource-collision check and
  all four remaining suites. Run it and `build` before pushing. `build-release` runs R8 and signs
  locally for testing. The helper respects caller `JAVA_HOME`, `ANDROID_HOME` and explicit `CARLINK_DEVICE`.
- Render UI changes with `:app:recordScreenshots` at head-unit and phone sizes and inspect the images.
  State plainly when USB, calls, ignition reconnect and projected sizing have not been tested on the truck.
- Keep authentication assets and Android signing keys outside Git. Local test APKs may contain
  the verified public upstream experimental runtime identity; document that provenance.
- Preserve GPL/AGPL, font licenses, component notices and original credits. Record actual engine
  source changes in `docs/VENDOR_CHANGES.md`; prefer host changes over shared protocol changes.
- Do not tag or publish a release unless the user requests it. Original maintainer devices,
  private paths and main-branch push instructions in upstream development notes are historical,
  not this user's environment or authorization.


## Separate wireless experiment

The user authorized `:wireless-lab` on branch `f150-wireless-lab`, based on wired v1.2.5.
It has its own package (`com.buckydajunior.carlink.f150.wirelesslab`) and launcher name.
The initial APK is a read-only compatibility check, not a wireless projection receiver.
Do not alter `:app`/`:carlink-core`, claim USB/boot events, run another projection session,
or enable radios, scan, create a hotspot or pair/connect automatically from this initial checker.
Feature/adapter presence is not proof of usable Bluetooth sockets or a working CarPlay session.
Preserve denied/unknown/error states and say what the check has not tested.
No phone names, addresses, pairing keys, radio toggles or runtime authentication assets are needed.
Run the lab's unit/manifest tests, debug/release builds and native layout renders before shipping
its APK; the existing wired source/tests remain the v1.2.5 baseline unless explicitly changed.

Wireless Lab 0.1.1 also offers an explicit Bluetooth settings navigation action because
this unit's Connected devices menu omits Bluetooth. Validate enabled/exported known
settings pages, permit the normal implicit shortcut even if metadata queries are
hidden, and handle missing/security-blocked pages gracefully. Metadata reads stay
on a worker; delayed navigation must not launch after the Activity loses visibility.
Returning from the user-requested settings page may refresh passive results once.
Report handler identities/request outcomes without claiming stock Bluetooth or
working sockets; no radio toggles, scans, pairing or projection run from the lab.

Wireless Lab 0.1.2 may identify relevant installed app versions and declared services,
including the default Home app, Android Bluetooth, ZLink and bounded name-matched
candidates. Only ZLink's TD_CHANNEL_ID metadata is requested. Vendor-name matches
are unverified fallbacks. Use public PackageManager APIs on the worker; narrow
queries and explicit visibility/error/truncation limits, no QUERY_ALL_PACKAGES.
/dev/rf_serial may be checked for existence/read/write permission metadata only:
NEVER open/read/write it or contact/bind OEM services in this reporter. Keep vendor
APKs and user photos outside Git. The user's working OEM Bluetooth connection is
not visible through the Android pairing page; do not request another pairing test.

Wireless Lab 0.1.3 may explicitly export only com.szchoiceway.btsuite's public
installed base/split APK files using PackageManager sourceDir/splitSourceDirs.
Keep size/count bounds, generated ZIP names, path-free version/hash metadata and
private-cache retention. Never read dataDir, contacts/call providers or pairings.
User selects Save ZIP via ACTION_CREATE_DOCUMENT or Share ZIP via explicit chooser;
no automatic recipient/upload, extra storage permission, bind, scan or serial open.
Suppress delayed export UI after pause; preserve pending filename across recreation.
Returned vendor code remains outside Git for static inspection only.
