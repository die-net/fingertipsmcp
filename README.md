# FingertipsMCP

FingertipsMCP lets an AI agent use your Android phone, or Google Chrome on your computer, the way you would: read the screen, tap or click and type, run your apps, and browse with your own logins (or the agent's own, if you prefer). Point an MCP client such as Claude Code or Cursor at it, then ask for something concrete:

> On the phone, use Chrome to open https://github.com/die-net/fingertipsmcp/ and go to the Releases page.

It comes in two parts that serve the same API and MCP tools:

- **FingertipsMCP for Android**, an app for a phone on Android 10 or newer, reached from a computer over ADB.
- **FingertipsMCP for Chrome**, a jar that starts Google Chrome on Linux, macOS, or Windows, in a desktop session, under Xvfb or Docker, or headless.

One MCP client can use a phone and a desktop Chrome side by side.

## What it does

**Reads pages as text and data, not pixels.** Rather than reading screenshots or the raw DOM, an agent reads a Chrome page as markdown, cleaned up by Mozilla's Readability, a slice at a time, or searches it for text and links. A PDF Chrome shows reads the same way, through Mozilla's pdf.js. JSON a page embeds (Next.js data, JSON-LD, state blobs in scripts) or fetches after it loads can be outlined and queried by path, so prices, listings, and search results come back as data. On the phone, apps are read through Android's accessibility tree as compact lines like `[e12] button "Sign in"`. Screenshots are there as a last resort.

**Keeps the agent's context small.** Every outline and read is capped and says what it left out. A read of a page that has not changed comes back as one line. Links come once each, without tracking parameters. One call can open a page, read it, and close it, or act, wait for the result, and show it. Password and one-time-code fields are masked in screen outlines.

**Looks like a person, not a test harness.** On the phone, taps and keys arrive as a real touchscreen, with slight variation in position and timing, and typing taps the keyboard's keys. On a computer, the mouse moves along a curved path at a hand's speed, sometimes overshooting, and keys go down and up at a typist's pace. Chrome is not marked as automated: `navigator.webdriver` stays false and no warning bar shows. Scripts that read a page run in an isolated world and leave nothing in the page. FingertipsMCP doesn't change Chrome's user agent, viewport, or settings. Sites and apps that act differently under automation see a person.

**Shares the device.** Several agents, scripts, and a person can use a phone or a desktop Chrome at once. Each gets its own Chrome tabs, and those tabs work in the background while someone else has the screen. One client holds the screen at a time, the others wait their turn, and the screen is tidied between them. On the phone that means the keyboard hidden, back to home, and locked again if it was locked. An agent can start separate sessions for subagents working in parallel. Desktop Chrome 149 or later stays behind the app you are using while an agent works in it, so you can keep working on the same computer.

**Lets you watch and step in.** A viewer in your desktop browser plays the phone's screen, or the tab desktop Chrome shows, with its sound. You can tap or click, type, and copy and paste, or take over when an agent hits a CAPTCHA or a login, then hand the screen back.

**Stays on your computer.** The phone's API is reachable only through ADB, over USB or Wireless Debugging, and no network port opens on the phone. FingertipsMCP for Chrome listens on loopback by default, or a Unix socket, and talks to Chrome over a pipe rather than a debugging port. Each client gets its own token, which you can revoke. A token can be limited to some of what FingertipsMCP does: reading the screen, acting on it, Chrome, the viewer, and managing tokens. So an agent can get Chrome without the rest of the phone, or a viewer only the screen.

On the phone, it also launches and stops apps, sends intents, wakes the phone and unlocks it with a stored PIN, and reads and writes the clipboard. On both, it waits for windows, screen elements, page loads, or network requests.

## Get started

### A phone

You need a phone running Android 10 or newer with USB debugging on, and `adb`, `jq`, and `curl` on your computer.

1. Download the APK from [Releases](https://github.com/die-net/fingertipsmcp/releases), and copy [`scripts/fingertips`](scripts/fingertips) onto your `PATH`.
2. Install it, pair, and start it:

```sh
adb install -r -g fingertipsmcp-android-vX.Y.Z.apk
fingertips pair --name laptop
fingertips server
fingertips helper
```

3. Add the phone to your MCP client, with the port and token from `~/.config/fingertips/pairing.json`. For Claude Code:

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

### Desktop Chrome

You need Java 21 or later and Google Chrome on Linux, macOS, or Windows.

1. Download `fingertipsmcp-chrome-vX.Y.Z.jar` from [Releases](https://github.com/die-net/fingertipsmcp/releases) and save it as `fingertipsmcp-chrome.jar`.
2. Add it to your MCP client, which starts it and Chrome when it needs them:

```json
{
  "mcpServers": {
    "chrome": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/fingertipsmcp-chrome.jar", "--stdio"]
    }
  }
}
```

To share one Chrome among several clients, or open the viewer, run it as a server instead, with `java -jar fingertipsmcp-chrome.jar token create` and then `java -jar fingertipsmcp-chrome.jar`.

[docs/setup.md](docs/setup.md) covers each step for both, Wireless Debugging so you can drop the phone's cable, getting going again after a reboot, and opening the viewer.

## Limits

- A computer has to reach the phone over ADB. Wireless Debugging, and restarting FingertipsMCP's helper without a cable, need Android 11.
- Windows an app marks secure, such as many banking apps and Chrome's incognito tabs, show black in the phone's screenshots and viewer.
- The Android app uses Android internals that OEM skins and new Android releases can change. It is sideloaded, not a Play Store app.
- Apps that check device integrity can still notice USB debugging is on.
- FingertipsMCP for Chrome runs on Linux, macOS, and Windows. Its input goes through Chrome's DevTools, not the operating system's mouse and keyboard. It has no incognito tabs. Under Xvfb or headless, Chrome is easier for a site to tell apart from a person's.

## Learn more

- [Setup](docs/setup.md): installing on a phone and a computer, pairing, the helper, Wireless Debugging, the viewer, and calling the API from the command line.
- [API](docs/api.md): MCP tools, REST and WebSocket routes, scopes, errors, and OpenAPI.
- [Sharing](docs/sharing.md): sessions, the screen queue, tab ownership, and how Chrome treats background tabs.
- [Architecture](docs/architecture.md): how the app, the shell helper, and Chrome DevTools fit together.
- [FingertipsMCP for Chrome](docs/chrome.md): options, Docker, how it drives Chrome, and how it keeps Chrome looking like a person's.
- [Development](docs/development.md): building from source, tests, and emulators.

## AI Disclosure

Though the design, architecture, some of the code, reviews, and testing were
done by a human, most of the code was written by Opus 5.5 and Grok 4.7.

## License

Copyright 2026 Aaron Hopkins and contributors. All rights reserved.

Licensed under the [Apache License, Version 2.0](LICENSE).

`browser/src/main/resources/com/fingertipsmcp/browser/readability/` is Mozilla's [Readability.js](https://github.com/mozilla/readability) 0.6.0, under the Apache License 2.0; see its `LICENSE.md`.

`browser/src/main/resources/com/fingertipsmcp/browser/pdfjs/` is the legacy build of Mozilla's [pdf.js](https://github.com/mozilla/pdf.js) 6.4.299 (`pdfjs-dist`), unmodified, under the Apache License 2.0; see its `LICENSE`.
