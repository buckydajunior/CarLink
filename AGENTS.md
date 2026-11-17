# F-150 Linkswell fork

This fork belongs to `buckydajunior/CarLink`; `F-e-n-y-x/CarLink` is upstream.
The product is for a 2018 Ford F-150 Lariat with an unspecified Linkswell model.

- Product behavior is **USB CarPlay only**, with automatic plug-in connection and no welcome
  wizard, branded launch icon, wireless setup, Bluetooth discovery or Android Auto startup.
- The requested larger icons are **inside projected CarPlay**. Preserve video resolution;
  the dedicated settings page controls physical dimensions reported to the iPhone.
- Prioritize plug-to-first-frame latency and reliable reconnects. Preserve `WIRED_TIMING`,
  measure on the actual Linkswell before claiming a speed, and keep slow probes off the UI thread.
- `:carlink-core` retains generic defaults and the shared engine libraries for compatibility;
  the APK explicitly selects `ConnectionPolicy.USB_CARPLAY_ONLY`. Test that product restrictions
  cannot be bypassed by stale preferences, broadcasts or removed UI paths.
- Read the existing `carlink-dev` skill for the engineering workflow. Its original maintainer's
  devices, private paths, account permissions and main-branch push instructions are historical;
  they are not this user's configuration or authorization. The helper now respects caller
  SDK/JDK variables and explicit device selection.
- Run the resource-collision check, all five unit-test suites and a build before pushing.
  Render UI changes at head-unit and phone sizes and inspect the images. State clearly when
  USB, calls, ignition reconnect or projected sizing have not been tested on the truck.
- Keep authentication assets and signing keys outside Git. Local test APKs may contain the
  public upstream experimental runtime identity; document that provenance. Preserve licenses
  and credits. Do not tag or publish a release unless the user requests one.
