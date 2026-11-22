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
- Music EQ has ten frequency-keyed bands; migrate old five-band settings once without treating
  old indexes as new indexes. Preserve byte-exact Off/flat and stereo-linked peak limiting.
  Live music levels analyze post-EQ music only while the EQ page is visible/STARTED. Keep transforms
  off the audio callback, capture bounded/nonblocking, and idle/closed sessions free of stale levels.
- Keep authentication assets and Android signing keys outside Git. Local test APKs may contain
  the verified public upstream experimental runtime identity; document that provenance.
- Preserve GPL/AGPL, font licenses, component notices and original credits. Record actual engine
  source changes in `docs/VENDOR_CHANGES.md`; prefer host changes over shared protocol changes.
- Do not tag or publish a release unless the user requests it. Original maintainer devices,
  private paths and main-branch push instructions in upstream development notes are historical,
  not this user's environment or authorization.

- Replug admission returns acceptance: release fresh watcher/router claims on rejection.
  Preserve one token-checked deferred attach through the automatic terminal guard,
  refresh its UsbDevice before start and never bypass completed teardown or consent.
  Keep detach scans bounded, manual Disconnect respected, internal startup re-enumeration
  owned by the engine, and stale/returned node evidence bounded and cleared on success.
