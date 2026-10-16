# Third-party notices

The plugin compiles one third-party library into the app on Android and iOS.

| Component | Version | License | Where |
|---|---|---|---|
| AprilTag 3 (detector core, `tag36h11`, `common/`) | `v3.4.5`, unmodified subset | BSD-2-Clause, Copyright (C) 2013-2016 The Regents of The University of Michigan | `native/marker_detector/third_party/apriltag/` (source, `LICENSE.md`, `README.md` with the archive URL and SHA-256) |

The BSD-2-Clause license asks binary distributions to reproduce its notice.
`LICENSE` therefore holds two sections in the Flutter multi-license format
(sections separated by a line of 80 hyphens, each starting with the package
names and a blank line): `ar_flutter_plugin_2` (MIT) and `apriltag`
(BSD-2-Clause, the text of `native/marker_detector/third_party/apriltag/LICENSE.md`).
Flutter copies both into the app's `NOTICES`, so the app's license page
(Settings, Licenses) lists AprilTag. `test/third_party_notices_test.dart`
keeps the two copies identical.
