# PadMapper (Kotlin) - structure

app/build.gradle.kts essentials:
    android { compileSdk = 34; defaultConfig { minSdk = 30; targetSdk = 34 }; buildFeatures { aidl = true } }
    dependencies {
      implementation("dev.rikka.shizuku:api:13.1.5")
      implementation("dev.rikka.shizuku:provider:13.1.5")
      implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
      implementation("androidx.core:core-ktx:1.13.1")
    }

Flow (Shizuku mode): MappingService (overlay + hot-plug + profile) --AIDL--> InputUserService (shell uid):
EvdevReader -> MappingRouter (tick ~125Hz) -> ShellTouchInjector -> IInputManager.injectInputEvent.
Fallback: PadAccessibilityService.onKeyEvent/onMotionEvent -> MappingRouter -> AccessibilityTouchInjector.dispatchGesture.

Setup: overlay permission -> start Shizuku -> grant Shizuku permission -> start service -> tap bubble (EDIT),
drag nodes onto the game's buttons (long-press = unmap), tap bubble again (PLAY).
Place nodes with the GAME in landscape; coordinates are stored in screen pixels.

Known limits
- Nothing here grabs (EVIOCGRAB) the pad, so the game still sees native gamepad input too. Turn controller support
  off in-game, or add a small JNI ioctl(EVIOCGRAB) in EvdevReader.
- Cheap pads differ: check the MainActivity tester; adjust EvdevReader.RIGHT_X/RIGHT_Y and Maps.EVDEV_KEYS.
- Pan re-anchor lifts the finger for one frame; widen AimCfg.halfW/halfH to make it rare.
- Not compiled/tested here: verify hidden-API reflection (IInputManager.injectInputEvent signature) per Android version.

## Added: JNI grab + tuning screen
app/build.gradle.kts additions:
    android {
      defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
      externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
- EvdevReader opens the node through Native.openRead, calls EVIOCGRAB, then reads. If the .so fails to load,
  it falls back to an ungrabbed read (game sees the pad too) and logs a warning.
- Grab also hides the pad from Android's own input, so MainActivity's tester shows nothing while PLAY is active.
  Tap the bubble to EDIT (engine stops, grab releases) when you want to test codes.
- SettingsActivity sliders write the profile and broadcast; MappingService pushes it to the engine live.

## APK build (no Android Studio needed)
1. Is poore folder ko ek naye GitHub repo me push karo (hidden `.github` folder ke saath).
2. Repo > Actions > "Build APK" > Run workflow (push pe auto bhi chalta hai).
3. Run complete hone par Artifacts se `PadMapper-debug-apk` download karo, zip kholo, `app-debug.apk` install karo.
Android Studio se: folder open karo > Build > Build APK(s).
