# Device notes

This is what Android, Chrome, and the MCP SDK were seen to do, which PhoneAPI's design depends on, and what is still to check on a phone. [sharing.md](sharing.md) describes the behavior built on it. Each check gives its date. Chrome and Android change between releases, so a check is worth running again after either updates. `scripts/chrome-input-check` checks Chrome's input behavior against an attached phone, as [development.md](development.md) describes.

The emulators run Android 16 and Android 10 with Chrome 133. The emulator's Chrome is a debug app, and the helper often runs there as root after `adb root`. The test phone runs Android 11 with Chrome 155.0.8059.84.

## Chrome tabs in the background

### Selecting tabs and popups

- Chrome on Android ignores `Target.createTarget`'s background flag and switches to a new tab while Chrome is in front. That is why `tookScreen` exists.
- On the emulator, `Target.activateTarget` and `Page.bringToFront` brought Chrome forward from the background, even 15 seconds after HOME. On the phone, on 2026-10-21, they only changed which tab was selected, and the launcher stayed in front. The emulator's debug Chrome under `adb root` may explain the difference. PhoneAPI never sends either for a session without the screen.
- A popup from a tab not on screen becomes the tab Chrome shows while Chrome is in front, with no change of foreground app, on the emulator and on the phone.
- `/json/list` does not report `openerId`. On the emulator `Target.getTargets` reported it for `window.open` and `target=_blank` popups, with the opener on screen, behind another tab, and with Chrome in the background. The phone reported it too.
- Page commands work on each page's own DevTools endpoint. On the phone, attaching through the browser connection got no answers.
- On the Android 16 emulator, a tab opened while Chrome is in the background becomes Chrome's selected tab. Chrome selects it as it comes forward, even over a `Page.bringToFront` sent just before, which is why `show` checks that its tab is still showing and tries again. Opening the tab did not bring Chrome forward.

### Launching apps from a tab

On the emulator, tabs that were not on screen launched no app in 18 tries: an `intent:` link, an app link, and a custom scheme, each by mouse click, by script, and by script with a user gesture, from a tab behind another and with Chrome in the background. App links opened inside the tab instead. From the tab on screen, the same clicks launched YouTube.

On the phone, on 2026-10-21, tabs not on screen launched no app in 8 tries with `tel:` and `intent:` links, by click and by script with a user gesture. From the tab on screen, both opened the dialer.

### Throttling and pausing

Measured on the Android 16 emulator on 2026-10-21. With Chrome in front and the tab behind another, its timers ran about once a second for the first minute, about once a minute after that, and stopped at about 5 minutes. A paused tab still answers DevTools: script evaluation, reading, mouse input, and navigation all work. Only the page's own JavaScript is paused, so content it would load or poll for does not arrive. `Page.setWebLifecycleState` with `active` answered but did not restart its timers. Navigating or reloading gives a fresh page that runs until it pauses again.

More on the emulator on 2026-10-22, with Chrome in the background and PhoneAPI's helper holding Chrome's binding:

- The 5 minutes count from when the tab was hidden, not created. A tab kept on screen for 3 minutes paused about 5 minutes after it was hidden. Tabs hidden from the start paused at 5 minutes. Reading a tab over DevTools once a minute did not delay it.
- `Page.setWebLifecycleState` with `frozen` and then `active` thaws a paused tab without bringing it forward. A fetch the page started then completed, where one on a paused tab never did. Its timers ran about once a minute, as for any tab hidden that long. It had not paused again 9 minutes later.
- With Chrome started with `--disable-background-timer-throttling --disable-renderer-backgrounding`, tabs still paused at 5 minutes, but a thawed tab then ran its timers once a second.
- A tab reloaded while hidden ran its timers once a second for a minute, then about once a minute. It paused again about 5 minutes after the reload, so a reload restarts the clock.
- A paused tab still evaluates DevTools script but runs none of its own tasks, so a message it posts to itself never arrives. That tells a paused tab from a running one: about 10 ms on a running tab, and a 200 ms timeout on a paused one. A page busy running script answers no script at all, which tells it apart.

On the phone, on 2026-10-22, with the phone locked and Chrome in the background, a hidden tab's timers ran once a second for about a minute, then stopped entirely, where the emulator kept them at about once a minute. The page itself paused between 7 and 9.5 minutes, later than the emulator's 5. Between the two, input and reads work but the page's timers never fire, so a page that reacts through them, such as a debounced search box, never does. That is why PhoneAPI checks timers as well as pauses.

The tab Chrome had selected kept running at full rate and reported `visible` with Chrome in the background and the screen off, so only tabs behind another one in Chrome go hidden.

### The timer check

Chrome runs a hidden tab's timers at most about once a second even when it has not stopped them, so the check allows 1.1 seconds, too long to wait before every input. It starts alongside each `cdp` or `cdp-mouse` input, in the background, on its own DevTools connection, and the next call on that tab reads the result.

The check's timer has a 1 ms delay. Chrome runs a zero-delay timer at once on a queue it never throttles, which on the emulator fired at once in a tab whose other timers had slowed to once a minute. On the phone, both stopped along with the page's own. Both checks run in an isolated world, so the page can neither see them nor fool them by replacing `MessageChannel` or `setTimeout`. Chrome stops that world's timers along with the page's, since it throttles per frame.

Checked on the emulators on 2026-10-22: a tab hidden for a minute ran a single 1 ms timer within a second, while its `setInterval` ran once a minute, so the timer check let input through. With the check's timer swapped for a chain of nested timers, which Chrome had slowed to once a minute, a background key was let through, a read noted `timers stopped`, the next key was refused with `tab_frozen` after the 1.1 s confirmation, a wait on the tab failed the same way, and a tab opened a moment before was left alone. Bringing the tab forward with `show` cleared it.

Checked on the phone on 2026-10-22: a hidden tab's timers ran once a second until about a minute after it was hidden, then all stopped at once: its `setInterval`, and a 0 ms and a 1 ms timer in an isolated world, none of which fired within 5 seconds. So on Chrome 155 the 1 ms check sees what the page sees. About 2.5 minutes after hiding, the pause check, by then in an isolated world, refused the first background key with `tab_frozen`, much sooner than v0.3.0's main-world check caught a pause (7 to 9.5 minutes). A `wait_for` on the tab ran out its 8-second timeout, since that refused key had started no timer check; waits now start their own.

### Unloaded tabs

Opening heavy pages one after another on the 2 GB emulator, nothing was unloaded through 15 tabs, ten of them real sites such as YouTube, CNN, Amazon, Maps, and the New York Times, with free memory down to about 220 MB. From 17 tabs, with 250 MB pages added, Chrome unloaded the oldest tabs first, six by 19 tabs. That is where the cap of 8 tabs comes from: it leaves room for the tab on screen, unowned tabs, and other apps on a 4 GB phone.

An unloaded tab stays in the tab list. `Page.reload` and `Page.navigate` bring it back as a fresh load and leave it hidden. Script evaluation and `Page.getFrameTree` never answer, since its renderer is gone. That matches the long-hidden tabs on the test phone. Selecting it also reloads it, but puts it on screen. `document.wasDiscarded` stayed false, so a changed `performance.timeOrigin` is what shows the reload.

PhoneAPI tells an unloaded tab apart with `Inspector.enable` on the page's own connection. Chrome answers it from the browser process and, for a tab with no renderer, sends `Inspector.targetCrashed` first. A script probe with a timeout cannot tell an unloaded tab from a page whose main thread is busy: on the emulator, a page in an 8-second loop answered neither script nor, on a fresh connection, `Runtime.getIsolateId`, and the first build reloaded it. With `Inspector.enable`, that read waited for the loop and kept the page. When `Inspector.enable` gets no answer in 2 seconds, PhoneAPI checks Chrome's browser endpoint on a socket that gives up reading after 2 seconds. A read of an unloaded tab returned the reloaded page in about a second on the emulator, with Chrome's renderers killed to stand in for memory pressure. When Android has frozen Chrome, the page connection's handshake waits out the 15-second read timeout before the browser check, so `browser_frozen` takes about 17 seconds rather than four 15-second retries. That was checked with `am freeze --sticky com.android.chrome`.

### Closed tabs

Chrome refuses a DevTools connection to a closed tab with 500 "No such target id", in Chrome 133 on the emulator and Chrome 155 on the phone, and 404 in some releases. PhoneAPI reports it as `not_found` rather than retrying.

## Android's app freezer

On Android 12 and later the cached-app freezer is on by default. On the Android 16 emulator on 2026-10-21, about 2 minutes after Chrome went to the background, every Chrome process was frozen (`isFrozen=true`), and nothing answered, not even the browser endpoint. Android 11 has the freezer but leaves it off by default. On the phone on 2026-10-22, `dumpsys activity` printed "Freezer settings" with `use_freezer=false`, and `cached_apps_freezer` was unset.

The freezer's effective state is `use_freezer` under "Freezer settings" in `adb shell dumpsys activity`. `dumpsys activity settings` leaves that section out, and the whole dump is hundreds of kilobytes, so the helper reads it through `grep -m 1`. The setting behind it is `cached_apps_freezer` (`settings get global cached_apps_freezer`), unset by default. On the Android 16 emulator, changing the setting changed `use_freezer` at once, without the reboot the developer option asks for. With it `disabled`, Chrome was never frozen in 150 seconds in the background. Set back to the default, Chrome was frozen within 90 seconds. Turning it off does not thaw an app that is already frozen; that waits until the app comes to the front or the phone restarts.

Holding Chrome's Custom Tabs binding was checked on the Android 16 emulator, with the freezer at its default, on 2026-10-21, using a throwaway build:

- With the binding held, Chrome's browser process stayed at importance 200, the same as PhoneAPI's service, and Android froze none of Chrome's live processes in 10 minutes in the background. A hidden tab answered DevTools throughout. Its timers still stopped at about 5 minutes, which is Chrome's own pausing and leaves reads and input working.
- The renderers of hidden tabs sat at importance 900 to 910, the cached range, yet were not frozen. Android's low-memory killer can still take them, which is unloading as above.
- Without the binding, Chrome dropped to the cached range within a minute, and every Chrome process was frozen by 2 minutes.
- Chrome's Custom Tabs service listed PhoneAPI as its client from a foreground-service process.

On the phone on 2026-10-22, PhoneAPI held the binding (`chromeKept`).

## Incognito tabs

DevTools cannot open an incognito tab. Chrome on Android fails `Target.createBrowserContext`, and opens a normal tab when `Target.createTarget` names the incognito tabs' `browserContextId`. So PhoneAPI starts Chrome's "New Incognito tab" shortcut instead, as [sharing.md](sharing.md#incognito-tabs) describes.

DevTools can clear some incognito data without closing tabs, but not all of it. `Storage.clearCookies` failed for the incognito `browserContextId` ("Failed to find browser context"), as `createTarget` ignores it. `Network.clearBrowserCookies` from an incognito tab cleared the incognito cookies and left the normal ones. Local storage stayed until `Storage.clearDataForOrigin` cleared it, one origin at a time, and DevTools has no list of the origins that hold data. So closing the tabs is the only full reset.

Checked on the Android 16 emulator with Chrome 133 on 2026-10-31, with the helper as the shell user:

- Over raw DevTools, the shell user can start the shortcut, each start opens a new incognito tab listed with its own `browserContextId`, and `screencap` of it is black.
- Through `POST /v1/browser/tabs`, `/v1/stream/video` and `/v1/screenshot` showed an incognito tab black and a normal tab in its place drawn. An incognito open took 1.5 s the first time, with the blank tab to learn the normal context, and 0.8 s after.
- A cookie set in a normal tab was not in the incognito tab. `read`, `snapshot`, `evaluate`, and a `cdp-mouse` tap on a link worked on it.
- Ending the session closed both incognito tabs within 0.5 s in two runs. In an earlier run, the second incognito tab was still listed 2 s after the session ended; that was not seen again.
- A session without the screen got it back free after the open, with Chrome sent home and the tab still readable. One that held the screen kept it.
- A cookie set in one session's incognito tab showed in another session's, and in a third opened after the first closed its tab. Once every incognito tab was closed, a new one had no cookie.
- With an incognito tab opened outside PhoneAPI holding a cookie, an incognito open closed it, and the new tab, opened right after, was in a new browser context with no cookies. A second session's open kept the first session's tab, saw its cookie, and got the note.
- The Incognito Screenshot flag was in `chrome://flags`. It stayed on after Relaunch; Chrome writes flags as it shuts down, so a force stop loses the change. With it on, the video and `/v1/screenshot` showed an incognito tab's page and Chrome's incognito icon.

## Starting Chrome

Chrome opens `chrome_devtools_remote` only while it runs. A tab open with Chrome stopped starts it from its launcher entry, as tapping its icon would, and waits for the socket, as [sharing.md](sharing.md) describes.

Checked on the Android 16 emulator with Chrome 133 on 2026-10-31, after `am force-stop com.android.chrome`:

- The socket appeared and `POST /v1/browser/tabs` returned in 2.1 to 3.2 s over four runs, and MCP `browser_open` in 2.0 s. Chrome restored its earlier tabs as it started.
- A session without the screen got it for the open and gave it back. Cleanup sent Chrome home about 5.5 s after the open returned, the same as after an incognito open. One that held the screen kept it.

## The top task

PhoneAPI asks for the top task before and after calls that could launch an app. The first build ran `dumpsys activity activities`. It took about 8 ms on the emulator but 90 to 140 ms on the phone, where any `dumpsys` costs that much to start, adding about a quarter second to each background input. The helper now calls `IActivityTaskManager.getTasks` directly, choosing the overload as it does for input injection, and falls back to `dumpsys` if that call fails. The same call answers the "is Chrome in front" checks behind the placeholder tab.

`getTasks` takes `(int, boolean, boolean, int)` on Android 16, and `(int)` on Android 10 and 11. Checked on both emulators and the phone on 2026-10-22, where `show` brought Chrome forward with it.

## Clipboard

Android 16 reports one clipboard write as two clipboard changes, a second apart, so a change count does not identify a clip. PhoneAPI notes the timestamp Android gives each clip it writes instead. As root, the emulator refuses the helper any clipboard access.

On the Android 16 emulator on 2026-10-29, with the helper as the shell user, cleanup cleared PhoneAPI's own clip, left a clip copied after it, and left the clip after `cleanup` false. On 2026-10-31, text a person pasted in the viewer was still on the clipboard after the viewer let go, and the next session read it as empty. A session read its own write back, found the clipboard empty after a person took over and handed the screen back, and a session that got the screen after a release with `cleanup` false found it empty too.

## Screen hand-offs

Checked on the Android 16 emulator:

- 2026-10-29: a REST session brought a page on screen, released with `cleanup` false, and ended. Chrome stayed in front and unlocked, and the tab stayed open and unowned. A viewer took the screen, which woke it when it was off, and typed into the page. When its socket closed, nothing was cleaned up. The next session found the tab in `browser_targets`, read what the viewer typed, and got the note that a person had used the screen.
- 2026-10-30, with a swipe keyguard: a session that unlocked and released to a waiter left it unlocked, with a note saying so. The waiter's release with no one waiting locked it. A phone unlocked by hand stayed unlocked after a session took and released it.
- 2026-10-30: a session's wait for `person` true matched within 0.2 s of a viewer's take, and the wait for `person` false matched when the viewer's socket closed. Neither took the screen. A session that held the screen kept holding it through both waits and got it back when the viewer let go.
- 2026-10-22: a tab Chrome opened for `open_intent` became the caller's, and another session did not see it.

## Video and audio streams

Measured on the Android 16 emulator on 2026-11-05, with the `C2SoftAvcEnc` encoder:

- On a still home screen a viewer got the key frame it joined with, redraws about 100, 300, and 700 ms after the last mirrored frame, then one frame every 4 s. A second viewer joining got a key frame within 10 ms, and so did the first. The idle frames were deltas of 1 to 30 KB, so the encoder kept refining the still picture.
- The encoder counts `KEY_I_FRAME_INTERVAL` in frames at the configured `KEY_FRAME_RATE`, not in seconds. With 10 s and `fps=60`, a page animating at about 29 fps got timer key frames every 20 s.
- adb buffered several MB per stream between the app and the client. A viewer that stopped reading, or read at 12 fps beside a 30 fps stream, never filled PhoneAPI's 4-frame queue. Ktor's ping timeout closed it after about 45 to 50 s instead. So the queue's drop and key-frame recovery did not run on the emulator. Its unit tests cover it.
- Audio capture fails before the stream starts: the helper's `AudioRecord.Builder().build()` throws a `NullPointerException` from `Context.getAttributionSource()`, since `app_process` has no Context. That also happens without these stream changes. The Android 10 emulator has no submix capture, so no emulator checks audio.

## UiAutomation

On the phone on 2026-10-22, "UiAutomationService already registered" came from two calls connecting UiAutomation at once in one helper. Connecting is now serialized, and two `ui_snapshot` calls at once then connected once. The first snapshot right after connecting saw 0 nodes and 0 windows; the next, 9 seconds later, saw 30 nodes.

## MCP SDK

PhoneAPI uses the Kotlin MCP SDK 0.15.0. A prototype on the Android 16 emulator showed, on 2026-10-20:

- `initialize` assigns a random session id. `notifications/cancelled` cancels the tool's coroutine at once. `DELETE` cancels every call in flight, and the session then answers 404.
- The id a tool handler sees, `ClientConnection.sessionId`, is the SDK's own, not the `Mcp-Session-Id` the client holds. PhoneAPI creates each session's transport and `Server` together, with `setSessionIdGenerator` returning an id it picked, so that id is in the tools' closure. Leases and tabs are keyed by it.
- A call cut off by `DELETE` comes back as HTTP 500 `Job was cancelled`, from PhoneAPI's error mapping. It should be a JSON-RPC error.
- The SDK's streaming mode answers a POST with an event stream that Ktor closes as soon as the transport hands the request off, before the result, so it does not work without more plumbing. In JSON mode the SDK sends a request's progress on the client's GET stream, and drops it when the client has none. So `/mcp` answers POSTs with JSON, refuses the GET stream with 405, and sends no progress.
- A tool ran to the end after its client dropped the connection, on stateless `/mcp` as well as with sessions. That matches the spec, which says not to treat a disconnect as a cancellation, so clients have to send `notifications/cancelled` to stop a wait. Legwork's Go SDK (v1.8.0) does that when a call's context ends, sends `DELETE` when the session closes, and opens the GET stream after `initialize`.
- Nothing from the SDK, session ids included, reached logcat in a debug build.

The SDK has a stateful transport with session ids, `DELETE`, and open and close callbacks, but no idle timeout, and its Ktor helper keeps the session table private. So PhoneAPI owns the `/mcp` route and drives `StreamableHttpServerTransport` itself, keeping expiry, the token check, and release in one place. The SDK makes ids with Kotlin's `Uuid.random()`, which uses `SecureRandom` on the JVM, so PhoneAPI keeps its generator.

Progress would need one of two things. The session's GET stream already carries it in JSON mode, but clients may skip that stream. Streaming POST responses, which every client must accept, would need PhoneAPI's own transport on the SDK's `Transport` interface, about 250 to 350 lines, owning session ids, headers, and errors. Wrapping the SDK's transport instead is smaller but depends on its internals.

MCP's stateless revision (2026-07-28) drops protocol sessions: servers that need state across calls mint handles that clients pass as tool arguments, as `session_start` does. SDK 0.15.0 does not speak that revision yet, so such clients cannot connect, but the tools would carry over.

## Still to check on the phone

- That Chrome on the phone opens an incognito tab for `OPEN_PRIVATE_TAB` from the shell user and lists it with its own `browserContextId`, and whether Chrome's "Lock Incognito tabs when you leave Chrome" setting blocks a tab after cleanup sends Chrome home. Also that the viewer shows an incognito tab black there, as on the emulator, and that the phone's Chrome has the Incognito Screenshot flag and it makes the viewer show the tab.
- Whether a tab opened while Chrome is in the background becomes Chrome's selected tab, as it does on the Android 16 emulator.
- That the shell user can clear the clipboard through `IClipboard.clearPrimaryClip` and read `getPrimaryClipDescription` on Android 11.
- That a session finds the clipboard empty on Android 11 after a person copied something, and that the clear leaves no toast or visible change.
- That clipboard text and images go both ways on Android 11, checked so far on the Android 10 and 16 emulators. Also that the helper can read an image another app copied, which the emulators' Chrome would not put on the clipboard.
- That the viewer's copy and paste work from a real keyboard in Chrome, Safari, and Firefox, including the second Cmd+C that Safari and Firefox need when the page guessed text and the phone held an image.
- How many hidden tabs Chrome keeps loaded on the Moto G Power before it unloads them, to check the cap of 8.
- Whether a release build logs anything from the SDK, such as session ids. A release build uses a different logging backend from the debug build checked above.
- That binding Chrome's Custom Tabs service works on a real Android 12 or later phone, and does not change anything visible on the Android 11 test phone.
- That Chrome 155 still sends `Inspector.targetCrashed` on `Inspector.enable` for a tab it unloaded.
- Whether the first `ui_snapshot` after UiAutomation connects can come back empty, as one did, and should wait for windows.
- Whether a hidden tab's timers also stop while Chrome itself is in front, with the tab behind another, or only while Chrome is in the background.
- That `show` brings a background tab on screen, including when Chrome is behind another app, and that `shown` and `pending` match what is on screen.
- Whether Android 11 applies a change to `cached_apps_freezer` as quickly as Android 16 does.
- That remote submix delivers zeros, or samples within 8 of zero, while nothing plays on Android 11, so the audio socket goes quiet, and that a sound comes through whole, without its start or end clipped. That checks the Opus encoder's output timestamps against its input.
- That a still screen on the Moto G Power looks sharp after scrolling, with the relay's settle redraws in place of `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, and sends a frame every 4 s.
- That a viewer on a slow link pauses rather than smears, and catches up on a key frame. adb's buffering kept the emulator from reaching that path.
- How long Chrome takes on the Moto G Power to open its DevTools socket after a tab open starts it, against the 10 s limit, and whether a first-run or sign-in screen keeps it from opening one.
- That a card number in a toast or a window title reaches `WS /v1/events` as `[card number]`, and the rest of the text is unchanged.
- That a tab Chrome unloaded still reloads for a read when `readyState` is read in an isolated world right after `Page.reload`, before the new document exists.

## Checked on the phone

On 2026-10-22, with v0.3.0 and then a dev build beside it, the phone's agent and adb checked:

- Tabs a request opened were gone in the next request, from when REST calls held the screen for one call.
- A session's own tab stayed on screen while another session opened a tab, so switching back works on Chrome 155.
- `session_conflict` both ways, `screenHolder` as `you`, `agent`, `parent`, and `subagent`, and the cleanup and waited notes.
- Restarting the dev helper closed both test tabs a live session had, since the startup sweep ran on every helper connection. It now runs once per app start. The release build's helper also restarted during cleanup, when the USB connection dropped, and its app relaunched it within 30 seconds.
