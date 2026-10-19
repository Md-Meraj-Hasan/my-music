# Audio + Video merge review

## Merge strategy
- Kept the existing audio app shell and audio player JavaScript intact apart from imports and the native iframe bridge.
- Removed the MP3 Tools page from the visible navigation and replaced it with a Video Player page. Existing MP3 processing code remains dormant for compatibility but no visible MP3 Tools card/navigation entry remains.
- Embedded the video app as a separate local page (`video-player.html`) and kept its CSS/JS under `video-src/` to avoid global style and selector collisions.
- Added a parent-page bridge for Android native media permission, video scan, thumbnail, cache-copy playback, orientation lock/unlock, and Android back button.
- Merged the richer video scanner implementation while preserving audio artist/album metadata. Added READ_MEDIA_VIDEO permission and the Screen Orientation dependency.
- Vite is configured to emit both index.html and video-player.html into dist.

## Validation
- Source paths and required IDs checked by automated script.
- JavaScript syntax and JSON syntax checked.
- ZIP integrity checked.
- APK compilation and physical-device playback/rotation not run in this environment.
