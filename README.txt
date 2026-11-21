ScreenCast Live - Android Studio project
=========================================
Open this folder in Android Studio (Koala or newer, JDK 17). Let Gradle sync.

BEFORE BUILDING (required)
1. Firebase console -> create project -> add Android app with package name:  com.screencastlive
2. Download google-services.json and put it in:  app/google-services.json
3. Build -> Authentication -> Sign-in method -> enable "Anonymous"
4. Build -> Realtime Database -> Create database -> Rules tab -> paste database.rules.json -> Publish
5. Run on two phones (or install the APK). Phone A: Broadcast tab -> Start. Phone B (mobile data): Public tab -> tap the stream.

TURN: Metered credentials are in Core.kt (Config). Regenerate them in the Metered dashboard before public release.
If anything fails, open Broadcast tab -> "Copy log" and send the log.

How the "Failed to start MediaProjection" bug is avoided
- Permission result Intent -> ScreenBroadcastService -> startForeground(type=mediaProjection) FIRST -> only then ScreenCapturerAndroid.startCapture.
- Manifest service has foregroundServiceType="mediaProjection".
- Service is START_NOT_STICKY (Android 14 projection Intent is single use). Reconnects use ICE restart, never a new permission.
