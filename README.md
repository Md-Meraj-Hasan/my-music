# Meraj Music — Offline Local Player

Google Music-inspired multi-page local audio player. Audio-only; no video player and no network permission.

## Browser preview
Run `npm install` and `npm run start` for the browser preview. Browser security requires selecting local files/folders with the Scan Music button.

## Android behavior
On startup the app requests Android audio-library permission once (unless already granted), then queries Android MediaStore for audio tracks and folder paths. The Scan Music button rescans the library. The browser and Android use the same multi-page interface.

## Build
GitHub Actions builds the debug APK. The native plugins are integrated by `scripts/integrate-native-media.cjs`.

## Notes
Playback of MediaStore content URIs and permission behavior should be tested on a physical Android device across the supported Android versions.
