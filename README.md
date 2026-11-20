# CarPiano 2K

An English rebuild of [jlunnn/CarPiano](https://github.com/jlunnn/CarPiano), a sound app for Zeekr head units (Android). The original plays a piano, recordings and alert tones through the car's **outside speaker** or the cabin, and can toggle a few vehicle functions. Its interface is Cantonese; the original README is kept as [README.zh-HK.md](README.zh-HK.md).

> Unofficial. Not affiliated with Zeekr, Geely or ECARX. Playing sound outside a vehicle on public roads may be restricted where you live. Use it lawfully and considerately.

## Status

| Stage | State |
| --- | --- |
| Automatic APK builds on GitHub | Working |
| Original app verified on an Australian-delivered 7X | Being tested |
| English interface (soundboard, live mic, stream, car controls) | Next |

Right now the app code is still the original. The redesign lands here once the outside speaker is confirmed working on the test car.

## Download

Every push to `main` builds an APK and publishes it under [Releases](../../releases). Download the newest `CarPiano-2K-build-N.apk` and install it on the head unit. Builds share one signing key, so each installs over the last.

## Planned for the English rebuild

- **Sounds**: a soundboard with unlimited named sounds in categories, USB import, and typed phrases spoken by text-to-speech.
- **Live mic**: hold-to-talk or latch, cabin microphone straight to the outside speaker, with a volume cap and auto-off.
- **Stream**: play music from USB or app storage through the outside speaker while the cabin keeps its own audio.
- **Play**: the piano, plus more instruments.
- **Car**: Sentry mode and ambient lighting toggles, live signals, and simple "when this happens, do that" automations.
- **Setup**: a read-only scan of audio buses, microphones and vehicle signals for diagnosing a new car or firmware.

## How it works

- Sound is routed by naming an audio bus explicitly. On the original author's 7X the outside speaker is `BUS12_OUTER_NOTIFY`.
- Vehicle functions are read and written through the head unit's ECARX interface by reflection. Every write is read back to confirm it took effect.
- The app is fully offline: no INTERNET permission and no network code.

## Building

GitHub Actions does it for you (`.github/workflows/build.yml`). To build locally you need JDK 17 and the Android SDK, then run `./gradlew :app:assembleDebug`.

## Licence and credit

MIT, same as the original. All credit for the audio routing and vehicle-interface discoveries goes to the original CarPiano author; see [LICENSE](LICENSE).
