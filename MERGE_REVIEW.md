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


## Latest UX updates
- Added a compact close/collapse chevron on the video frame; it pauses the video and hides the stage so the list can use the available screen. Selecting a video restores the stage.
- Audio and video Share buttons now share the selected media file, not promotional text. Android uses MediaStore content URIs with ACTION_SEND, a chooser, and temporary read permission. Browser preview supports Web Share Files only where the browser permits it.


## Browser-preview controls merge (2026-10-21)
- Matched the video overlay layout to the approved standalone controls preview while preserving the existing Capacitor/native media bridge and library UI.
- Added a horizontal thumbnail queue sourced from the existing video list/native thumbnail plugin.
- Lock now hides the overlay and blocks controls; tapping the video unlocks.
- The share control reuses the existing selected-video native share handler.
- This is a source ZIP update. A Gradle/GitHub Actions APK build and physical-device validation have not been run here.
