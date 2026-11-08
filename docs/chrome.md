# FingertipsMCP for Chrome

FingertipsMCP for Chrome is FingertipsMCP for a computer, with a desktop or headless. It starts Google Chrome on Linux or macOS and serves the same browser routes, `browser_*` MCP tools, waits, and screenshots as FingertipsMCP for Android, so one MCP client can drive a phone and a Chrome side by side. The goal is the same as on the phone: to look like a person using an unmodified Chrome, not like an automated one.

It runs as a single jar (`fingertipsmcp-chrome-vX.Y.Z.jar`, Java 21). Docker is optional packaging.

## Requirements

- Java 21 (for example Temurin)
- Google Chrome, stable channel, where its installer puts it. Chromium works as a fallback with a cost; see [Chromium](#chromium).
- Linux on x86-64 or ARM64, or macOS. Google ships Chrome for ARM64 Linux through the same apt repository, so a Raspberry Pi 4 or 5 on 64-bit Raspberry Pi OS can run `google-chrome-stable`. Raspberry Pi OS's own browser is Chromium.
- curl and jq if you want `fingertips papi` to call it

## Run it

Download `fingertipsmcp-chrome-vX.Y.Z.jar` from the [latest release](https://github.com/die-net/fingertipsmcp/releases/latest) and save it as `fingertipsmcp-chrome.jar`, the name the examples use. Then:

```sh
java -jar fingertipsmcp-chrome.jar token create
java -jar fingertipsmcp-chrome.jar
```

`token create` mints a bearer token and writes `~/.config/fingertips-chrome/pairing.json` (or `$XDG_CONFIG_HOME/fingertips-chrome/pairing.json`), readable only by you. It has the same shape as the phone's, so the `fingertips` client reads it:

```sh
PAPI_PAIRING=~/.config/fingertips-chrome/pairing.json fingertips papi GET /v1/browser/targets
PAPI_PAIRING=~/.config/fingertips-chrome/pairing.json fingertips papi POST /v1/browser/tabs '{"url":"https://example.com/"}'
```

The second command starts Chrome with its own profile in `~/.local/share/fingertips-chrome/chrome-profile` (`~/Library/Application Support/fingertips-chrome/chrome-profile` on macOS, or `$XDG_DATA_HOME/fingertips-chrome`), opens one blank window, and listens on `127.0.0.1` at a port picked once and kept in `settings.json`. If Chrome exits, the next call starts it again; see [Windows](#windows). Stopping FingertipsMCP for Chrome closes Chrome.

`papi` starts a REST session for itself, as with the phone. A client of its own calls `POST /v1/sessions` and sends the id in `Mcp-Session-Id`; see [sharing.md](sharing.md).

Tokens default to the `observe`, `control`, and `browser` scopes. `--scopes` takes others (`admin` manages tokens over the API), and `--no-pairing` prints the token instead of writing the file. `java -jar fingertipsmcp-chrome.jar token list` and `token revoke ID` manage them.

## MCP

Over Streamable HTTP, point the client at `http://127.0.0.1:<port>/mcp` with the bearer token. DNS rebinding protection is on: `/mcp` refuses a request whose `Host` is not a loopback name, or that carries an `Origin`, as a web page's request does, so a page cannot reach it through a rebinding domain.

Or let the client start FingertipsMCP for Chrome on stdio. With a phone alongside:

```json
{
  "mcpServers": {
    "phone": {
      "command": "/absolute/path/to/fingertips",
      "args": ["mcp"]
    },
    "chrome": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/fingertipsmcp-chrome.jar", "--stdio"]
    }
  }
}
```

`--stdio` starts its own Chrome and serves one client with the `observe`, `control`, and `browser` scopes, as one session that lasts until the client closes stdin. It does not listen on HTTP. Chrome allows one process per profile, so a stdio server next to an HTTP one needs its own `--profile`.

The tools are the phone's `device_info`, `session_start`, `session_end`, `screen_release`, `wait_for`, `browser_*`, and `screenshot`. Their descriptions say how they behave on a desktop.

## Sessions and the screen

Sessions, tab ownership, and the screen lease work as on the phone ([sharing.md](sharing.md)). Each session sees its own tabs and tabs no session owns, and its tabs close when it ends. The screen is the browser's one mouse and keyboard: clicks, scrolls, gestures, keys, and typing take it, since they bring their tab to the front and move one pointer. Reading, snapshots, scripts, and waits do not.

Background means background here too, so a workflow that only works while its tab is showing fails every time, not only when another session is busy. A session without the screen opens its tabs hidden, and they stay hidden until it acts on them. Only the screen holder's tab shows. Letting the screen go shows a blank placeholder tab, which `browser_targets` leaves out, unless `screen_release` has `cleanup` false, which leaves the tab showing for a person. The next holder gets no note about it. Switching tabs this way does not bring Chrome over other apps, but a session's first input on a hidden tab does, as a person switching to it would.

## Options

| Option | |
| --- | --- |
| `--host ADDR` | Listen address. Default `127.0.0.1`. A non-loopback address is logged as a warning. |
| `--port N` | TCP port. Default: from `settings.json`. |
| `--unix PATH` | Listen on a Unix socket, mode 0600, instead of TCP. |
| `--stdio` | MCP over stdin and stdout. Logs go to stderr. |
| `--chrome PATH` | Browser binary. Also `FINGERTIPS_CHROME`. |
| `--profile DIR` | Chrome user data directory. |
| `--headless` | Chrome `--headless=new`. |
| `--show-automation` | Leave `navigator.webdriver` true; see [Stealth](#stealth). |
| `--chrome-arg ARG` | One more Chrome flag. Repeatable. |
| `--attach PORT\|URL` | Development: use a Chrome already started with `--remote-debugging-port`. |

## How it drives Chrome

FingertipsMCP for Chrome starts Chrome with `--remote-debugging-pipe`: DevTools runs over two file descriptors instead of a port, so no other process can connect and there is no listening socket to find. One browser connection carries every tab as a flattened session (`Target.attachToTarget`). Other flags are kept to `--user-data-dir`, `--no-first-run`, `--no-default-browser-check`, and, explained under [Stealth](#stealth), `--disable-blink-features=AutomationControlled` and `--no-startup-window`: no `--enable-automation`, `--no-sandbox`, or `--disable-gpu`. The same DevTools hygiene as on Android applies: scripts run in an isolated world, and `Runtime`, `Debugger`, and `Emulation` are not enabled on pages (`browser_console` enables `Runtime` only for its own short session).

Browser input defaults to `cdp`, which on a desktop is mouse and keyboard events, never touch:

- **Clicks** bring the tab to the front if it is hidden, scroll the element into view with the mouse wheel, move the pointer there along a bowed path timed by Fitts's law that often overshoots and settles on a long move, rest briefly, and press and release. The pointer stays where it was left, so the next move starts there. Points land on whole device pixels.
- **Swipes** scroll with the wheel, a few notches per flick, with the pointer over the page or element. A swipe up scrolls down, as on a phone.
- **Gestures** with one path are a mouse drag. More than one path is refused.
- **Typing** clicks the field, then sends each character as key down and key up, Shift held across capitals, at a person's pace inside `minDelayMs`..`maxDelayMs`, with fast keys rolling over. Text no key on a US layout produces, such as emoji, is inserted as an input method commits it. `clear` is select-all (Ctrl+A, or Command+A on macOS) and Backspace.

One action runs at a time. `auto`, `cdp`, and `cdp-mouse` all mean this mouse and keyboard. `touch` is refused with 422 `unsupported`, since a desktop has no touch screen. OS-level input, such as Linux XTEST or uinput and macOS CGEvent, would sit behind the same seam (`DesktopEvents`); FingertipsMCP for Chrome does not have it yet. Incognito tabs are not available either (422 `unsupported`).

`browser_read` and `browser_find` read a PDF as they do on the phone. Chrome's viewer holds no text, so FingertipsMCP for Chrome replaces the tab's page with an empty one at the same address and reads the file there with pdf.js. The window then shows that empty page instead of the PDF.

## Stealth

How human the browser looks depends most on where it runs:

1. **A real desktop session.** A person's display, GPU, fonts, and window. Best.
2. **Xorg with a GPU.** Real rendering and WebGL, but no person's desktop around it.
3. **Xvfb**, as in the Docker image. Rendering falls back to software, which WebGL and canvas fingerprints show.
4. **Headless** (`--headless`). The easiest to tell apart from a person's Chrome.

### navigator.webdriver

Chrome sets `navigator.webdriver` to true whenever a remote-debugging switch is on its command line, the pipe included, even without `--enable-automation`. That is one of the first things bot checks read. So FingertipsMCP for Chrome passes `--disable-blink-features=AutomationControlled`, which puts it back to false. Checked against an uncontrolled Chrome 155 on macOS, a page then sees the same thing in every way it can look: the value, the property's descriptor, its getter's source, name, and length, and its place in `Navigator.prototype`'s property list. `--show-automation` leaves the flag off.

Chrome answers that flag with a bar warning about an unsupported command-line flag, on the tab it opens at launch. So a windowed Chrome starts with `--no-startup-window`, which opens no window at all, and FingertipsMCP for Chrome opens the first window itself through DevTools, with one blank tab. No tab then shows the bar: the first window, tabs opened later, and windows opened later all have the same content area as an uncontrolled Chrome's. Headless Chrome has no bar to show, so it starts the usual way.

`--no-startup-window` has a cost: Chrome skips its startup pages, so a profile set to continue where it left off does not restore its last session, and nothing is restored after a crash either. Each run of FingertipsMCP for Chrome starts with one blank window. Cookies, logins, history, and settings are kept in the profile as usual.

### Windows

FingertipsMCP for Chrome keeps Chrome with a window open:

- Closing the last tab through the API first opens a blank tab, as a person opens a new tab before closing their last one. Chrome on Linux exits when its last window closes, which would end session cookies and logins.
- On macOS, Chrome keeps running when a person closes its last window. Opening a tab then opens a window again.
- If Chrome exits anyway, for example when a person closes its last window on Linux or quits it, the next call starts it again with a fresh blank window. `device_info` reports `helper` `stopped` until then.

### ARM

Chrome on ARM tells sites so. User-Agent Client Hints report the architecture as `arm` (with a 64-bit bitness), and WebGL names the device's GPU, such as a Raspberry Pi's VideoCore, or SwiftShader under Xvfb. Far fewer people browse from ARM Linux than from x86-64 Linux, Windows, or a Mac, so the same behavior stands out more there. Apple silicon Macs are ARM too, but are common.

### Chromium

When Google Chrome is not installed, as on a stock Raspberry Pi OS, FingertipsMCP for Chrome runs Chromium and logs a warning. Sites can tell: Client Hints (`Sec-CH-UA`) name Chromium rather than Google Chrome, and Chromium lacks Chrome's proprietary codecs (H.264, AAC) and Widevine. Install Google Chrome where it is available.

## Docker

The image `ghcr.io/die-net/fingertipsmcp-chrome` is Debian with Google Chrome, Java 21, Xvfb, and a desktop font set (Liberation, DejaVu, Noto including CJK and color emoji), for `linux/amd64` and `linux/arm64`.

```sh
docker volume create fingertips-chrome-config
docker volume create fingertips-chrome-data
docker run --rm -v fingertips-chrome-config:/home/fingertips/.config/fingertips-chrome ghcr.io/die-net/fingertipsmcp-chrome token create
docker run -d --name fingertips-chrome --shm-size=1g \
  -v fingertips-chrome-config:/home/fingertips/.config/fingertips-chrome \
  -v fingertips-chrome-data:/home/fingertips/.local/share/fingertips-chrome \
  -p 127.0.0.1:47100:47100 \
  ghcr.io/die-net/fingertipsmcp-chrome
docker run --rm -v fingertips-chrome-config:/c alpine cat /c/pairing.json > ~/fingertips-chrome-pairing.json
```

The container listens on port 47100 (`FINGERTIPS_PORT`) on all of its interfaces; publish it on the host's loopback only, as above. `FINGERTIPS_SCREEN` sets the Xvfb screen (default `1920x1080x24`), and `TZ` the time zone. Passing `DISPLAY` and the X socket uses that display instead of Xvfb. Chrome runs as an unprivileged user with its sandbox. If it fails to start because the container blocks the user namespaces its sandbox needs, allow them (for example a seccomp profile that permits `clone` with namespace flags) rather than turning the sandbox off. `--shm-size` keeps large pages from crashing the renderer.

To build the image from a checkout:

```sh
./gradlew :chrome:fatJar
docker build -f chrome/docker/Dockerfile -t fingertipsmcp-chrome chrome
```

## Files

| Path | |
| --- | --- |
| `~/.config/fingertips-chrome/settings.json` | The TCP port |
| `~/.config/fingertips-chrome/tokens.json` | Token hashes |
| `~/.config/fingertips-chrome/pairing.json` | Address and token for clients (mode 0600) |
| `~/.local/share/fingertips-chrome/chrome-profile` | Chrome's profile |
| `~/.local/share/fingertips-chrome/chrome.log` | Chrome's own output |

The API is described in [api.md](api.md), with the [differences from Android](api.md#fingertipsmcp-for-chrome-differences).
