# Setting up PhoneAPI

This guide installs a release build and connects an agent to it. To build from source, see [development.md](development.md).

## Requirements

- A phone or emulator running Android 10 or newer, with USB debugging on. Wireless Debugging, to drop the cable later, needs Android 11.
- On the computer, `adb`, `jq`, and `curl` on your `PATH`. `phoneapi` shells out to these. Most machines already have `curl`. `adb` and `jq` usually need a separate install, for example `brew install android-platform-tools jq` on macOS, or [platform-tools](https://developer.android.com/tools/releases/platform-tools) from Google plus your OS package for `jq`.
- An [MCP](https://modelcontextprotocol.io) client, such as Claude Code or Cursor, if you want an agent to drive the phone.

## Install

1. Download a release APK (`phoneapi-v*.apk`) from [GitHub Releases](https://github.com/die-net/phoneapi/releases).
2. Copy [`scripts/phoneapi`](../scripts/phoneapi) somewhere on your `PATH`, for example `~/bin/phoneapi`. It does not need the rest of the repo.
3. Install the APK. The first time the helper runs, the app grants itself `WRITE_SECURE_SETTINGS` through it, so it can turn Wireless Debugging on itself, which is handy after a reboot.

```sh
adb install -r -g phoneapi-vX.Y.Z.apk
```

4. Pair, expose the server, and start the helper:

```sh
phoneapi pair --name laptop
phoneapi server
phoneapi helper
```

`phoneapi` targets the release package (`net.die.phoneapi`) and stores pairing state in `~/.config/phoneapi/pairing.json` (or `$XDG_CONFIG_HOME/phoneapi`). Override the package with `PHONEAPI_PKG`, the directory that holds the pairing and session files with `PHONEAPI_STATE`, or the pairing file alone with `PAPI_PAIRING`. `pair` writes the pairing file and does not print the token. `server` forwards the on-device API to localhost; run it again if the tunnel drops.

Open PhoneAPI on the phone for screen reading and taps, Wireless Debugging, and the computers that have access. The screen reading switch starts the helper and shuts it down. On Android 11 and newer the same screen can turn Wireless Debugging on and pair it. You can remove a computer's access there at any time.

## Pairing

A paired computer holds its own bearer token. Mint one with `phoneapi pair`. The shell user is already trusted, so no approval on the phone is needed:

```sh
phoneapi pair --name laptop
phoneapi server
```

The saved JSON looks like this (`scheme` `http`, host `127.0.0.1`):

```json
{"scheme":"http","host":"127.0.0.1","port":41234,"token":"pa_…","name":"laptop"}
```

The token gets every scope unless `--scopes` limits it, for example `phoneapi pair --name viewer --scopes stream,observe`. An admin token can change a token's scopes later with `PATCH /v1/tokens/{id}`, and the app's settings screen shows each token's scopes.

Every request needs `Authorization: Bearer <token>`. Scopes, the `access_token` query exception, token admin, and error shapes are in [api.md](api.md).

## Helper

The helper is a separate `app_process` running as the shell user. It reads the screen, injects input, captures the screen, and runs privileged app commands. `/v1/device` reports it as `running`, `starting`, `needs_pairing`, `needs_usb`, or `stopped`, and `capabilities` shrinks to what works in the current state.

| Status | Meaning |
| --- | --- |
| `running` | The helper is connected. |
| `starting` | A launch is in progress. |
| `needs_pairing` | Android 11+ and no paired Wireless Debugging key yet. |
| `needs_usb` | Android 10. Relaunch from a computer. |
| `stopped` | The automatic restart gave up after repeated failures. |

Launch it once from the computer:

```sh
phoneapi helper
```

`phoneapi helper <package>` starts it for another install, such as a debug build. The process detaches, so the command returns while the helper keeps running. That USB start does not survive a reboot; run it again after a reboot, or start the listener and helper over Wireless Debugging. On Android 11+, after pairing Wireless Debugging once, the app restarts the helper itself after a crash or a package update while its process is running. Turning the helper switch off shuts that process down and leaves it stopped until the switch is turned on again. A successful launch leaves Wireless Debugging on, because that connection is also how the browser API reaches Chrome. The `WRITE_SECURE_SETTINGS` grant lets the app flip that switch. Until the helper has run once, turn Wireless Debugging on in Developer options.

On Android 10, browser calls use a USB tunnel instead. `phoneapi helper` sets that up.

To pair Wireless Debugging from the phone (Android 11+), tap the Wireless debugging row in the app while it says "Not set up yet". It opens Developer options and puts a notification in the shade that takes the code inline. It stays there for 5 minutes. When mDNS sees this phone's pairing service, which means the system "Pair device with pairing code" dialog is open, the notification drops down over the dialog. If it doesn't drop down, read the code, pull down the shade, and type the code there. Do Not Disturb can hide the notification completely, so the app warns if it is on. Turn it off while pairing. The dialog has to stay in front: Android closes the pairing port when it is dismissed, including when you switch apps. On Android 10 that row is hidden.

Snapshots, injected input, screenshots, logcat, browser routes, and streams need the helper. App launch and wake still run without it, with limited fallbacks. See [api.md](api.md) for which routes require the helper or the browser scope.

## Connecting an agent

Start the helper (`phoneapi helper`) and `phoneapi server`, then point an MCP client at `http://127.0.0.1:<port>/mcp` with the bearer token, both from the pairing file. In Claude Code (project `.mcp.json`, or user scope in `~/.claude.json`):

```json
{
  "mcpServers": {
    "phone": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Cursor takes the same entry without `type`. Each client gets its own session, with its own Chrome tabs and its turn at the screen, as [sharing.md](sharing.md) describes.

Then ask the agent something concrete, for example:

> On the phone, use Chrome to open https://github.com/die-net/phoneapi/ and go to the Releases page.

The tools, resources, and scopes are in [api.md](api.md#mcp).

## Watching in the viewer

Open `http://127.0.0.1:<port>/viewer?access_token=<token>` in a desktop browser. The page sets a cookie and drops the token from the URL. It plays the phone's screen and sound with WebCodecs, takes taps, keys, and text, rotates the screen, and copies and pastes between the computer and the phone. **Take over** gives you the screen when an agent has it, and **Done** hands it back. The token needs the `stream` scope, which `phoneapi pair` grants.

## Calling the API directly

You can also call the REST and WebSocket API with `curl`, your own client, or `phoneapi papi`. Calls that act on the phone need a session from `POST /v1/sessions`, named in the `Mcp-Session-Id` header. `phoneapi papi` keeps one for you, and `phoneapi session id` and `phoneapi session end` show and end it. Run `phoneapi server` first so localhost reaches the on-device socket.

```sh
phoneapi papi GET /v1/device
phoneapi papi GET /v1/openapi.json
```

Routes, auth, and OpenAPI are in [api.md](api.md). [development.md](development.md#calling-the-api-with-phoneapi-dev-papi) has more `papi` examples.
