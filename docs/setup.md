# Setting up FingertipsMCP

This guide installs a release build of FingertipsMCP for Android, FingertipsMCP for Chrome, or both, and connects an agent to them. To build from source, see [development.md](development.md).

You'll want an [MCP](https://modelcontextprotocol.io) client, such as Claude Code or Cursor, for an agent to drive them.

## A phone

### Requirements

- A phone or emulator running Android 10 or newer, with USB debugging on. Wireless Debugging, to drop the cable later, needs Android 11.
- On the computer, `adb`, `jq`, and `curl` on your `PATH`. `fingertips` shells out to these. Most machines already have `curl`. `adb` and `jq` usually need a separate install, for example `brew install android-platform-tools jq` on macOS, or [platform-tools](https://developer.android.com/tools/releases/platform-tools) from Google plus your OS package for `jq`.

### Install

1. Download a release APK (`fingertipsmcp-android-v*.apk`) from [GitHub Releases](https://github.com/die-net/fingertipsmcp/releases).
2. Copy [`scripts/fingertips`](../scripts/fingertips) somewhere on your `PATH`, for example `~/bin/fingertips`. It does not need the rest of the repo.
3. Install the APK. The first time the helper runs, the app grants itself `WRITE_SECURE_SETTINGS` through it, so it can turn Wireless Debugging on itself, which is handy after a reboot.

```sh
adb install -r -g fingertipsmcp-android-vX.Y.Z.apk
```

4. Pair, expose the server, and start the helper:

```sh
fingertips pair --name laptop
fingertips server
fingertips helper
```

`fingertips` targets the release package (`com.fingertipsmcp`) and stores pairing state in `~/.config/fingertips/pairing.json` (or `$XDG_CONFIG_HOME/fingertips`). Override the package with `FINGERTIPS_PKG`, the directory that holds the pairing and session files with `FINGERTIPS_STATE`, or the pairing file alone with `PAPI_PAIRING`. `pair` writes the pairing file and does not print the token. `server` forwards the on-device API to localhost; run it again if the tunnel drops.

Open FingertipsMCP on the phone (its launcher icon says Fingertips) for screen reading and taps, Wireless Debugging, and the computers that have access. The screen reading switch starts the helper and shuts it down. On Android 11 and newer the same screen can turn Wireless Debugging on and pair it. You can remove a computer's access there at any time.

### Pairing

A paired computer holds its own bearer token. Mint one with `fingertips pair`. The shell user is already trusted, so no approval on the phone is needed. It reads the token from the app with `adb shell content read`, so the token reaches only adb's output and the pairing file, never the phone's log:

```sh
fingertips pair --name laptop
fingertips server
```

The saved JSON looks like this (`scheme` `http`, host `127.0.0.1`):

```json
{"scheme":"http","host":"127.0.0.1","port":41234,"token":"ft_…","name":"laptop"}
```

The token gets every scope unless `--scopes` limits it, for example `fingertips pair --name viewer --scopes stream,observe`. An admin token can change a token's scopes later with `PATCH /v1/tokens/{id}`, and the app's settings screen shows each token's scopes.

Every request needs `Authorization: Bearer <token>`. Scopes, the `access_token` query exception, token admin, and error shapes are in [api.md](api.md).

### Helper

The helper is a separate `app_process` running as the shell user. It reads the screen, injects input, captures the screen, and runs privileged app commands. `/v1/device` reports it as `running`, `starting`, `needs_pairing`, `needs_usb`, or `stopped`, and `capabilities` shrinks to what works in the current state.

| Status | Meaning |
| --- | --- |
| `running` | The helper is connected. |
| `starting` | A launch is in progress. |
| `needs_pairing` | Android 11+ and no paired Wireless Debugging key yet. |
| `needs_usb` | Android 10. Relaunch from a computer. |
| `stopped` | The automatic restart gave up after repeated failures. |

Once paired, the app starts the helper itself after a crash, a package update, or a reboot, and when its screen reading switch is turned on. A start turns Wireless Debugging on if it is off, and puts it back as it was if the start fails, so the setting is only left on while the helper runs, which is what reaches Chrome too. When the setting was already on but no port can be found, adbd's mDNS record has gone, and the app turns the setting off for a second and on again, which makes adbd register again. Six failed starts in a row end the retries, and so does the setting going off while a start needs it on, since that is the user or Android refusing the network, and turning it on again would only put Android's prompt back up. Turning the setting on, a network change, or the switch starts over.

Launch it once from the computer:

```sh
fingertips helper
```

It uses the pairing from `fingertips pair`, so pair first. The process detaches, and the command waits up to 30 s for `GET /v1/device` to report the helper `running`. It fails with a message if a step fails or the helper does not come up. Set `FINGERTIPS_PKG` (and a pairing for that install) to start it for another install, such as a debug build. That USB start does not survive a reboot; run it again after a reboot, or start the listener and helper over Wireless Debugging. On Android 11+, after pairing Wireless Debugging once, the app restarts the helper itself after a crash or a package update while its process is running. With the helper down, it finds Wireless Debugging's port by trying the last port that worked, then this phone's own mDNS record, which adbd names after the phone's serial. The app reads the serial through the helper each time one registers, including one started over USB, and on a wireless launch or pairing. Until it has the serial, it tries every record it sees. Turning the helper switch off shuts that process down and leaves it stopped until the switch is turned on again. A successful launch leaves Wireless Debugging on, because that connection is also how the browser API reaches Chrome. The `WRITE_SECURE_SETTINGS` grant lets the app flip that switch. Until the helper has run once, turn Wireless Debugging on in Developer options.

On Android 10, browser calls use a USB tunnel instead. `fingertips helper` sets that up.

To pair Wireless Debugging from the phone (Android 11+), tap the Wireless debugging row in the app while it says "Not set up yet". It opens Developer options and puts a notification in the shade that takes the code inline. It stays there for 5 minutes. When mDNS sees this phone's pairing service, which means the system "Pair device with pairing code" dialog is open, the notification drops down over the dialog. If it doesn't drop down, read the code, pull down the shade, and type the code there. Do Not Disturb can hide the notification completely, so the app warns if it is on. Turn it off while pairing. The dialog has to stay in front: Android closes the pairing port when it is dismissed, including when you switch apps. On Android 10 that row is hidden.

Snapshots, injected input, screenshots, logcat, browser routes, and streams need the helper. Without it, an app launch works only while FingertipsMCP is on screen, and wake has a limited fallback. See [api.md](api.md) for which routes require the helper or the browser scope.

## Desktop Chrome

### Requirements

- Linux on x86-64 or ARM64, macOS, or Windows 10 or 11 on x64 or ARM64 with a 64-bit Java.
- Java 21, for example Temurin.
- Google Chrome, stable channel, where its installer puts it. Chromium works too, but sites can tell it apart; see [chrome.md](chrome.md#chromium).
- `jq` and `curl`, and [`scripts/fingertips`](../scripts/fingertips), only to call the API with `fingertips papi`.

### Install

Download `fingertipsmcp-chrome-v*.jar` from [GitHub Releases](https://github.com/die-net/fingertipsmcp/releases) and save it as `fingertipsmcp-chrome.jar`, the name the examples use. It is a single jar and needs nothing else from the repo.

It runs one of two ways:

- **Started by the MCP client**, with `--stdio`. The client runs it when it starts and stops it when it quits. It serves that one client, with no token, and has no viewer.
- **As a server**, over HTTP on loopback. Several clients and a viewer can share it, each with its own token. Mint one, then start it:

```sh
java -jar fingertipsmcp-chrome.jar token create
java -jar fingertipsmcp-chrome.jar
```

`token create` writes the port and token to `~/.config/fingertips-chrome/pairing.json` (`%LOCALAPPDATA%\fingertips-chrome\pairing.json` on Windows), readable only by you, in the same shape as the phone's pairing file. The token gets the `observe`, `control`, and `browser` scopes. `--scopes` replaces them, for example `--name viewer --scopes stream,control` for a viewer.

Either way, it starts Chrome with a profile of its own, so the first run is a clean Chrome to sign in to. Stopping FingertipsMCP for Chrome closes Chrome. [chrome.md](chrome.md) has the options, a Docker image, where its files live, and how it keeps Chrome looking like a person's.

## Connecting an agent

For the phone, start the helper (`fingertips helper`) and `fingertips server`, then point an MCP client at `http://127.0.0.1:<port>/mcp` with the bearer token, both from the pairing file. For desktop Chrome, either let the client start it on stdio, or point the client at the server the same way, with the port and token from its own pairing file. In Claude Code (project `.mcp.json`, or user scope in `~/.claude.json`), with both:

```json
{
  "mcpServers": {
    "phone": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    },
    "chrome": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/fingertipsmcp-chrome.jar", "--stdio"]
    }
  }
}
```

Cursor takes the same entries without `type`. Each client gets its own session, with its own Chrome tabs and its turn at the screen, as [sharing.md](sharing.md) describes.

Then ask the agent something concrete, for example:

> On the phone, use Chrome to open https://github.com/die-net/fingertipsmcp/ and go to the Releases page.

The tools, resources, and scopes are in [api.md](api.md#mcp), and what differs on desktop Chrome is in [api.md](api.md#fingertipsmcp-for-chrome-differences).

## Watching in the viewer

Open `http://127.0.0.1:<port>/viewer?access_token=<token>` in a desktop browser, with the port and token from the phone's or Chrome's pairing file. The page sets a cookie and drops the token from the URL. It plays the screen and sound with WebCodecs. **Take over** gives you the screen when an agent has it, and **Done** hands it back. The token needs the `stream` scope, and `control` too to use the screen. `fingertips pair` grants both. A Chrome token needs them asked for, as above.

- **The phone.** It plays the phone's screen, takes taps, keys, and text, rotates the screen, and copies and pastes between the computer and the phone.
- **Desktop Chrome.** It plays the tab Chrome shows, following it as tabs switch, and takes the mouse, the wheel, keys, and text. It needs FingertipsMCP for Chrome running as a server on a TCP port, so `--stdio` and `--unix` have no viewer.

## Calling the API directly

You can also call the REST and WebSocket API with `curl`, your own client, or `fingertips papi`. Calls that act on the phone or Chrome need a session from `POST /v1/sessions`, named in the `Mcp-Session-Id` header. `fingertips papi` keeps one for you, and `fingertips session id` and `fingertips session end` show and end it. For the phone, run `fingertips server` first so localhost reaches the on-device socket. For desktop Chrome, point `PAPI_PAIRING` at its pairing file.

```sh
fingertips papi GET /v1/device
fingertips papi GET /v1/openapi.json
PAPI_PAIRING=~/.config/fingertips-chrome/pairing.json fingertips papi GET /v1/browser/targets
```

Routes, auth, and OpenAPI are in [api.md](api.md). [development.md](development.md#calling-the-api-with-fingertips-dev-papi) has more `papi` examples.
