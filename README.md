# CarTalk

CarTalk (repo name CarPiano-2K) is an English rebuild of [jlunnn/CarPiano](https://github.com/jlunnn/CarPiano), a sound app for Zeekr head units (Android). The original plays a piano, recordings and alert tones through the car's **outside speaker** or the cabin, and can toggle a few vehicle functions. Its interface is Cantonese; the original README is kept as [README.zh-HK.md](README.zh-HK.md).

> Unofficial. Not affiliated with Zeekr, Geely or ECARX. Playing sound outside a vehicle on public roads may be restricted where you live. Use it lawfully and considerately.

## Status

| Stage | State |
| --- | --- |
| Automatic APK builds on GitHub | Working |
| English home screen: Sounds, Live mic, Setup | In testing on a 7X |
| Import your own MP3 / WAV files | In testing |
| In-app update check and one-tap install | In testing (fixed stale download in build 13) |
| Floating soundboard over other apps | Working on a 7X |
| Built-in browser that saves audio downloads straight into a group | In testing |
| Whoopee: in-cabin prank sounds, left or right, with a delay | In testing |
| Car controls, automations, piano | Not built yet |

The original Cantonese screens have been removed from this fork. The audio routing and vehicle-interface code they relied on is kept.

## Download

Every push to `main` builds an APK and publishes it under [Releases](../../releases). Download the newest `CarPiano-2K-build-N.apk` and install it on the head unit. Builds share one signing key, so each installs over the last.

## Not built yet

- Typed phrases spoken by text-to-speech.
- Choosing which cabin microphone feeds the live mic, and which seat a Whoopee sound comes from.
- Sentry mode and ambient lighting toggles, and simple "when this happens, do that" automations.

## Sounds

No sounds ship in the APK. Add your own with **Add sounds**: single files or a zip from Downloads or USB. Folders inside a zip become groups, and a folder named Whoopee feeds the Whoopee page.

## How it works

- Sound is routed by naming an audio bus explicitly. On the original author's 7X the outside speaker is `BUS12_OUTER_NOTIFY`.
- Vehicle functions are read and written through the head unit's ECARX interface by reflection. Every write is read back to confirm it took effect.
- The app is fully offline: no INTERNET permission and no network code.

## Building

GitHub Actions does it for you (`.github/workflows/build.yml`). To build locally you need JDK 17 and the Android SDK, then run `./gradlew :app:assembleDebug`.

## Licence and credit

MIT, same as the original. All credit for the audio routing and vehicle-interface discoveries goes to the original CarPiano author; see [LICENSE](LICENSE).
