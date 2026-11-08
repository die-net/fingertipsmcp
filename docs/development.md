# Developing FingertipsMCP

This guide is for working on the app from a source checkout. Installing a release build is in [setup.md](setup.md). The HTTP/MCP surface is in [api.md](api.md). Design decisions and system shape are in [architecture.md](architecture.md).

## Prerequisites

- JDK 21 (Temurin works)
- Android SDK with compileSdk 37 (`ANDROID_HOME` must point at the SDK)
- A device or emulator with USB debugging
- `adb`, `jq`, and `curl` on the host
- [`uv`](https://docs.astral.sh/uv/) for `fingertips-dev papi WS …` WebSocket calls
- Node 20 or later on the `PATH` for `testDebugUnitTest`, which runs the page scripts' tests in `app/src/test/js` with `node --test`

Typical macOS Homebrew layout:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

## Client: fingertips-dev

`scripts/fingertips-dev` sources `scripts/fingertips` with debug defaults:

| | `fingertips` (release) | `fingertips-dev` |
|--|----------------------|----------------|
| Package | `com.fingertipsmcp` | `com.fingertipsmcp.dev` |
| Pairing file | `~/.config/fingertips/pairing.json` | `.dev/pairing.json` |
| Session file | `~/.config/fingertips/session` | `.dev/session` |
| `setup` | no | yes |

Override with `FINGERTIPS_PKG`, `FINGERTIPS_STATE` (the directory for the pairing and session files), or `PAPI_PAIRING` if needed.

## First-time device setup

With a USB device (or emulator) attached:

```sh
scripts/fingertips-dev setup            # build, install, grant, pair, server, helper
scripts/fingertips-dev setup --no-build # skip the Gradle build
```

That installs the debug APK as `com.fingertipsmcp.dev` (so it can sit next to a release build), grants `WRITE_SECURE_SETTINGS` so the app can re-enable Wireless Debugging after reboot, writes `.dev/pairing.json`, exposes the server on localhost, and starts the helper.

To rebuild by hand:

```sh
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Day-to-day commands

```sh
scripts/fingertips-dev pair --name laptop   # mint a token into .dev/pairing.json
scripts/fingertips-dev server               # adb-forward the API to localhost
scripts/fingertips-dev helper               # start listener + shell helper, wait until it runs
FINGERTIPS_PKG=com.fingertipsmcp PAPI_PAIRING=~/.config/fingertips/pairing.json \
  scripts/fingertips-dev helper             # same, for the release install
```

`helper` checks the helper with the pairing's token, so it needs a pairing for the package it starts. It fails if a step fails or the app does not report the helper `running` within 30 s. `setup` waits up to 180 s for a booted device, and stops before `Ready` if the helper does not come up.

Open the app for screen reading and taps, wireless debugging, and the computers that have access.

## Calling the API with fingertips-dev papi

`fingertips-dev papi` (or `fingertips papi` for a release install) reads the pairing file (or `$PAPI_PAIRING`) and issues HTTP or WebSocket calls. HTTP prints the body and then the status code. `WS` prints text frames for `PAPI_WS_SECONDS` (default 10); that path needs [`uv`](https://docs.astral.sh/uv/).

Calls that act on the phone or its tabs need a session, as [api.md](api.md#sessions) describes. `papi` starts one the first time the server answers 428 `session_required`, keeps its id in the session file next to the pairing file, and sends it on every call. So a sequence of `papi` calls holds the screen and its tabs as one client, and another client cannot take the screen between two of them unless they sit idle for 30 s while it waits.

- If the session ended, after 20 minutes without a request or by an admin, the server answers 404 `session_not_found`, and `papi` starts a new one and says so on stderr. The new one has neither the old one's screen nor its tabs.
- Set `PAPI_SESSION` to use another session instead of the kept one, for example to run two scripts as separate clients, or to act as an MCP client's session with the id its `initialize` returned.
- `fingertips-dev session id` prints the kept session, and `fingertips-dev session end` ends it, closing the tabs it opened, and removes the session file.

```sh
scripts/fingertips-dev papi GET /v1/device
scripts/fingertips-dev papi POST /v1/input/tap '{"x":540,"y":1200,"humanize":false}'
scripts/fingertips-dev papi WS /v1/events
```

Read the screen and tap a control:

```sh
scripts/fingertips-dev papi GET '/v1/ui/snapshot?format=compact'
scripts/fingertips-dev papi POST /v1/ui/find '{"selector":{"text":"Settings"}}'
scripts/fingertips-dev papi POST /v1/input/tap '{"selector":{"text":"Settings"}}'
```

Wait until a window is in front, then launch or stop an app:

```sh
scripts/fingertips-dev papi POST /v1/wait \
  '{"all":[{"type":"window","package":"com.android.settings"}],"timeoutMs":10000}'
scripts/fingertips-dev papi POST /v1/apps/com.android.settings/launch
scripts/fingertips-dev papi POST /v1/apps/com.android.settings/stop
```

Drive Chrome (helper up, Chrome publishing `@chrome_devtools_remote`, Wireless Debugging on Android 11+, or the Android 10 USB tunnel). Target ids look like `chrome_devtools_remote~<page id>`:

```sh
scripts/fingertips-dev papi GET /v1/browser/targets
scripts/fingertips-dev papi POST /v1/browser/tabs '{"url":"https://example.com/"}'
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/navigate '{"url":"https://example.org/"}'
scripts/fingertips-dev papi GET /v1/browser/targets/TARGET/snapshot
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/tap '{"selector":"a"}'
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/tap '{"selector":"a","input":"cdp"}'
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/swipe '{"direction":"up","input":"cdp"}'
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/text '{"text":"hello","input":"cdp"}'
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/evaluate '{"expression":"document.title"}'
scripts/fingertips-dev papi POST /v1/browser/targets/TARGET/close
```

FingertipsMCP's browser input depends on how Chrome treats DevTools input on the phone: touch is held for any tab Chrome is not showing, mouse clicks reach background tabs, clicks and keys are dropped on a page Chrome has not drawn until a screenshot request, and a new tab takes the screen while Chrome is in front. After a Chrome update, check that this still holds. The script serves its own test page through `adb reverse`, opens and closes its own tabs, wakes the screen and brings Chrome forward unless given `--hidden-only`, and puts the screen and foreground app back. It needs Node 22 or later.

```sh
scripts/chrome-input-check
```

Full route reference: [api.md](api.md).

## Checks and tests

Same suite CI runs on pull requests:

```sh
./gradlew spotlessCheck detektMain detektTest lint testDebugUnitTest :api-model:test assembleDebug buildHealth --continue
```

Useful pieces on their own:

```sh
./gradlew spotlessApply    # format Kotlin / Gradle Kotlin DSL
./gradlew spotlessCheck
./gradlew detektMain detektTest
./gradlew lint
./gradlew testDebugUnitTest :api-model:test
```

Release tags also build a signed release APK (`assembleRelease`) after those checks.

## Which Chrome to test

FingertipsMCP is tested back to the oldest Chrome the bundled pdf.js supports, since PDF reads need it. pdf.js 6.4.299 needs Chrome 125, as the `Chrome >=` line in its `gulpfile.mjs` says. A pdf.js update can raise that, so check the line and update this section with it.

- Chrome 125 and newer are supported. The phone's Chrome and the newest emulator get the full checks, and repeated runs when a failure is intermittent.
- Older Chrome is not supported. It gets only a quick happy-path look, without PDFs, to see if anything is obviously broken with an easy fix. A few tries are enough, and a failure that shows up only there is not a release blocker.

Test a fix on the current Chrome first, with the sequence that failed. Run other Chrome versions only when the code differs by version.

Android is a separate question, since Chrome updates apart from it. FingertipsMCP supports Android 10 and newer, so anything that doesn't depend on Chrome, such as the helper, input, the UI tree, the screen, and the clipboard, is tested back to Android 10. A real Android 10 phone is assumed to run a newer Chrome than the Android 10 emulator's Chrome 91, so a Chrome failure there says little about such a phone.

## Emulator notes

On a debuggable emulator image, Chrome only publishes `@chrome_devtools_remote` after it is started with remote debugging, for example a `/data/local/tmp/chrome-command-line` file containing `chrome --disable-fre --no-first-run --remote-debugging-port=9222`, followed by a force-stop and launch. Adding `--enable-features=AndroidOpenPdfInline` to that line makes Chrome show a PDF in its own viewer, which `browser_read` reads, instead of saving it. Some emulator images also refuse the shell user; `adb root` makes the helper uid 0, which Chrome accepts. `scripts/fingertips-dev helper` does not change that.
