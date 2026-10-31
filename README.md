# TV Home

Personal tool: replaces the stock home screen on my TCL Android TV (Android 9)
with a fast, light, no-tracking launcher. Radio Dial is a separate app from
`aesrael/radio-dial`. Built from source
with plain SDK tools — no Gradle, no libraries, no accounts.

## What it is

- **Launcher** (`home.custom.tv`): light Google-TV-style home. Top tabs
  (Home / Apps / Movies / TV Shows / Live TV / Settings), photo hero that
  crossfades, BBC + Sky news cards, white app tiles. Tabs filter the app
  row; Live TV opens the Freeview tuner. Permissions: INTERNET (weather +
  headlines only — Open-Meteo, BBC/Sky RSS). No analytics.
- **Store**: Aurora Store (anonymous login) for YouTube / Netflix / Prime /
  Apple TV from the Play catalogue.

## Build

Needs Android SDK (platform android-28, build-tools 34) + JDK 17:

```sh
./build.sh           # launcher  -> out/Home-signed.apk
```

First build creates `debug.keystore` (gitignored). Keep it: updates must
carry the same signature or the TV rejects them.

## Install

```sh
adb connect <TV-IP>:5555
./install.sh <TV-IP>
```

On the TV, accept the USB debugging prompt.

## Revert (everything is reversible)

```sh
./revert.sh <TV-IP>    # stock home screen back
./restore.sh <TV-IP>   # + reinstalls every debloated package for the user
```

Debloat is per-user (`pm uninstall --user 0`): APKs stay in the system
partition, so a factory reset also restores everything. The stock launcher
is kept installed-but-disabled as a fallback.

## Removed bloat (all restored by restore.sh)

TCL app store, esticker, bi (analytics), usercenter, useragreement, Google
recommendation rows + Play Music, tvweishi, partnercustomizer.

Kept: com.tcl.tv (tuner input), Bluetooth remote service, Play services,
WebView, media players, settings, system UI.

## Safety notes

- Turn USB debugging OFF on the TV when done (port 5555 closed).
- TCL Android 9 re-enables its user center at boot — expected, not failure.
