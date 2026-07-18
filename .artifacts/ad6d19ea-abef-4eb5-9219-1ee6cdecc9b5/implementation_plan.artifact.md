# Implementation Plan - Fix Infinite Loading in Offline Queue Transitions

The goal is to fix the issue where selecting another downloaded video from the player's related list while offline causes infinite loading and clears the list.

## Proposed Changes

### Player Services

#### [MODIFY] [OfflinePlayerService.kt](file:///C:/Users/Yjamp/AndroidStudioProjects/LibreTube/app/src/main/java/com/github/libretube/services/OfflinePlayerService.kt)
- Update `startPlayback` to include `relatedStreams` in the `Streams` metadata sent to the UI. This ensures the list of other downloaded videos remains visible in the player.
- Use `PlayingQueue.getStreams()` to populate the `relatedStreams` list.
- Ensure that the player is correctly prepared with the new media source.

```kotlin
        val streams = downloadWithItems.toStreams().apply {
            relatedStreams = PlayingQueue.getStreams()
        }

        withContext(Dispatchers.Main) {
            // ...
            setMediaItem(downloadWithItems, streams)
            // ...
        }
```
- Update `setMediaItem` signature to accept the pre-built `Streams` object.

### Utilities

#### [MODIFY] [DeArrowUtil.kt](file:///C:/Users/Yjamp/AndroidStudioProjects/LibreTube/app/src/main/java/com/github/libretube/util/DeArrowUtil.kt)
- Add a check for internet connectivity before making network calls. If offline, return null immediately. This prevents UI components from hanging or waiting for network timeouts.

### UI & Performance

#### [MODIFY] [PlayerFragment.kt](file:///C:/Users/Yjamp/AndroidStudioProjects/LibreTube/app/src/main/java/com/github/libretube/ui/fragments/PlayerFragment.kt)
- Update `onPlaybackStateChanged` to handle buffering timeouts differently for offline mode. If offline, a buffering timeout should likely be shorter or trigger a more helpful local error.
- Ensure `isOffline` flag is correctly propagated to all adapters.

## Verification Plan

### Automated Tests
- Build the project: `./gradlew :app:compileDebugKotlin`.

### Manual Verification
1.  **Offline Transitions**: Play a downloaded video, then select another one from the list below the player while disconnected. Verify it starts playing instantly.
2.  **Persistent List**: Verify that the list of other downloaded videos does not disappear after switching to a new one.
3.  **No Lag**: Verify that the UI remains responsive and doesn't "wait" for anything during the transition.
