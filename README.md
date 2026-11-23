# CarTalk

CarTalk is an English rebuild of [jlunnn/CarPiano](https://github.com/jlunnn/CarPiano), a sound app for Zeekr head units (Android). The original plays a piano, recordings and alert tones through the car's **outside speaker** or the cabin, and can toggle a few vehicle functions. Its interface is Cantonese; the original README is kept as [README.zh-HK.md](README.zh-HK.md).

> Unofficial. Not affiliated with Zeekr, Geely or ECARX. Playing sound outside a vehicle on public roads may be restricted where you live. Use it lawfully and considerately.

## Status

Tested on an Australian-delivered Zeekr 7X.

| Part | State |
| --- | --- |
| Floating widget: icon, minimal favourites panel, full board with a tab per group | Working |
| Hold to talk from the widget (cabin mic to the outside speaker) | Working, optional |
| Whoopee sounds placed at a seat, from the widget | Working, optional |
| Library: import files or zips, built-in browser, groups, colours, favourites, rename | In testing |
| In-app update check and install | In testing |
| Automations: play a sound or switch Sentry / ambient lighting when the car powers on or off | In testing |
| Setup: car scan, speaker test, outside loudness, seat tuning, last crash | Working |

The app itself is the manager for the widget. The original Cantonese screens have been removed from this fork; the audio routing and vehicle-interface code they relied on is kept.

## Download

Every push to `main` builds an APK and publishes it under [Releases](../../releases). Download the newest `CarTalk-build-N.apk` and install it on the head unit. Builds share one signing key, so each installs over the last.

## Not built yet

- Typed phrases spoken by text-to-speech.
- Recording and trimming sounds in the app.
- More automation triggers (gear, doors, locking), which need their signal addresses found first.

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
