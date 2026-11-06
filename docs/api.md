# PhoneAPI HTTP and MCP API

After `phoneapi server`, the base URL is `http://127.0.0.1:<port>` from the pairing file. The machine-readable contract is **OpenAPI 3.1** at:

```sh
phoneapi papi GET /v1/openapi.json
```

That document is generated from the same Kotlin types as the handlers and MCP tool schemas (`api-model`). Each operation has `x-scopes`, the scopes any one of which lets a token call it, empty when any token may. WebSocket routes are marked `x-websocket`. The `bearer` security scheme is Bearer auth. Prefer OpenAPI (or this page’s summary) over guessing field names.

Install, pairing, and the helper lifecycle are in [setup.md](setup.md). Design background is in [architecture.md](architecture.md).

## Authentication

| Mechanism | Where |
| --- | --- |
| `Authorization: Bearer <token>` | All REST calls and `POST /mcp` |
| Cookie `phoneapi` | Set by `GET /viewer?access_token=`. Accepted only on `GET /viewer`, `GET /v1/device`, and WebSocket upgrades, which is what the viewer page uses |
| `?access_token=<token>` | Only that viewer URL (it redirects and drops the query) and WebSocket upgrades |

Missing or unknown tokens get an **empty HTTP 404** (no JSON body). Authenticated calls that lack a required scope get **403** `forbidden`.

Scopes on a token:

| Scope | Use |
| --- | --- |
| `observe` | Device info, UI tree, screenshot, events, logcat, OpenAPI, waits that watch the UI |
| `control` | Input, IME, wake/unlock/lock, app launch/stop/clear, intents |
| `browser` | Chrome/WebView CDP helpers, `browser.*` wait conditions, and wake/unlock, which browser touch input needs. A wait with only browser conditions needs no other scope |
| `stream` | Viewer page and video/audio WebSockets |
| `admin` | Token CRUD and stored unlock PIN |

`phoneapi pair` grants every scope unless `--scopes` names some, such as `--scopes observe,stream`. It sends ADB `CREATE_TOKEN`, whose `scopes` extra takes the same list. With an admin token, `POST /v1/tokens` creates a token with the scopes it names, and `PATCH /v1/tokens/{id}` replaces a token's scopes. The token keeps its secret, and REST and WebSocket upgrades check the new scopes on the next request. An MCP session's tools are fixed at `initialize`, so a session whose token loses a scope it started with ends within 30 s; one whose token gains a scope sees the new tools when its client next initializes. The app's settings screen lists each token's scopes.

## Conventions

- JSON bodies use `Content-Type: application/json`. Many `POST`s accept `{}` when all fields have defaults.
- Boolean query flags: bare `?name`, `true`/`1`/`yes`, or `false`/`0`/`no`.
- Closed enums (node state, swipe direction, log level, node action names, …) are case-insensitive. An unknown value is **400** `bad_request` on REST and a tool error on MCP.
- `humanize` defaults to **true** on taps, swipes, and gestures (including browser). Set `false` for exact points and a fixed contact.
- `/v1/device.capabilities` (and MCP `device_info`) say what works right now. MCP omits tools the token or device cannot use.

### Sessions

A REST call that acts on the phone or its tabs runs as a session, as an MCP client does, so a script's steps hold the screen and its tabs together. [sharing.md](sharing.md#rest-sessions) has the design.

- `POST /v1/sessions` starts one (any token, whatever its scopes) → 201 `SessionStarted` with its `id`. Send the id in `Mcp-Session-Id` on every call that needs a session. Without it a call fails with **428** `session_required`; with the id of a session that ended or belongs to another token, **404** `not_found`.
- Calls that need a session: wake, unlock, lock, orientation, the UI tree and its actions, screenshots, input, IME, app launch, stop, and clear, intents, waits, and every `/v1/browser/` route.
- Calls that need none: `GET /v1/device`, `GET /v1/apps`, the PIN routes, the OpenAPI document, the events socket, the viewer and its sockets and streams, and session and token admin.
- `DELETE /v1/sessions/current` ends the session named in the header: cleanup if it held the screen, and the tabs it opened close. `POST /v1/screen/release` lets its screen go and keeps its tabs. A session also ends after 20 minutes without a request.
- One id works on both sides. An id from MCP's `initialize` works on REST as the same session, and an `initialize` that carries a REST session's id in `Mcp-Session-Id` gives that session an MCP transport. Ending it from either side ends it for both.
- `phoneapi papi` starts a session when a call first needs one and keeps its id in a `session` file next to the pairing file; it starts a new one, and says so, if the old one ended. `PAPI_SESSION` names a session to use instead, and `phoneapi session id` and `phoneapi session end` show and end the kept one.

### Screen lease

One client at a time holds the screen, as [sharing.md](sharing.md) describes. Calls that act on the screen or show what is on it take the lease: input, IME, apps and intents, wake, unlock, lock, orientation, the UI tree, screenshots, waits with any condition that is not `browser.*` or with a `snapshot`, browser input with `input` `touch` (and `cdp` for pointer input), browser reads and snapshots with `show`, and opening an incognito tab, which gives the screen back after unless the session already had it. Reading a tab, and `cdp` keys, text, and `cdp-mouse` taps, need no lease.

- A session keeps the screen from its first such call until the session ends, it calls `screen_release`, or it makes no screen call for 30 s while someone else waits. Then cleanup runs and the waiter gets the screen; the session keeps its tabs, and its next screen call waits again and gets a note. REST sessions follow the same rules.
- A call that needs the screen while someone else has it waits up to 60 s, then fails with 409 `screen_busy`, saying how long the screen has been held.
- When a session lets go, PhoneAPI hides the keyboard, puts Chrome on a blank placeholder tab, and goes home before the next holder gets the screen. It locks the phone only when no one is waiting and the phone was locked when sessions started using it. A step that failed is reported to the next holder: as a `# note:` line first in an MCP tool result, or an `X-PhoneAPI-Note` header on REST.
- Notes waiting for a session appear first on its next call of any kind: that cleanup ran before it got the screen, that it waited more than 2 s for the screen, that its screen went to another client, or that a person used it.
- While Chrome is in front, it shows the screen holder's tab or the placeholder, never another session's tab. A session without the screen that opens a tab, or whose page opens a popup, gets it behind, with `tookScreen` false.
- A browser input, `browser_eval`, `browser_navigate`, or `browser_open` call from a session without the screen that brings an app forward fails with 409 `took_screen`. If no one held the screen, that session now does; otherwise the app that was in front comes back and the holder gets a note. Android and Chrome block nearly all of these, so this is a tripwire for workflows that rely on it.

### UI snapshot

`GET /v1/ui/snapshot`

| Query | Default | Meaning |
| --- | --- | --- |
| `format` | `compact` | `compact`, `json`, or `both` |
| `window` | — | Window id |
| `maxDepth` | — | Truncate tree depth |
| `maxNodes` | — | Most node lines in the compact outline |
| `maxChars` | — | Most characters of compact outline, cut at a line |
| `textOnly` | `false` | Leave positions, resource ids, states, and actions out of the compact outline |
| `redact` | `true` | Mask password and one-time-code fields and card numbers; `redacted` counts them. Pass `false` for the raw text. REST and MCP both default to masking |
| `includeInvisible` | `false` | |
| `all` | `false` | All windows, not only actionable |
| `autoWake` | `true` | Wake before capture |

Compact text looks like `[e12] button "Sign in"`. When anything is left out, the header line ends with `truncated` and `#` lines after it say what: `# showing 120 of 1840 nodes; raise maxNodes or maxChars for more`, `# nodes below maxDepth 4 left out`, or `# stopped reading at 5000 nodes; pass window or maxDepth to narrow`. `maxNodes` and `maxChars` apply only to the compact outline. Prefer it over screenshots for agents. Node `ref` values (e.g. `e12`) are stable only when `capabilities` includes `ui.stableIds` (Android 13+).

### Wait

`POST /v1/wait` body `WaitRequest`: `all` and/or `any` lists of conditions, `timeoutMs` (default 10000), optional `settleMs`, optional fresh `snapshot` / `snapshotFormat` in the result, cut and masked by `snapshotOptions` (`windowId`, `maxDepth`, `maxNodes`, `maxChars`, `textOnly`, `redact`, as for the snapshot above). MCP `wait_for` takes those as `window`, `maxNodes` (default 400), `maxChars`, `textOnly`, and `redact` (default `true`), and `screenshot` adds a PNG to the result, so one call waits for an action's result and shows it.

Condition `type` values include `node`, `window`, `ime`, `idle`, `screen`, `keyguard`, and `browser.url`, `browser.lifecycle`, `browser.networkIdle`, `browser.element`, `browser.request`, `browser.dialog`, `browser.newTarget`, `browser.log`, `browser.settled`. Browser conditions need the **browser** scope. Other conditions, `snapshot`, and `settleMs` watch the UI and need **observe**. So a token with only browser can wait on its pages, and its result leaves out `state`, which names the app in front. `screen` takes `on`, `person`, or both: `person` true matches while a person holds the screen in the viewer, and false while nobody does. It is the state now, so `person` true does not match once the person has finished; to know a person finished a page, wait for the page to change. A wait whose only non-browser conditions are `screen` with `person` alone does not take the screen, so it can watch a person use it. A page showing a dialog answers DevTools nothing until it is dismissed, and Chrome reports only dialogs that open during the wait, so `browser.dialog` counts one already open by finding it on screen: Chrome's dialog whose title names the page's host.

### Events

`WS /v1/events?types=&logcatTag=&logcatLevel=`

- `types` — comma-separated; exact name or prefix ending in `.` (e.g. `ime.`). Omit for the default set **without** logcat.
- Include `logcat` in `types` to stream log lines (helper required).
- Event names include `ui.changed`, `window.changed`, `ime.shown`, `ime.hidden`, `screen.on`, `screen.off`, `user.present`, `toast`, `logcat`, `helper.status`, and `screen.taken` (an MCP session without the screen brought `package` forward; `session` is its hash). Card numbers in `toast` text and `window.changed` titles show as `[card number]`.

## Errors

Authenticated errors are JSON:

```json
{
  "error": "helper_unavailable",
  "message": "This requires the shell helper, which is not running. …",
  "state": { "screen": "on", "keyguard": "none", "ime": false, "foregroundPackage": "…" }
}
```

| Status | `error` | Typical cause |
| --- | --- | --- |
| 400 | `bad_request` | Bad JSON, enum, or query |
| 403 | `forbidden` | Missing scope |
| 404 | `not_found` | Unknown id, tab, session, or package (authenticated), or nothing handles an intent |
| 404 | `stale_ref` | A UI node ref no longer points at a node; take a new snapshot |
| 404 | *(empty body)* | Missing/invalid Bearer |
| 409 | `needs_user` | Unlock needs a person: no stored PIN, no keypad found, or the PIN was rejected |
| 409 | `no_keyboard` | `mode` `keyboard` and no on-screen keyboard keys were found |
| 409 | `no_editor` | Setting text with no text field focused |
| 409 | `wake_failed` | The screen did not turn on |
| 409 | `lock_failed` | The helper could not lock the screen |
| 409 | `key_failed` | The helper did not deliver the key |
| 409 | `gesture_cancelled` | The system cancelled an injected touch |
| 409 | `offscreen` | The node or element has no box on screen, or a touch swipe or gesture point is off the page |
| 409 | `obscured` | Something, such as the keyboard, covers the node. `force` taps the part that shows |
| 409 | `content_unavailable` | A `touch` tap on a page that is not on screen, or that a dialog, menu, or the tab switcher of the browser covers. Also a call to a page that timed out while Chrome shows a page's dialog, such as an alert, which holds the page until someone dismisses it |
| 409 | `not_visible` | `cdp` touch input into a tab that is not on screen, since Chrome holds it until the tab shows, or `touch` input into a tab Chrome would not bring forward |
| 409 | `intent_refused` | Android refused the intent, for example an activity that is not exported |
| 409 | `intent_failed` | Android could not start the intent for another reason; the message has its output |
| 409 | `stop_failed` / `clear_failed` | `am force-stop` or `pm clear` failed; the message has its output |
| 409 | `screen_busy` | Someone else held the screen for the whole 60 s wait |
| 409 | `screen_taken` | A person in the viewer took the screen from this session |
| 409 | `tab_limit` | A session has 4 tabs it opened, or all sessions 8 |
| 409 | `tab_stuck` | Chrome shows another session's tab and would not switch away, so the screen call did not run |
| 409 | `text_not_cleared` | `clear` with the on-screen keyboard could not empty the field, so nothing was typed |
| 409 | `took_screen` | A call from a session without the screen brought an app or Chrome forward; the message names it |
| 409 | `tab_reloaded` | Chrome had unloaded the hidden tab, so PhoneAPI reloaded it instead of sending input or script to a page the caller has not seen |
| 409 | `tab_frozen` | `cdp` or `cdp-mouse` input into a tab Chrome has paused, or whose timers it has stopped, as it does to tabs hidden in the background. Also a wait on such a tab. Bring it forward with `touch` or `show`, or reload it |
| 409 | `session_conflict` | A screen call while the session that started the caller with `session_start`, or one the caller started, holds the screen |
| 409 | `clipboard_unreadable` | The helper could not read the clipboard's image from the app that copied it |
| 413 | `too_large` | A clipboard image over 20 MB, sent or on the clipboard, or a PDF over 20 MB to read |
| 422 | `unsupported_char` | Text the on-screen keyboard cannot type; use `mode` `setText` or `keyevent` |
| 422 | `unsupported` | An action this Android release lacks, such as the IME enter action before Android 11 |
| 428 | `session_required` | A REST call that needs a session came without `Mcp-Session-Id`; start one with `POST /v1/sessions` |
| 429 | `too_many_sessions` | `initialize` or `POST /v1/sessions` past 16 sessions for the token or 32 in all |
| 500 | `internal` | Unexpected failure |
| 500 | `pin_store_failed` | The PIN could not be encrypted or saved |
| 502 | `cdp_error` | Chrome's DevTools socket closed or answered with an error. Also a read whose page loaded a new document twice while it ran |
| 502 | `page_error` | The page-reading script failed or returned nothing, or a recorded response cannot be read: it is not JSON, or Chrome no longer has its body. Also a PDF that could not be read from the page PhoneAPI puts in its place: an HTTP error, a reply that is not a PDF, a fetch Chrome refuses (as it does for an `http` PDF at a local or LAN address), or one not from an http or https URL |
| 502 | `stream_error` | The helper stopped during video or audio capture |
| 503 | `helper_unavailable` | Helper down or dropped; message may say starting / needs pairing / needs USB / stopped |
| 503 | `helper_error` | A helper call, or a shell command it ran, failed; the message has its output |
| 503 | `browser_unavailable` | No DevTools socket: Chrome is not running, or did not publish one within 10 s of a tab open starting it |
| 503 | `browser_frozen` | Android froze Chrome in the background, so nothing in it answers |
| 503 | `cdp_error` | DevTools cannot be reached: wireless debugging is not paired, or Android 10 has no USB tunnel |
| 503 | `cdp_timeout` | DevTools did not answer in time |
| 503 | `screenshot_failed` | The helper did not return a PNG, for example with the screen off |
| 503 | `stream_error` | Display mirroring could not start |
| 503 | `audio_error` | Audio capture could not start; it needs Android 11 or later and the helper |

## REST

### Device

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/device` | observe | `DeviceInfo`: display, helper status, capabilities, whether Android's app freezer is on (`appFreezer`), whether PhoneAPI holds Chrome's Custom Tabs binding, which keeps the freezer off Chrome while the helper runs (`chromeKept`), and who has the screen (`screenHolder`: `you` when `Mcp-Session-Id` names the holder, `parent` or `subagent` for a session related by `session_start`, `agent`, `person`, or `script`) and for how long (`screenHeldMs`) |
| `POST` | `/v1/device/wake` | control or browser | Turn the screen on → `ActionResult` |
| `POST` | `/v1/device/unlock` | control or browser | Dismiss keyguard (`UnlockRequest`, optional) |
| `POST` | `/v1/device/lock` | control | Lock the device |
| `POST` | `/v1/device/orientation` | control | Auto-rotate, or lock to `lock` / `0` / `90` / `180` / `270` (`OrientationRequest`) |
| `PUT` | `/v1/device/pin` | admin | Store unlock PIN (`SetPinRequest`) → `PinStatus` |
| `DELETE` | `/v1/device/pin` | admin | Clear stored PIN → 204 |

### UI and screenshot

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/ui/snapshot` | observe | Accessibility / UiAutomation tree → `UiSnapshot` |
| `POST` | `/v1/ui/find` | observe | Match `NodeSelector` → `FindResult`. `redact` masks secrets as for the snapshot and counts them in `redacted` |
| `POST` | `/v1/ui/nodes/{ref}/action` | control | `NodeActionRequest` (`mode` `real` or `semantic`) |
| `GET` | `/v1/screenshot` | observe | PNG; `?scale=` 0.1–1 |

### Input and IME

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `POST` | `/v1/input/tap` | control | Point or selector (`TapRequest`) |
| `POST` | `/v1/input/swipe` | control | Points, or selector + `direction` |
| `POST` | `/v1/input/gesture` | control | Pointer paths (`GestureRequest`) |
| `POST` | `/v1/input/key` | control | `KeyRequest` (`BACK`, `HOME`, `KEYCODE_*`, …) |
| `POST` | `/v1/input/text` | control | `TextRequest`; `mode` `auto` / `keyboard` / `keyevent` / `setText` |
| `POST` | `/v1/ime/hide` | control | Hide soft keyboard |
| `POST` | `/v1/ime/show` | control | Focus editable + show IME (`ImeShowRequest`) |
| `POST` | `/v1/clipboard/read` | control | `ClipboardReadRequest` → `ClipboardContent`: the clip's text, MIME types, and whether the copying app marked it `sensitive`. `copy` presses `KEYCODE_COPY` first and waits up to 1 s; `changed` says whether the clip changed |
| `POST` | `/v1/clipboard/write` | control | `ClipboardWriteRequest`: plain text, up to 100000 characters. `paste` presses `KEYCODE_PASTE` after |
| `GET` | `/v1/clipboard/image` | control | The clip's image, in the type the copying app gave it; 404 when it holds none |
| `POST` | `/v1/clipboard/image` | control | A PNG, JPEG, GIF, or WebP body, up to 20 MB, for the clipboard. `?paste=true` presses `KEYCODE_PASTE` after |

`autoWake` (default true) turns the screen on and leaves the keyguard up. Dismiss the keyguard with `POST /v1/device/unlock`. Browser input with `input` `cdp` or `cdp-mouse` ignores it and leaves the screen as it is.

Browser pointer input has three `input` modes. `touch` brings the tab to the front and injects a hardware touch. `cdp` sends touch events over DevTools without bringing the tab forward, but Chrome only takes them on the tab it is showing, with Chrome on screen, so `cdp` fails with `not_visible` otherwise. `cdp-mouse` taps with a DevTools mouse click, which reaches a background tab even with Chrome hidden and the screen off; the page sees a mouse rather than a finger. Keys and text with `cdp` work with Chrome hidden. All of these are trusted input. A click or scroll made from `evaluate` is not (`isTrusted` is false), which bot detection can see, and with Chrome hidden there is no trusted way to scroll: Chrome holds wheel and scroll input as it does touch.

The clipboard calls need the shell helper (`clipboard`), which reads the clip as the shell user, since Android 10 and later let only the app in front or the keyboard read it. They take the screen: the clipboard belongs to whoever is using the phone, a write replaces what they copied, and both show on the phone: Android 12 and later toast "Shell pasted from your clipboard" on a read, and Android 13 and later preview a write. An image on the clipboard is a `content://` URI, which the helper reads with `content read`; reading the clip grants the shell read access to it. An image written to the clipboard is served by PhoneAPI's own provider, which only the clip's readers may open, and PhoneAPI keeps only the newest one. An app that does not take images, which includes plain text fields before Android 12, pastes the URI as text. When the holder lets the screen go, cleanup empties the clipboard if it still holds a clip PhoneAPI wrote or copied, and leaves one anyone copied since. When a session gets the screen, its first screen call empties the clipboard, whoever copied the clip, so a session never reads what a person or an earlier holder left there.

`/viewer` does not post a finished swipe. It streams the canvas on `WS /v1/input/pointer` so the phone moves while the finger is still down.

### Apps and intents

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/apps` | observe | Installed apps → `AppInfo[]` |
| `POST` | `/v1/apps/{pkg}/launch` | control | `LaunchRequest` |
| `POST` | `/v1/apps/{pkg}/stop` | control | Force-stop (helper) |
| `POST` | `/v1/apps/{pkg}/clear` | control | Clear data (helper) |
| `POST` | `/v1/intents` | control | `IntentRequest` (helper) |

### Wait

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `POST` | `/v1/wait` | observe or browser, by condition | `WaitRequest` → `WaitResult` |

### Browser (CDP helpers)

Target ids look like `chrome_devtools_remote~<pageId>`. Chrome's tabs are listed with DevTools' `Target.getTargets`, so a tab has the same id however it was found, and `openerId` names the tab that opened a popup. Tabs Chrome has unloaded can be missing from the list, and the blank placeholder tab PhoneAPI keeps for the screen is left out.

A session, from MCP or REST, owns the tabs it opens, popups they open, and unowned tabs it claims by using them; `browser_targets` lists those and unowned tabs, with `owned`, and leaves out other sessions' tabs, which every browser tool refuses as `not_found`. A session can have 4 tabs it opened and all sessions 8; past either, opening fails with `tab_limit`. When a session ends, the tabs it opened close and those it claimed go back to being unowned; tabs left by a restart close when the helper next starts. The screen holder can also use the WebViews of the app in front. A tab Chrome unloaded to save memory is reloaded in the background when used: reads, finds, and snapshots return the fresh page with `reloaded` true, and input and `eval` fail with `tab_reloaded`. Chrome must publish DevTools; Android 11+ needs Wireless Debugging; Android 10 needs the USB tunnel from `phoneapi helper`.

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/browser/targets` | browser | Tabs and debuggable WebViews |
| `POST` | `/v1/browser/tabs` | browser | Open tab (`OpenTabRequest`). When Chrome is not running, it starts Chrome first, which takes the screen while the tab opens and gives it back after unless the session already had it. `tookScreen` is true when Chrome was on screen and switched to it. `capture` records the page's JSON responses from the start of its load, as under [Recorded responses](#recorded-responses). `incognito` opens it in Chrome's incognito profile, with none of the phone's logins. It first closes incognito tabs no session owns, and notes when it shares the profile with another session's. That takes the screen while the tab opens and gives it back after unless the session already had it, and screenshots show the tab black unless the owner turned on Chrome's Incognito Screenshot flag, as in [sharing.md](sharing.md#incognito-tabs) |
| `POST` | `/v1/browser/targets/{id}/navigate` | browser | `NavigateRequest` |
| `POST` | `/v1/browser/targets/{id}/close` | browser | Close the tab without bringing it forward → `ActionResult` |
| `GET` | `/v1/browser/targets/{id}/snapshot` | browser | Compact AX outline → `BrowserSnapshot`. `maxNodes` (default 400, up to 5000) and `maxChars` cap it; cuts are reported as for the UI snapshot. `show` brings the tab on screen first. `shown` and `pending` as for `read` |
| `POST` | `/v1/browser/targets/{id}/read` | browser | `BrowserReadRequest` → `BrowserReadResult`: the page's text as markdown or plain text, a slice at a time, or its outline. A `selector` reads every element it matches, in page order, and `selected` counts them. `version` from an earlier result gets `changed` false and no content when the content still has it. `paywall`, `feeds`, `next`, and `canonical` come from the page's JSON-LD and its `rel` links. `data` names the JSON the page embeds, and `scope` `data` reads it; see [Embedded data](#embedded-data). `shown` says whether Chrome was drawing the tab, and `pending` lists signs of content still to load. `show` brings the tab on screen first and waits for its text to stop growing. A PDF that Chrome shows in its own viewer is read from the file, and `extracted` says `pdf`; a `selector` on it is a 400 |
| `POST` | `/v1/browser/targets/{id}/find` | browser | `BrowserFindRequest` → `BrowserFindResult`: text matches or links, at the offsets `read` uses. `queries` (up to 20) searches for each on its own, with results in `queries`. Links come once per URL, without tracking parameters such as `utm_*` and Amazon's `pd_rd_*` and `/ref=`, and `repeats` counts those left out |
| `POST` | `/v1/browser/targets/{id}/tap` | browser | Ref or CSS. A selector waits up to `waitMs` (default 5000, up to 30000) for an element with a box. `input` `auto` (default), `touch`, `cdp`, or `cdp-mouse` (`BrowserTapRequest`) |
| `POST` | `/v1/browser/targets/{id}/swipe` | browser | CSS pixels or a direction. `input` `touch` or `cdp` (`BrowserSwipeRequest`). With `touch`, a point off the page is 409 `offscreen` |
| `POST` | `/v1/browser/targets/{id}/gesture` | browser | Pointer paths in CSS pixels. `input` `touch` or `cdp` (`BrowserGestureRequest`). With `touch`, a point off the page is 409 `offscreen` |
| `POST` | `/v1/browser/targets/{id}/key` | browser | Key name. `input` `touch` or `cdp` (`BrowserKeyRequest`). `touch` also needs the control scope |
| `POST` | `/v1/browser/targets/{id}/text` | browser | Type. `touch` uses text modes; `cdp` inserts (`BrowserTextRequest`) |
| `POST` | `/v1/browser/targets/{id}/evaluate` | browser | Isolated-world JS (`EvalRequest`) → `EvalResult` |
| `POST` | `/v1/browser/targets/{id}/capture` | browser | `BrowserCaptureRequest` → `BrowserCaptureResult`: start (`on` true, the default) or stop recording the tab's JSON responses for `scope` `data`; see [Recorded responses](#recorded-responses) |
| `POST` | `/v1/browser/targets/{id}/console` | browser | Collect logs for a timeout (`ConsoleRequest`) |

#### Embedded data

A page built by scripts often carries its data as JSON for the scripts to pick up: Next.js's `__NEXT_DATA__`, JSON-LD, an assignment such as `window.__INITIAL_STATE__ = {…}`, a string given to `JSON.parse`, or a call such as `self.__next_f.push([…])`, whose blob is named by the callee. An object or array literal written as code, with unquoted keys, single quotes, or trailing commas, reads as the JSON it stands for. Anything else in it, such as a function, fails the whole literal, and nothing is run. Assignments and call arguments under 256 characters are left out as settings. A tab showing a JSON response has one blob, `response`, and one showing a script response is read for literals as an inline script is. Every read lists the blobs in `data`, with their names, sources, and lengths, and the MCP header names them in a `# data:` line.

`scope` `data` reads them. Script in the page's own world is never read, only the text of its script elements, so a value the page's scripts changed after loading still reads as the page sent it.

- Without `path`, `read` returns an outline of every blob: its name, size, and source, then its keys three levels down, with each one's type and size, the length of each array, and the keys of its first item. Every line starts with the path that reads it.
- With `path`, it returns that value as indented JSON, sliced like any content. A path is a blob's name, then a JSON Pointer into it: `__NEXT_DATA__/props/pageProps/flight`. Array items count from 0, and a `/` or `~` in a key is written `~1` or `~0`. A repeated key names its last value, as JavaScript reads it. With `outline`, it returns the outline of that value.
- A `*` segment matches every array item or object key at its level, or every blob as the first segment. Each value comes back on its own line as the path that names only it, then the value as compact JSON, and `values` counts them. `paths` reads up to 20 paths in one call the same way, and a path that names nothing gets a `#` line saying why while the others still come back.
- `find` with `scope` `data` searches the JSON as `read` renders it, and gives each match the `path` of its line. Its offset is into the blob's JSON, or into `path`'s when `find` was given one.

Numbers keep the digits the page wrote. A read takes at most 2,000,000 characters of data from the page. A blob past that is listed but cannot be read, and an outline or a search of every blob reads blobs in page order until the next would pass it. A `selector` does not apply to data, nor does `kind` `links`. Data the page's scripts fetch after it loads is not embedded; [Recorded responses](#recorded-responses) covers it.

#### Recorded responses

Many pages fetch their data after they load, or when a person taps something. `capture` records those responses so `scope` `data` can read them: `POST /v1/browser/targets/{id}/capture`, or MCP's `browser_capture`, starts it on a tab. To record a page's load, open the tab with `capture` true, or start recording before navigating. A `browser_read` or `browser_find` with `url` and `scope` `data` records for you.

- Every response the page fetches with a JSON content type, whatever its request method, is listed after the embedded blobs, as `response-1`, `response-2`, and so on in the order they finished, with its URL as the source and its length in bytes. A status outside 200 to 299 is added to the source. Paths, `*`, `paths`, outlines, and `find` work on them as on any blob.
- A body is fetched from Chrome only when a read wants it. A body behind a prefix such as `)]}'` that keeps it from loading as a script reads without the prefix. A body of several JSON values one after another, as in NDJSON or a chunked reply that puts each chunk's length on a line before it, reads as an array of those values, lengths included. One that does not parse as JSON is listed with the reason, and a read of it fails with `page_error`.
- Chrome holds the bodies in the page's renderer, at most 2,000,000 bytes for one response and 4,000,000 for all of them, dropping the oldest first. A body it no longer has is listed with that reason. When the page loads another document, the earlier responses go. The latest 50 are kept.
- Recording lasts until `on` is false, the tab closes, or 10 minutes pass without a start or a data read. It also stops when a session claims a tab someone else was recording, and when a session gives a claimed tab back, so no one reads what another client recorded.
- Recording enables DevTools' Network domain on the tab. A page cannot see that from script, but Chrome does a little more work for each request while it is on.

### Tokens and schema

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/tokens` | admin | `TokenInfo[]` |
| `POST` | `/v1/tokens` | admin | `CreateTokenRequest` → `CreatedToken` (201; secret once) |
| `PATCH` | `/v1/tokens/{id}` | admin | `UpdateTokenRequest` → `TokenInfo`: replace the token's scopes. Ends its MCP sessions that used a scope it lost within 30 s |
| `DELETE` | `/v1/tokens/{id}` | admin | Revoke → 204. Ends the token's sessions within 30 s |
| `POST` | `/v1/sessions` | any | Start a session → 201 `SessionStarted` |
| `DELETE` | `/v1/sessions/current` | any | End the session named in `Mcp-Session-Id` → 204 |
| `POST` | `/v1/screen/release` | any | Optional `ScreenReleaseRequest`. Let the screen go for the session named in `Mcp-Session-Id` → `ActionResult`. `cleanup` (default true) hides the keyboard, goes home, and locks when no one waits and the phone was locked before; false leaves the screen as it is, such as for a person in the viewer, and keeps the session's tab Chrome is showing, as an unowned tab, after it ends |
| `GET` | `/v1/sessions` | admin | `SessionInfo[]`: sessions by a hash of their id, with age, idle time, whether they hold the screen, their tab count, how often they brought an app forward without it (`tookScreen`), and for one from `session_start`, the hash of the session that started it (`parent`) |
| `DELETE` | `/v1/sessions/{hash}` | admin | End a session → 204 |
| `GET` | `/v1/openapi.json` | observe | OpenAPI 3.1 document |

## WebSockets

Auth: Bearer header, the `phoneapi` cookie, or `access_token` on the upgrade URL. A browser's upgrade must come from the API's own address: one whose `Origin` names another host or port gets the empty 404, so another local web page cannot open these sockets with the viewer's cookie.

| Path | Scope | Summary |
| --- | --- | --- |
| `/v1/events` | observe | JSON event frames (see Conventions) |
| `/v1/input/pointer` | control | The viewer's input: live pointer contacts (`PointerFrame` JSON text frames), keys, text, orientation, copy and paste, and taking the screen over. The socket is a holder of the screen lease |
| `/v1/stream/video` | stream | H.264; query `maxSize`, `fps`, `bitRate` |
| `/v1/stream/audio` | stream | Device audio (submix capability) |

`/v1/input/pointer` frames are `{"op":"down"|"move"|"up","id":0,"x":1,"y":2,"tMs":0}` or `{"op":"cancel"}`. `id` is the contact, 0 through 9. `tMs` is milliseconds since that gesture's first `down`, shared by every contact. `x` and `y` are screen pixels. The first `down` is `ACTION_DOWN`; another finger is `ACTION_POINTER_DOWN`. A `move` carries the latest point of every finger still down. The last `up` is `ACTION_UP`. `cancel`, a bad frame, or the socket closing while a contact is down injects `ACTION_CANCEL` and releases the touch. An 11th `down`, or a `move`/`up` for an id that is not down, is ignored. Closing with nothing down injects nothing.

The socket also takes `{"op":"key","key":"HOME"}` (a `KeyRequest`), `{"op":"text","text":"hi"}` (a `TextRequest`), `{"op":"orientation","orientation":"90"}` (an `OrientationRequest`), `{"op":"paste","text":"hi"}` (puts the text on the clipboard and presses `KEYCODE_PASTE`), `{"op":"copy","id":1}` (presses `KEYCODE_COPY` and answers `{"op":"clipboard","id":1,"text":"...","changed":true}`, with no `text` when the clip has none; for an image, `"image":"image/png"` in place of `text`, and the image's bytes in the next binary frame, the only kind the server sends), a binary frame (an image to put on the clipboard and paste), `{"op":"take"}` (a `ViewerTake`, whose `autoWake`, default true, turns the screen on and leaves the keyguard up), and `{"op":"release"}` (lets the screen go, from the page's **Done** button). It holds the screen lease for the person using it: the first `down`, key, text, or orientation takes the screen if no one has it. While someone else has it, input is dropped, a whole gesture at a time. `take` takes the screen at once, even from an MCP session, which gets it back without cleanup when the socket lets go. The socket lets the screen go on `release`, after 30 s without input, and when it closes. Letting go runs no cleanup: the screen goes back to the session it took over from if that session is alive, and otherwise to the next waiter as the person left it. The server sends `{"op":"lease","holder":"none"|"you"|"agent"|"person"|"script","heldMs":12000}` when the holder changes, with `"takenFrom":"agent"` or `"script"` after a take over, and `{"op":"error","message":"..."}` when a key, text, orientation, copy, or paste fails, with the request's `id` if it had one. A copy while someone else has the screen gets no answer.

`GET /viewer?access_token=` (stream) sets the `phoneapi` cookie and redirects to `/viewer`. The page then uses that cookie. It closes the video socket while the page is hidden and opens it again when the page is visible. While visible, it reopens the video socket after a close, and after 12 s with no picture, backing off to one attempt every 10 s. The video socket sends at least one frame every 4 s, even from a still screen, and closes with `The shell helper stopped` if the helper dies. Key frames come when a viewer joins and when a viewer that fell behind needs one. Otherwise the encoder sends one every 10 s worth of frames at the requested `fps`, which is longer when the screen changes less often. A viewer that falls behind skips frames until the next key frame. The audio socket sends nothing while the phone is silent. Each Opus message is one standard Opus packet of up to 60 ms. It opens the audio socket only while the browser will play sound. One button cycles `POST /v1/device/orientation` through `auto`, `0`, `90`, `180`, and `270`. Cmd+C or Ctrl+C copies the phone's selection to the computer's clipboard, or the phone's clipboard when nothing is selected. Pasting puts the computer's text, or an image when there is no text, on the phone's clipboard and pastes it into the focused field. Copying an image converts it to PNG, the only image type browsers take. The page writes the type the phone held at the last copy, since the browser needs the type before the phone answers; when that guess is wrong, Chrome writes again, and Safari and Firefox ask for a second Cmd+C.

## MCP

`/mcp` is a stateful Streamable HTTP MCP endpoint (same Bearer token; **not** `access_token`). It does not restrict the Host header.

- `initialize` starts a session, and the response's `Mcp-Session-Id` header names it. Every later request carries that header and the token that started the session; any other token gets the empty 404 an unknown token gets. An unknown or ended session gets 404, and the client starts again with `initialize`.
- POSTs are answered with JSON. `GET` is refused with 405, since PhoneAPI sends nothing unprompted. `DELETE` ends the session, which cancels its calls in progress; those get 404.
- A session ends after 20 minutes without a request, and when its token is revoked or replaced. A token can have 16 sessions open and the phone 32; past either, `initialize` fails with 429 `too_many_sessions`.
- Sessions live in the app's memory, so restarting the app ends them all.
- The `initialize` result's `instructions` give the model a short overview of sharing the phone: that parallel use is the normal case, that browser work starts in the background, that screen tools wait for the screen, `screen_release`, including to hand the screen to a person, and `session_start` for subagents and parallel tasks. They also say how to work in few calls: an action and one `wait_for` with `snapshot` or `screenshot` per step, and selectors rather than a snapshot first. Some clients never show the instructions, so the tool descriptions carry the details: `device_info`, `browser_open`, and `screen_release` say that sharing is normal, and every tool that always takes the screen ends with "Uses the screen."

Run `phoneapi server` and point a Streamable HTTP client at `http://127.0.0.1:<port>/mcp` with the bearer token. There is no stdio bridge: `phoneapi mcp` was retired when sessions arrived.

### Tools

| Tool | Scope | Summary |
| --- | --- | --- |
| `device_info` | observe | Device + helper + capabilities |
| `screen_release` | any | Let the screen go for now, with cleanup; tabs stay the session's. `cleanup` false leaves it as it is, such as for a person, and keeps its tab on screen after it ends. The description gives the hand-off steps: bring the tab on screen, unlock, release with `cleanup` false, tell the person, and `wait_for` the page after it, or `screen` with `person` |
| `session_start` / `session_end` | any | A separate session for a subagent or parallel task, with its own turn at the screen and its own tabs. Every other tool takes `session` with the id `session_start` returns; without it a call acts as the caller's own session |
| `ui_snapshot` | observe | Compact UI outline, up to `maxNodes` (default 400) |
| `ui_find` | observe | Find by selector, masked unless `redact` is false |
| `ui_act` | control | Action on a ref; `mode` `real` or `semantic` |
| `tap` | control | Point or selector; humanized by default |
| `swipe` | control | From/to or direction inside selector |
| `type_text` | control | `mode` auto / keyboard / keyevent / setText |
| `press_key` | control | Key names |
| `clipboard_read` / `clipboard_write` | control | The clipboard's text, or its image as image content (up to 1.5 MB) on a read. `copy` presses `KEYCODE_COPY` first, which copies what is selected on screen in the app in front; `paste` presses `KEYCODE_PASTE` after. Writes take text only (needs helper) |
| `wait_for` | observe or browser | Same conditions and scopes as `/v1/wait`. `snapshot` adds a capped, masked outline and `screenshot` a PNG; both need observe. `target` names the tab for browser conditions that name none |
| `keyboard_hide` / `keyboard_show` | control | Soft keyboard |
| `app_launch` / `app_stop` / `app_clear` | control | Package control (`stop`/`clear` need helper) |
| `open_intent` | control | Start an intent (needs helper) |
| `device_wake` / `device_unlock` | control or browser | Screen on / keyguard |
| `device_lock` | control | Lock |
| `device_orientation` | control | Auto-rotate, or lock the screen to the current or a named rotation |
| `browser_targets` / `browser_open` / `browser_navigate` / `browser_close` | browser | Tab list, navigation, and closing a tab. `browser_open` with `incognito` opens an incognito tab, which takes the screen while it opens. Opening a tab while Chrome is not running starts Chrome, which also takes the screen while the tab opens |
| `browser_snapshot` | browser | AX outline |
| `browser_tap` / `browser_swipe` / `browser_gesture` | browser | Pointer input. `input` `auto` (default), `touch`, `cdp`, or `cdp-mouse` (tap only) |
| `browser_key` / `browser_type` | browser | Key presses and typing. `cdp` stays on a background tab; device keys such as HOME need `touch`, and `browser_key` with `touch` needs the control scope too |
| `browser_read` | browser | Page text as markdown, sliced by `offset` and `maxChars` (default 8000), or `outline` for headings with offsets. `scope` `article` (default, via Readability) or `page`, or a CSS `selector`, which reads every match. `version` returns only the header, `unchanged`, when the content has not changed. The header names the page's `paywall`, `feeds`, `next` page, `canonical` URL, and embedded `data`. `scope` `data` reads that JSON by `path` or `paths`, as under [Embedded data](#embedded-data). `url` opens a new tab and reads it in one call, and `close` closes the tab afterwards. When such a read fails and the tab stays open, the error names it. When Chrome saves a PDF rather than showing it, which closes the new tab, the read goes on in a fresh tab, and the `# opened` line names that tab and says so. PhoneAPI remembers the last 256 such URLs while it runs, and a `url` read of one goes straight to a fresh tab, so Chrome saves it only once. On Chrome's own error page, the header shows the URL that failed, not `chrome-error://`. With `scope` `data`, a tab it opens records its responses, and the `# opened` line says `recording`. A tab that is not on screen is not drawn, so content that loads when drawn may be missing: the header says `hidden` and lists `pending` signs, and `show` brings the tab forward first |
| `browser_find` | browser | `kind` `text` (matches with context and nearest heading) or `links` (text, URL, same-origin, each URL once), at offsets `browser_read` takes. `queries` searches for up to 20 things at once. A result cut at `limit` says to raise it, up to 200, or at 200 to narrow the search. With `scope` `data`, it searches the embedded JSON, within `path` when given, and each match shows its path. `url` and `close` as for `browser_read` |
| `browser_capture` | browser | Start (`on` true, the default) or stop recording a tab's JSON responses, which `browser_read` with `scope` `data` then lists as `response-1`, `response-2`, and so on. See [Recorded responses](#recorded-responses). `browser_open` takes `capture` to record from the start of the load |
| `browser_eval` / `browser_console` | browser | JS and logs |
| `screenshot` | observe | PNG last resort (`scale` default 0.5) |
| `logcat_tail` | observe | Recent lines via helper |

When node ids are not stable, tool descriptions say so. Streams and token admin are REST-only.

### Resources

| URI | Description |
| --- | --- |
| `phoneapi://device/capabilities` | Current capability flags |
| `phoneapi://viewer` | How to open `/viewer` with `access_token` |

## Enumerations (common)

| Field | Values |
| --- | --- |
| Wait node `state` | `present`, `absent`, `visible`, `enabled`, `disabled`, `checked`, `unchecked`, `focused`, `selected` |
| Browser element `by` | `css`, `xpath`, `text` |
| Browser element `state` | `present`, `absent`, `visible` |
| Browser log `level` | `verbose`, `info`, `warning`, `error` |
| Swipe `direction` | `up`, `down`, `left`, `right` |
| Screen `orientation` | `auto`, `lock`, `0`, `90`, `180`, `270`. `0` is the device's natural orientation. `display.autoRotate` reports the system setting; `display.rotation` is the current quarter-turn (`0`–`3`). An app can still force its own orientation. |
| Text `mode` | `auto`, `keyboard`, `keyevent`, `setText` |
| Browser `input` | `auto` (default; `touch` for a session that holds the screen, and otherwise `cdp-mouse` for a tap and `cdp` for keys and text, so it does not take a free screen; swipes and gestures use `touch`), `touch` (bring the tab forward and inject a hardware event), `cdp` (dispatch over DevTools; touch needs the tab showing in Chrome on screen, keys and text reach a background tab), `cdp-mouse` (a mouse click that reaches a background tab, taps only). Swipe and gesture coordinates are CSS viewport pixels. A touch swipe or gesture stays on the page: a point off it is 409 `offscreen`, and humanized motion near an edge is kept inside it. A touch key is a hardware key for whatever window has focus, so `browser_key` with `touch` needs the control scope, and `auto` keys use `cdp` for a token without it. |
| Node `action` | `click`, `longClick`, `focus`, `clearFocus`, `select`, `setText`, `scrollForward`, `scrollBackward`, `expand`, `collapse`, `dismiss`, `showOnScreen`, `imeEnter`, plus other platform / app-listed names. `imeEnter` needs Android 11. |

Full request and response schemas live in OpenAPI and under `api-model` (`DeviceInfo`, `UiSnapshot`, `TapRequest`, `WaitRequest`, `BrowserTarget`, `ApiError`, …).
