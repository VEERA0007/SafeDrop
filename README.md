# SafeDrop – AirDrop-style sharing with parental controls (Android)

Offline, nearby file sharing between Android phones (Google Nearby Connections: Bluetooth + Wi-Fi Direct),
with a PIN-protected parent area.

## Build the APK
1. Install Android Studio (Koala or newer) and open this folder.
2. Let Gradle sync (it downloads the Android SDK 34 pieces), then **Build > Build APK(s)**,
   or run `./gradlew assembleDebug` after Android Studio creates the wrapper.
3. Install on two Android 10+ phones. Google Play services is required.

## How it works
- **Share tab (child):** tap *Find nearby*, connect to another phone, confirm the matching code, send a file.
- **Parent tab (PIN):** set a PIN the first time. Then:
  - Approve / reject every incoming file (held in private quarantine) and every outgoing file.
  - Rules: block file types (apk, exe, ... editable), max size, trusted-devices-only, quiet hours.
  - Activity log of connections, blocked attempts, approvals and transfers.
- Approved incoming files are saved to `Downloads/SafeDrop`.

## Limits (be honest with the child's device setup)
- Rules live on the child's phone. A child could uninstall the app. Pair it with Google Family Link
  (block uninstall / app installs) to make it stick.
- It talks only to other SafeDrop installs, and Android only. It cannot control Apple's AirDrop or
  Android's built-in Quick Share.
- The PIN is hashed, not salted per device; fine for family use, not for high-security needs.
- Not compiled in the authoring environment (no Android SDK there): expect to fix a typo or two on first sync.
