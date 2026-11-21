# HaishinKit Android studio

The Android sample follows the UI UX Pro Max dark media interface guidance,
adapted to native Material 3 rather than its suggested marketing-page layout.
The requested direction is navy with teal actions and the existing Swift app icon.

- One primary action per screen. Stop and connection cancellation remain available.
- Navy surfaces keep preview chrome distinct; teal starts an operation, red stops it.
- Shared Material color, shape and typography tokens; no downloaded font dependency.
- Four labelled destinations. Bottom navigation on phones, rail at 600dp and above.
  Route selection follows the navigation back stack rather than a separate index.
- The preview uses compact edge controls and an unobstructed center, following the iOS sample.
  Camera selection, zoom, effects and quality capture open in a dismissible bottom sheet.
- Connection settings are grouped with persistent field labels and inline URL feedback.
  Credentials remain masked and in memory, matching the existing lifecycle.
- Screen sharing says capture is active, rather than claiming the network is connected.
- English and Japanese resources; text can wrap and controls use native minimum hit areas.
- No new ScreenObject accessibility actions. Existing touch editing remains unchanged.
- The launcher uses the unchanged Swift bitmap with adaptive-icon safe-zone insets.

Validation should include phone portrait/landscape, larger fonts, navigation/back,
permissions, connection failures/cancellation, recording and picture-in-picture.
Actual network publication requires a reachable streaming endpoint.

## Verification

- App Kotlin compilation and ktlint checks passed.
- 32 app unit tests passed, including background filter-swipe direction and exclusion rules.
- Six Compose tests passed on a Pixel 5a: settings/authentication retention,
  navigation/back selection, invalid-URL recovery, primary actions in short windows,
  and 2x text.
- Visually inspected portrait and landscape, 375dp width with 2x text,
  and an 800dp window using the physical device's display-size override.
  Font, rotation and display-size settings were restored afterward.
- Device APKs reuse the unchanged native libraries from the existing local debug
  APK. Kotlin/UI and resources were rebuilt against current main. A clean native
  build and live stream publication were not part of this UI verification.
- Captured screenshots show publishing with a live camera preview, Settings and Share
  in English, as requested. No credentials are included.

Android lint and ktlint passed. Measured text contrasts range from 7.24:1
(stop action) to 14.71:1 (body text), above the 4.5:1 normal-text threshold.

Background horizontal swipes cycle filters; object transforms, pinches and vertical
drags keep their existing meaning. Snapshot/recording controls use translucent
backgrounds, and a short filter-name hint provides feedback after each swipe.
