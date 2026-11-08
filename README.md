# FingertipsMCP

FingertipsMCP lets an AI agent use your Android phone the way you would: read the screen, tap and type, run your apps, and browse in Chrome with your own logins (or the agent's own, if you prefer). Install the app, plug in a computer, and point an MCP client such as Claude Code or Cursor at it. Then ask for something concrete:

> On the phone, use Chrome to open https://github.com/die-net/fingertipsmcp/ and go to the Releases page.

## What it does

**Reads pages as text and data, not pixels.** Rather than reading screenshots or the raw DOM, an agent reads a Chrome page as markdown, cleaned up by Mozilla's Readability, a slice at a time, or searches it for text and links. A PDF Chrome shows reads the same way, through Mozilla's pdf.js. JSON a page embeds (Next.js data, JSON-LD, state blobs in scripts) or fetches after it loads can be outlined and queried by path, so prices, listings, and search results come back as data. Apps are read through Android's accessibility tree as compact lines like `[e12] button "Sign in"`. Screenshots are there as a last resort.

**Keeps the agent's context small.** Every outline and read is capped and says what it left out. A read of a page that has not changed comes back as one line. Links come once each, without tracking parameters. One call can open a page, read it, and close it, or act, wait for the result, and show it. Password and one-time-code fields are masked in screen outlines.

**Looks like a person on their own phone.** Taps and keys arrive as a real touchscreen, with slight variation in position and timing, and typing taps the keyboard's keys. Scripts that read a page run in an isolated world and leave nothing in the page. FingertipsMCP doesn't change Chrome's user agent, viewport, or settings. Sites and apps that act differently under automation see a person, not a test harness.

**Shares the phone.** Several agents, scripts, and a person can use the phone at once. Each gets its own Chrome tabs, and those tabs work in the background while someone else has the screen. One client holds the screen at a time, the others wait their turn, and the screen is tidied between them: keyboard hidden, back to home, and locked again if it was locked. An agent can start separate sessions for subagents working in parallel. Incognito tabs keep a page away from the phone's accounts.

**Lets you watch and step in.** A viewer in your desktop browser plays the phone's screen and sound. You can tap, type, and copy and paste between computer and phone, or take over when an agent hits a CAPTCHA or a login, then hand the screen back.

**Stays on your cable.** The API is reachable only through ADB, over USB or Wireless Debugging. No network port opens on the phone. Each client gets its own token, which you can remove in the app. A token can be limited to some of what FingertipsMCP does: reading the screen, acting on it, Chrome, the viewer, and managing tokens. So an agent can get Chrome without the rest of the phone, or a viewer only the screen.

It also launches and stops apps, sends intents, wakes the phone and unlocks it with a stored PIN, reads and writes the clipboard, and waits for windows, screen elements, page loads, or network requests.

## Get started

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

[docs/setup.md](docs/setup.md) covers each step, Wireless Debugging so you can drop the cable, getting going again after a reboot, and opening the viewer.

## Limits

- A computer has to reach the phone over ADB. Wireless Debugging, and restarting FingertipsMCP's helper without a cable, need Android 11.
- Windows an app marks secure, such as many banking apps and Chrome's incognito tabs, show black in screenshots and the viewer.
- It uses Android internals that OEM skins and new Android releases can change. It is sideloaded, not a Play Store app.
- Apps that check device integrity can still notice USB debugging is on.

## Learn more

- [Setup](docs/setup.md): install, pairing, the helper, Wireless Debugging, the viewer, and calling the API from the command line.
- [API](docs/api.md): MCP tools, REST and WebSocket routes, scopes, errors, and OpenAPI.
- [Sharing the phone](docs/sharing.md): sessions, the screen queue, tab ownership, and how Chrome treats background tabs.
- [Architecture](docs/architecture.md): how the app, the shell helper, and Chrome DevTools fit together.
- [Development](docs/development.md): building from source, tests, and emulators.

## AI Disclosure

Though the design, architecture, some of the code, reviews, and testing were
done by a human, most of the code was written by Opus 5.5 and Grok 4.7.

## License

Copyright 2026 Aaron Hopkins and contributors. All rights reserved.

Licensed under the [Apache License, Version 2.0](LICENSE).

`app/src/main/assets/readability/` is Mozilla's [Readability.js](https://github.com/mozilla/readability) 0.6.0, under the Apache License 2.0; see its `LICENSE.md`.

`app/src/main/assets/pdfjs/` is the legacy build of Mozilla's [pdf.js](https://github.com/mozilla/pdf.js) 6.4.299 (`pdfjs-dist`), unmodified, under the Apache License 2.0; see its `LICENSE`.
