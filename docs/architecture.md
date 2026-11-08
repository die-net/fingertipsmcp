# FingertipsMCP architecture

FingertipsMCP is meant to feel like giving a robot their first cell phone: install the app, plug it in, and use it. The robot has neither eyes nor fingers, so the app has to help it see the screen and act without asking a human to babysit each step.

Seeing is primarily by way of a structured UI tree (Android's a11y tree or Chrome's AX tree). Screenshots are available a last resort for the client to give to its own vision model. Input looks like what a person would do: real touchscreen events with slight randomization on position and timing and typing that prefers tapping keys over dumping text into a field.

A small helper daemon runs as the `shell` user (uid 2000) via `app_process`, usually started with `fingertips helper` over `adb shell` (or restarted later over Wireless Debugging). The `shell` user isn't root, but does have significantly elevated permissions over what a normal app is allowed to do.

## Overview

```mermaid
flowchart LR
  Host["Host: fingertips CLI / MCP client"]
  Host -->|"adb forward + Bearer"| App["App process: Ktor + MCP"]
  App -->|AIDL| Helper["Helper: shell uid app_process"]
  Helper --> Tree["UiAutomation tree"]
  Helper --> Inject["InputManager inject"]
  Helper --> Cap["screencap / mirror / audio"]
  App -->|"CDP via adbd 11+ or USB tunnel 10"| Chrome["Chrome DevTools"]
```

| Piece | Role |
| --- | --- |
| **`app/`** | Android: settings UI, `ListenerService`, the abstract-socket engine, helper client, adb DevTools sockets, touch and key injection, encoders, viewer assets. `AppGraph` wires the shared modules |
| **`browser/`** | JVM: DevTools sessions, `BrowserServiceImpl`, page reading (`page-read.js` and the other page scripts, Readability, pdf.js), the humanizer, browser waits |
| **`server/`** | JVM: Ktor REST/WebSocket routes, bearer auth, token store, MCP and sessions, the screen lease, tab ownership |
| **`chrome/`** | JVM app: desktop Chrome over `--remote-debugging-pipe`, its CLI, and the Docker image. See [FingertipsMCP for Chrome](#fingertipsmcp-for-chrome) |
| **`helper/`** | Separate `app_process` as shell UID: UiAutomation snapshots, motion/key injection, screencap, display mirror, DevTools socket relay, logcat |
| **`api-model/`** | Shared `@Serializable` DTOs; same types drive MCP tool schemas and `GET /v1/openapi.json` |
| **`scripts/fingertips`** | Production client (pair, server, helper, papi, session) |
| **`scripts/fingertips-dev`** | Repo wrapper: debug package, `.dev/pairing.json`, `setup` |

The app is plain Kotlin: `AppGraph` is the composition root. Ktor and MCP sit on a `ServerServices` boundary so routes can be tested with fakes. `browser/` and `server/` are plain JVM modules with no Android types, so code that does not need a phone can run elsewhere; `app/` converts helper and Android types at its edge, and sends their slf4j logs to logcat.

## Transport: abstract socket + ADB only

The API listens on an Android **abstract** `LocalServerSocket` named after the package. A computer reaches it with:

```text
adb forward tcp:<port> localabstract:<package>
```

then talks HTTP to `http://127.0.0.1:<port>`.

`ListenerService` (foreground, `specialUse`) holds that socket. The host starts it with `fingertips helper` (`am start-foreground-service`). `fingertips server` only installs the `adb forward` onto that socket.

Unauthenticated or unknown Bearer tokens get an **empty 404** (no banner).

## Pairing and auth

Bearer tokens are minted over ADB (`CREATE_TOKEN` through `ShellCommandReceiver`, DUMP-protected). The shell user is already trusted, so the phone does not need an Allow dialog for that path. `fingertips pair` writes pairing JSON (scheme `http`, host `127.0.0.1`, port, token) to `~/.config/fingertips/pairing.json`. `fingertips server` installs the forward from that file.

Scopes: `observe`, `control`, `browser`, `stream`, `admin`. Routes and MCP tools are gated by scope and by `capabilities` from `/v1/device`. `?access_token=` is only for WebSocket upgrades and `GET /viewer` (which sets a cookie and redirects), not for `/mcp`.

Wireless Debugging pairing on Android 11+ is a separate concept. It allows the app to self-pair so the helper and Chrome DevTools keep working without USB.

## App vs helper

Chrome’s DevTools socket and realistic input injection are not available to a normal app UID. The helper runs as `shell` (or root on some emulators) via `app_process`, registers over a protected Binder provider, and detaches with `setsid` so it survives the `adb shell` session ending. One helper runs per app package: it listens on an abstract-namespace socket named `fingertips-helper-<package>`, and closes each connection to it as soon as it accepts it, reading and writing nothing. A second launch for the same package fails to listen, connects to the name to confirm another helper holds it, and exits at once; any other listen failure runs the helper unlocked, with a warning. The kernel frees the name when the helper dies, and it has no file owner, so a helper running as root and one running as shell see the same lock. A release and a debug build each get their own helper, though Android lets only one of them hold UiAutomation at a time, so the second one's screen reads fail until the first lets go (3 idle minutes with no event subscriber).

The helper is required for UI tree, injected input, screenshots, logcat, browser CDP plumbing, and streams. Without it, capabilities shrink; wake has a limited fallback, and launching an app works only while FingertipsMCP is in front.

Status reported on `/v1/device`: `running`, `starting`, `needs_pairing`, `needs_usb`, `stopped`.

## Seeing the screen

Primary vision is a compact tree, not a pixel dump. Format for agents looks like `[e12] button "Sign in"` with bounds and state.

- **Android UI:** helper UiAutomation (`SnapshotEngine`, refs, find, node actions). Reads trust UiAutomation's node cache for at most 500 ms, and an action ends that at once, because an app can change a node without sending an event (`NodeCacheAge`).
- **Chrome:** CDP Accessibility domain (`Accessibility.getFullAXTree`), not the DOM. The DOM is used for scroll-into-view, quads, and similar action plumbing.
- Prefer tree → find → act / tap over screenshots.
- Screenshot is helper `screencap -p`, marked as a last resort for agents.

## Input

All touches and keys go through helper InputManager injection, stamped as a real touchscreen source (not accessibility `dispatchGesture`, which newer Android can flag). Humanization (jitter, curved swipes, short holds) defaults on for person-like motion. The viewer streams live multi-touch on `WS /v1/input/pointer` instead of replaying a finished path. The socket closing cancels the gesture, which releases the touch.

`tap` injects coordinates; `ui_act` can use semantic `performAction` or resolve a ref to a real touch. Text modes: `auto` (prefer tapping keyboard keys via a11y labels when possible), `keyboard`, `keyevent`, `setText`.

## Browser / CDP

Control is raw CDP against `@chrome_devtools_remote` (and WebViews that publish DevTools), not ChromeDriver.

- **Android 11+:** the app opens Chrome’s socket through adbd (`CdpForward` / Kadb). Wireless Debugging must be available. The helper is not on this byte path.
- **Android 10:** USB forward/reverse tunnel (`fingertips_cdp`); the helper connects to the reverse socket and relays bytes. `fingertips helper` sets the tunnel up.

The browser code reaches targets through `DevtoolsEndpoints`, so it does not care how. On Android, `SocketEndpoints` opens a socket and a WebSocket for each session, to one target. A desktop browser has one connection, a pipe or a WebSocket, and `ConnectionEndpoints` attaches a flattened session per target over it (`Target.attachToTarget` with `flatten`). `CdpRouter` routes a new session from the moment it reads the attach's reply, so a detach right behind it still stops the session, and it detaches a session whose caller was cancelled before the reply came. A browser call gets a browser session of its own either way, so the domains it turns on end with it.

The app owns the CDP session policy (compact AX snapshots, humanized taps through the helper, isolated-world evaluate, avoid enabling Debugger/Emulation by default). Browser tap, swipe, gesture, key, and text default to `auto`: for a caller holding the screen lease that brings the tab forward and injects a hardware event, and otherwise taps, keys, and text go over DevTools so they do not take the screen. `input=cdp` sends `Input.dispatchTouchEvent`, `Input.dispatchKeyEvent`, or `Input.insertText` to that target without bringing it forward. Chrome holds touch for any tab it is not showing on screen and plays it once the tab shows, so a cdp touch is refused unless the tab is showing; keys and text reach a background tab. `input=cdp-mouse` taps with `Input.dispatchMouseEvent`, which Chrome delivers to a background tab even while hidden, once `pressPastHold` has got the press past Chrome's hold on a page it has not drawn. `scripts/chrome-input-check` checks this behavior against the attached phone. A CSS selector is looked up by node id, since `DOM.querySelector` takes nothing else, and Chrome drops those ids when a page commits and at `DOMContentLoaded`. So a tap, focus, or `browser.element` wait runs its lookup through `withNodeIds`, which starts it over when `DOM.documentUpdated` comes during it. Nothing keeps a node id from one lookup to the next. A `browser.element` wait repeats its lookup every 250 ms rather than waiting for an event, since Chrome reports a change only under parents the session has read, and hearing of every node would mean reading the whole document, which slows the page's own changes. Reading runs `page-read.js` after Mozilla's Readability in the isolated world. Readability picks some part of any page, so article scope first asks whether the page reads as an article, as Readability's `isProbablyReaderable` does: enough paragraphs of prose outside lists and parts named as comments, sidebars, and the like. A listing of search results does not, and is read whole. The script only walks the DOM into blocks (headings, paragraphs, list items, table rows, preformatted text) with link and style runs, and reports page facts such as the HTTP status, published date, signs of a captcha, and signs of content still to load (`page-pending.js`, shared with browser snapshots). A tab Chrome is not drawing can be missing content that loads when drawn, so reads and snapshots say whether the tab was shown, and `show` brings it on screen first and waits for its text to stop growing. Chrome on Android shows a PDF in a native viewer, or saves it, so the tab's page holds no text. Before reading, Kotlin checks the main frame's URL, and for Chrome's viewer (`chrome-native://pdf/link?url=…`) `PdfReader` takes the tab over: with the Fetch domain it pauses the tab's navigation to the PDF's URL and answers it with an empty page of its own, so the navigation never reaches the network and Chrome never decides to show or save a PDF. That page has the PDF's origin, so `pdf-read.js` in it fetches the file as the site's own page would, with the site's cookies and `cache: "force-cache"`: the copy that opening the PDF left in Chrome's cache, fresh or not, so most reads make no request, and one sent `no-store` costs one same-origin request. The bytes stay in Chrome; the app never holds them. The page has no site script, so pdf.js and `pdf-read.js` run in its main world and stay there as `fingertipsPdf`, with the parsed file, until the tab navigates or closes, and later reads and finds reuse it. `PdfReader` runs script there only in a page it made, which it knows by the DevTools loader id of the load it answered; a page at the PDF's URL with any other loader is taken over again first. It runs that script through a handle on the page's document (`DOM.resolveNode`, then `Runtime.callFunctionOn`), so none of it can land in a document loaded in its place. The takeover answers every navigation of the tab's main frame that pauses before `Page.navigate` returns, which Chrome does only once the navigation has its response, so the wait for the pause ends with the navigation. `Page.navigate` returns before the commit, and until then `DOM.getDocument` names the old document, so the takeover then waits for `Page.frameNavigated` with that navigation's loader id, up to 5 s, before it takes the handle. It names the document by its backend node id, since Chrome drops a `nodeId` at `DOMContentLoaded`. A navigation that never pauses, as when a service worker answers the URL, fails with `pdf_failed`. A read of a tab knows the stand-in page by that loader id too, and reads the PDF there; nothing in a page decides it, so no site's page can make a read take its tab over. Chrome refuses a page with no network address of its own a fetch from an `http` URL at a local or LAN address, so such a PDF is not read. A Chrome that saves PDFs rather than showing them closes a new tab once the save starts. A `url` read whose tab goes that way while it loads opens a blank tab and takes it over, which reads the saved copy from the cache, and says so in the `# opened` line. `PdfReader` keeps the last 256 such URLs in memory, so a later `url` read of one goes straight to a blank tab and Chrome does not save it again. If the URL turns out not to be a PDF, the read reports the original error. `pdf-read.js` fetches and parses the file in the background and reads pages a couple of seconds per call, and `PdfReader` waits on the fetch a couple of seconds per call too, up to a minute, so a long download or a long PDF stays within each DevTools call's time limit. A failure that trying again would only repeat, such as the site's HTTP error or a reply that is not a PDF, is `pdf_failed`, which is not retried. It places text as the page is displayed, so a page with its own rotation reads like any other, and keeps text that runs the way most of the page's does, leaving out margin stamps and figure labels. It drops running headers, footers, and watermarks: text that repeats in the same place on many pages. It joins lines into headings, list items, paragraphs, and code by font size, spacing, and monospace fonts, and returns the same blocks as `page-read.js`. The outline comes from the PDF's bookmarks when most of them name a line on the page they point to, with each bookmark's depth as the heading level. Otherwise headings are text set larger than the body, and short standalone lines that open with a section number counting up from the one before, such as 2.4 then 2.5. pdf.js ships as ES modules and script given to DevTools runs as a classic script, so `PdfReader` drops each module's export list and wraps the rest in a function. Kotlin renders the blocks as markdown or text, so offsets, slicing, and searching are the same for `read` and `find` and are unit tested. Nothing is cached between calls; a CRC of the rendered text is returned as `version` so a caller can tell when offsets from an earlier call no longer apply. Reading data runs `page-data.js` in the same isolated world. It finds the page's JSON blobs and turns object literals in code into JSON text, so Kotlin never parses JavaScript. Only the blobs a call's paths name cross the DevTools socket, up to 2,000,000 characters. Kotlin walks that text in place (`JsonScan`), without a tree of nodes, and keeps only the slice it returns, while hashing the rest for `version`. A find holds one value's rendered text at a time. Responses a page fetches are recorded only on request (`NetworkCapture`): a DevTools session stays attached to the tab with the Network domain on and small buffer limits, and the app keeps only each JSON response's id, URL, and size. A data read fetches a body with `Network.getResponseBody` when its paths want it, within the same budget. Waits that watch requests enable Network with no buffer, since they never read a body. Streaming video/audio is for the human viewer (WebCodecs), not MCP tools. The page closes the video socket while it is hidden and the audio socket while playback is blocked.

### Opening a tab

An open listens on a browser session from before the tab exists, with `Target.setDiscoverTargets` and `Browser.setDownloadBehavior` on (`TabEvents`), and each thing an open can turn out to be has one event that says so:

| State | What says so | Then |
| --- | --- | --- |
| The tab exists, and its id | `Target.createTarget`'s reply. Chrome 91 and 109 answer with the Android tab's number, and there it is the page on `Target.getTargets` that discover did not replay | The tab is the caller's, and off the screen if it should be |
| The page committed | `Target.targetInfoChanged` with a title, for a tab opened on its URL. `Page.frameNavigated` with `Page.navigate`'s loader id, for one that navigates | The open returns the tab |
| Chrome is saving the URL | `Browser.downloadWillBegin` with the tab's id as its `frameId` | A PDF's save is cancelled, and up to 2 s go to the tab closing or Chrome's viewer committing |
| The tab closed | `Target.targetDestroyed` | The URL was saved, so a stand-in reads it |
| The server has not answered | 12 s passing | The open returns `loading` true, and a new browser session keeps listening for up to 2 minutes, so a save that starts later is cancelled too. A `browser.*` wait on the tab connects only once that session has heard the tab commit or close, and until then a call that uses the page fails `content_unavailable` at once. Once that session has ended, for any reason, calls go ahead as for any tab |

A tab opened on its URL gets no connection of its own, since Chrome can hold a new connection's `Page.enable` until the commit, and that connection would race Chrome closing the tab. For the same reason a wait stays off a tab still waiting for its server: its connection would be held past a call's limit, or be shown the first page and take that for loaded. The commit of a tab that navigated, heard after the open returned, is a title other than the one discover replayed, since a connection coming or going repeats the first page's title with the new URL. Only a tab that has to navigate gets one: `capture`, which opens blank so recording starts first, and incognito, which opens on Chrome's new tab page. An error from `Page.navigate` waits 2 s for a save or a close to say what it was, and a load that really failed is then a 400. Chrome saying nothing by a deadline is not read as an answer.

## FingertipsMCP for Chrome

FingertipsMCP for Chrome is the same server and browser service on a computer, with no phone and no helper.

```mermaid
flowchart LR
  Client["MCP client / HTTP client"] -->|"loopback TCP or Unix socket + Bearer, or stdio"| Jar["fingertipsmcp-chrome.jar: Ktor + MCP"]
  Jar -->|"--remote-debugging-pipe (fd 3/4)"| Chrome["Google Chrome"]
```

- **Launch.** `ChromeLocator` finds Google Chrome stable, or Chromium with a warning. `ChromeLauncher` starts it through `/bin/sh`, which turns the child's stdin and stdout into Chrome's fds 3 and 4, since Java gives a child only three descriptors. Messages are NUL-terminated JSON (`PipeTransport`), written from a queue by a coroutine of their own, so a Chrome that stops reading can't hold a caller past its call's timeout. `BrowserSupervisor` starts Chrome again when it has exited, once the old Chrome has stopped and freed its profile. The start runs in the supervisor's own scope, and callers wait for it, so a caller that is cancelled doesn't leave a Chrome nobody closes. Chrome allows one process per profile, so that Chrome would make every later start fail. `--attach` uses a WebSocket to a Chrome with a debugging port instead, for development.
- **Input.** `DesktopPageInput` plans each action with `Humanizer`'s mouse and typing models and plays it through `DesktopEvents`. `CdpDesktopEvents` is `Input.dispatchMouseEvent` and `Input.dispatchKeyEvent`; an OS backend (XTEST or uinput, CGEvent) could implement the same interface, mapping viewport points to the screen. `DesktopPointer`, one per browser, records where the pointer is from the events sent and runs one action at a time; `DesktopPageInput` and the viewer's `ViewerPageInput` both go through it, so a move starts where the last one ended, whoever made it. Inside each action a hidden tab is brought to the front first, and targets are scrolled into view with the wheel. Every input takes the screen lease, since there is one pointer and one keyboard focus.
- **Server.** `ChromeApp` builds `ServerServices` with the browser, `TabScreenshots`, `BrowserWaits` (browser conditions on the wait loop Android uses), `HostDevice`, a screen lease whose cleanup shows the placeholder tab, and tab ownership on the `chrome` endpoint. `Tabs` treats Chrome as always in front, so its rule that only the holder's tab shows holds here too. MCP refuses a non-loopback `Host` and any `Origin`, since a loopback port is reachable from web pages by name. `--stdio` serves one local client as one session, with no token. Tokens come from `fingertipsmcp-chrome.jar token create`, which also writes `pairing.json`.
- **Viewer.** `ChromeViewer` serves the viewer's video, audio, and input. When a viewer connects it loads a small extension (`chrome/src/main/resources/.../viewer`) and runs its action on the tab Chrome shows, found by its tab target through `Target.setAutoAttach`. The extension's offscreen document captures that tab with `tabCapture`, encodes it with WebCodecs, and hands frames back through a `Runtime.addBinding` binding on the pipe, in the layout the viewer's sockets carry. `ViewerQueue` keeps each viewer's frames, skipping to a key frame for one that falls behind. Input from the page goes through `ViewerControls`: contacts, keys, text, paste, and copy, which every product has. The phone's `PhoneViewerControls` is also `PhoneControls`, with screen wake, orientation, and image paste, and Chrome's is `MouseControls`, with hover and the wheel, through `ViewerPageInput`'s mouse and keyboard on the captured tab. The socket refuses an operation the product's controls lack. The viewer page is in `server/`'s resources, and the video header's `pointer` tells it which to drive.
- **Detection.** Any remote-debugging switch makes Chrome report `navigator.webdriver` true, so FingertipsMCP for Chrome passes `--disable-blink-features=AutomationControlled` (`--show-automation` opts out). Chrome puts a warning bar for that flag on the tab it opens at launch, so a windowed Chrome starts with `--no-startup-window` and `ChromeLauncher` opens the first window itself; that also skips session restore. `LastTab` opens a blank tab before the API closes the last one, so Chrome on Linux does not exit. The environment matters most: a real desktop session, then Xorg with a GPU, then Xvfb, then headless.

## Host client

| Command | Job |
| --- | --- |
| `fingertips pair` | Mint token (every scope, or `--scopes`), save pairing file (no forward, no secret on stdout) |
| `fingertips server` | `adb forward` API socket to localhost |
| `fingertips helper` | Start listener + helper (and Android 10 CDP tunnel) |
| `fingertips papi` | REST / WebSocket calls, in a kept session |
| `fingertips session` | Show or end the session `papi` keeps |
| `fingertips-dev setup` | Debug build/install/grant/pair/server/helper for developers |

Production defaults to package `com.fingertipsmcp` and `~/.config/fingertips/`. Dev defaults to `com.fingertipsmcp.dev` and `.dev/pairing.json`.

## Agent loop

The intended loop for an LLM client:

1. `ui_snapshot` / `ui_find` (tree)
2. `tap` / `ui_act` / `type_text` / `press_key`
3. `wait_for` (window / node / browser conditions)
4. `browser_read` / `browser_find` to read a page as markdown or query its JSON, and `browser_*` input when the work is in Chrome
5. `screenshot` only when the tree is not enough

MCP exposes that curated set. Token admin and media streams stay off the tool catalog, and there is no raw CDP socket: every browser action goes through a typed route or tool, so tab ownership and the isolated world always apply.

## Caveats

This stack is closer to scrcpy / wireless-debugging tooling than to a normal Play app. Expect breakage on OEM skins and new Android releases.

### Shell helper

Running a long-lived daemon as the `shell` user via `app_process` + `setsid` is non-standard. It is not an app component Android manages for you. Package updates, OEM process killers, and “USB debugging revoked” all take it down; Wireless Debugging auto-restart only covers some of that on Android 11+.

The helper’s Binder registration uses a protected ContentProvider and reflection through `ActivityManager.getService` / `IContentProvider` because a plain `ContentResolver.call` from `app_process` is rejected (no registered app identity).

### Hidden and privileged APIs

The helper calls into framework internals that apps are not supposed to use. On load it tries `VMRuntime.setHiddenApiExemptions("L")` so the targetSdk blacklist does not block those lookups. Anything in this list can move or vanish:

| Area | What we call |
| --- | --- |
| UiAutomation | `UiAutomationConnection`, hidden `UiAutomation.connect` / `disconnect` |
| Input | `ServiceManager.getService("input")`, `IInputManager.injectInputEvent` (arity varies by release) |
| Display mirror | Hidden `DisplayManager` mirror APIs; fallback `SurfaceControl.createDisplay` / `openTransaction` / `setDisplaySurface` / `destroyDisplay`, plus `DisplayManagerGlobal` |
| Audio | `REMOTE_SUBMIX` capture (`CAPTURE_AUDIO_OUTPUT`, shell-only) |
| Privileges | `WRITE_SECURE_SETTINGS`, which the app grants itself through the helper, so the app can re-enable Wireless Debugging after reboot; `DUMP` for `CREATE_TOKEN` |

Video encoding also uses `MediaFormat.KEY_LOW_LATENCY` where the codec claims support; that key is not equally real on every device.

The mirror only draws when the screen changes, so a still screen can send nothing at all. The mirror therefore draws into a `SurfaceTexture`, and a GLES relay copies each frame onto the encoder's input surface. The relay redraws the last frame when a viewer joins, so the requested key frame has input. It also redraws 100, 300, and 700 ms after the screen stops changing, so the encoder can sharpen what it blurred during motion, and then every 4 s, so a viewer can tell a still screen from a dead stream. The encoder's own `KEY_REPEAT_PREVIOUS_FRAME_AFTER` isn't used: some encoders ignore it, and the ones that honor it repeat frames ten times a second on a still screen, which costs a message each over a relay.

Each viewer has a short frame queue. When it is full, the new frame is dropped and that viewer skips delta frames until a key frame. The stream asks for one at once, and again while any viewer still waits, since a viewer still behind can drop the key frame too. The gap between requests doubles from 500 ms to 8 s, because every key frame goes to every viewer. So a slow viewer sees the picture pause rather than smear. `KEY_I_FRAME_INTERVAL` is 10 s, only a backstop. Encoders count it in frames at the configured rate, so on a screen that changes less often it is longer.

Audio packets are dropped while remote submix delivers silence, apart from 60 ms after each sound, which covers the encoder's lookahead and lets the decoder fade out. The viewer plays packets as they arrive, so a gap needs nothing on its side. Android's Opus encoder makes one 20 ms frame per packet and has no setting for longer ones, so the app joins three frames into one Opus packet (RFC 6716 code 3) and sends a 60 ms packet, a third as many messages, for 40 ms more delay. Frames join only while they share a configuration and follow on in time, and what is held goes out when silence starts. A decoder takes the joined packet like any other, so the viewer is unchanged; AAC packets go out one at a time.

Until the mirror draws once, the relay has nothing to redraw. Samsung's Android 10 SurfaceFlinger can leave a new `SurfaceControl` mirror undrawn for seconds on a still screen, so 250 ms after creating one the helper projects it two pixels short and back. That makes SurfaceFlinger draw it. The `DisplayManager` mirror isn't nudged.

### Other odd corners

- **Custom Ktor engine.** CIO only binds filesystem Unix sockets. `AbstractHttpEngine` accepts Android abstract `LocalServerSocket` connections and feeds them into CIO’s pipeline, including a reflective call into an internal `CIOApplicationCall` release method (`@InternalAPI`). When CIO closes a connection's output, the engine shuts down the socket's output too, as a TCP socket would, since Ktor's channel over an `OutputStream` only marks itself closed. Without that, a closed WebSocket stays connected: CIO reads until the client hangs up, and the client waits for the server to close first. Closing a listening socket does not wake a thread in `accept()`, and that blocked call keeps the abstract name bound. So stopping the listener shuts the socket down to end the call, waits for the accept thread to leave, and then closes the socket. `ServerController` runs each stop and start in turn, off the main thread, so a start never races the stop that is freeing the name.
- **ADB is the trust boundary.** Anyone with a debugging session can mint tokens and drive the device. That is intentional; it is also why there is no LAN API anymore.
- **Chrome DevTools path is split and fragile.** Android 11+ needs Wireless Debugging (and often leaves it on). Android 10 needs a USB forward/reverse dance. Emulators sometimes need `adb root` before Chrome accepts the shell uid. Chrome itself must be publishing `@chrome_devtools_remote`.
- **Node refs are not forever.** Stable ids need Android 13+ (`ui.stableIds`); older builds synthesize refs that can churn across snapshots.
- **Humanization is best-effort.** Injected touches aim to look like a real touchscreen; apps and Play integrity can still treat “USB debugging + shell helper” as a hostile environment.
- **`FLAG_SECURE` and OEM policy.** Secure windows are black in captures; some devices further restrict shell capture, submix audio, or display mirroring.
- **Not a Store-shaped app.** Foreground `specialUse` listener, shell helper, hidden APIs, and adb provisioning are fine for sideload / enterprise; they are not a normal Play distribution story.
- **Unlock / PIN.** Stored PIN unlock taps the system keyguard UI. Layout changes, biometrics-only policies, or pattern/password locks will fail closed (`needs_user` / no keypad). A cancelled entry leaves digits in the PIN field, and the next PIN would land after them and count as wrong. So the field's length is read first, leftovers go through the delete key, and when the field can't be read or emptied nothing is typed.

## Related docs

- What FingertipsMCP is for: [README](../README.md)
- Installing a release build, pairing, and the helper: [setup.md](setup.md)
- HTTP, WebSocket, MCP, and OpenAPI: [api.md](api.md)
- Building, `fingertips-dev`, tests and linters: [development.md](development.md)
- Sharing the phone between clients, sessions, and the screen lease: [sharing.md](sharing.md)
- What Android and Chrome were measured to do, and what is still to check on a phone: [device-notes.md](device-notes.md)
