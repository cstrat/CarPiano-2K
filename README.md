# CarTalk

CarTalk is a soundboard for Zeekr head units (Android). It puts a floating widget on the car's screen that plays your own sound clips through the car's **outside speaker** or the cabin, lets you talk through the outside speaker, and can place prank sounds at a particular seat.

It is an English rebuild of [jlunnn/CarPiano](https://github.com/jlunnn/CarPiano), whose author worked out how to reach the outside speaker and the vehicle interface. The original README is kept as [README.zh-HK.md](README.zh-HK.md).

> Unofficial. Not affiliated with Zeekr, Geely or ECARX. Playing sound outside a vehicle on public roads may be restricted where you live. Use it lawfully and considerately.

## Status

Tested on an Australian-delivered Zeekr 7X.

| Part | State |
| --- | --- |
| Floating widget: icon, minimal favourites panel, full board with a tab per group | Working |
| Hold to talk from the widget (cabin mic to the outside speaker) | Working, optional |
| Whoopee sounds placed at a seat, from the widget | Working, optional |
| Library: import files or zips, built-in browser, groups, colours, favourites, rename | Working |
| Outside loudness boost and choice of outside channel | In testing |
| Whoopee seat tuning across the cabin's speaker channels | In testing |
| Automations: play a sound or switch Sentry / ambient lighting when the car powers on or off | In testing |
| In-app update check | Working; the install step falls back to the browser on head units that block it |
| Setup: car scan, speaker test, last crash | Working |

## Download

Every push to `main` builds an APK and publishes it under [Releases](../../releases). Download the newest `CarTalk-build-N.apk` and install it on the head unit. Builds share one signing key, so each installs over the last and keeps your sounds and settings.

## Using it

- **Library** is where sounds are managed. **Add sounds** imports audio files or a zip from Downloads, Music or USB; folders inside a zip become groups. **Browse web** opens a small browser, and any audio file you download in it is named and filed straight into the group you chose. Long-press a sound to move, rename, favourite or remove it.
- **Widget** switches the floating widget on and sets its size and opacity for each of its three sizes, and whether it shows hold to talk and Whoopee.
- **Automations** holds "when this happens, do that" rules. They run only while the widget is on.
- **Setup** has the update check, a read-only car scan, speaker tests, outside loudness and seat tuning.

No sounds ship in the APK. You bring your own.

## Internet use and privacy

CarTalk uses the internet for two things, and only when you ask:

- **Update check.** On launch and from Setup, it asks this repository's GitHub Releases whether a newer build exists, and downloads the APK if you choose to install it.
- **Browse web.** The built-in browser loads whatever sites you open, like any browser. It has shortcut buttons to a few sound-effect sites; nothing is contacted until you tap one. Those sites apply their own terms, cookies and licences, and what you download from them is your responsibility.

Nothing about you, your sounds or the car is sent anywhere by the app itself. There are no analytics, accounts or ads. Sounds, settings and the crash log stay in the app's private storage on the head unit, and uninstalling the app deletes them.

Permissions it asks for, and why:

| Permission | Why |
| --- | --- |
| Internet | Update check and the built-in browser |
| Install packages | Handing a downloaded update to the system installer |
| Microphone | Hold to talk |
| Read storage | Importing your audio files from Downloads, Music or USB |
| Display over other apps | The floating widget |
| Foreground service, run at startup | Keeping the widget alive and bringing it back after the car starts |

## Not built yet

- Typed phrases spoken by text-to-speech.
- Recording and trimming sounds in the app.
- More automation triggers (gear, doors, locking), which need their signal addresses found first.

## How it works

- Sound is routed by naming an audio bus explicitly. On a 7X the outside speaker is `BUS12_OUTER_NOTIFY`, a single mono channel; the cabin's main bus has twelve.
- Seat placement sends a sound on one channel of a surround stream, or on channels you assign in Setup.
- Vehicle functions are read and written through the head unit's ECARX interface by reflection. Automations that switch Sentry mode or ambient lighting write to the car; nothing else does.
- The widget is an Android overlay run by a foreground service.

## Building

GitHub Actions does it for you (`.github/workflows/build.yml`). To build locally you need JDK 17 and the Android SDK, then run `./gradlew :app:assembleDebug`.

## Licence and credit

MIT, same as the original. All credit for the audio routing and vehicle-interface discoveries goes to the original CarPiano author; see [LICENSE](LICENSE).
