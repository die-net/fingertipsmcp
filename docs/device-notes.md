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
- `Page.bringToFront` answers before the switch is done, and nothing announces the end of it, so PhoneAPI polls `document.visibilityState`. On the Android 10 and Android 16 emulators, on 2026-11-05, a `touch` tap on a hidden tab took 70 to 100 ms longer than one on the tab already showing.
- On the Android 16 emulator, a tab opened while Chrome is in the background becomes Chrome's selected tab. Chrome selects it as it comes forward, even over a `Page.bringToFront` sent just before, which is why `show` checks that its tab is still showing and tries again. Opening the tab did not bring Chrome forward.

On the Android 13 emulator with Chrome 109 on 2026-11-06, `/json/list` named a tab `36`, and `Target.getTargets` named the same tab with 32 hex digits. So `POST /v1/browser/tabs` returned the first, and `GET /v1/browser/targets` listed the tab under the second with `owned` false. Ending the session still closed the tab. Chrome 133 has one id per tab.

The first id is the Android tab's number, and it is what `Target.createTarget` answers with on Chrome 109, while `Target.getTargets` has the second. Measured that day: a page's own connection answers `Target.getTargetInfo` with the listed id on Chrome 109 and 133, reached through either id, and the browser connection's `Target.getTargetInfo` echoes the number back. So PhoneAPI asks the new page. The mismatch had also kept the startup sweep from matching a leftover, made each put-back open a new placeholder, and stopped incognito opens with `Chrome did not say which profile a new tab is in`. With the listed id, on the Android 13 emulator, a session's tab listed `owned` true, a leftover closed at the next start, and an incognito tab opened.

`Target.getTargets` lists only tabs Chrome has loaded. On Chrome 109 that day `/json/list` had 33 tabs, restored from earlier runs, and `Target.getTargets` had 3. A restarted Chrome also gives its tabs new hex ids, as one tab did on the Android 13 emulator that day, so a tab left by a run before Chrome restarted cannot be matched, and the sweep leaves it open.

The startup sweep used to read the saved list when it first got an answer from Chrome, by which time a live session's tabs were on it. On the Android 13 emulator on 2026-11-06, with Chrome stopped at the first helper connect, a tab a session then opened stayed open through a helper restart, and a leftover closed at the next start with Chrome running.

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

Checked on the Android 16 emulator with Chrome 133 on 2026-11-06, with `Page.crash` standing in for an unload. A read of the tab returned the new page with `reloaded` true, so `readyState` read in an isolated world right after `Page.reload` does not lose the reload. A key got `tab_reloaded` in 0.15 s. With a page that took 12 s to answer, a read failed `tab_reloaded` at 10.1 s, and a read after the page loaded worked.

On that emulator, with a page running 3 s tasks back to back, 3 `cdp` keys into the visible tab and 4 into a hidden one all went through, with no false `tab_frozen`. The first took 4 to 6 s, waiting for a gap between tasks. A visible tab kept its timer at one tick a second for 331 s, past the 5 minutes that hidden tabs get, and took a `cdp` key at the end. `am freeze` on Chrome gave `browser_frozen` at 17.1 s for a read and for a key, which is the 15 s handshake and the 2 s probe, and `cdp_timeout` at 15.1 s for the target list.

### Closed tabs

Chrome refuses a DevTools connection to a closed tab with 500 "No such target id", in Chrome 133 on the emulator and Chrome 155 on the phone, and 404 in some releases. PhoneAPI reports it as `not_found` rather than retrying.

### Page dialogs

Chrome draws a page's alert, confirm, or prompt in `tab_modal_dialog_container`, with the title in a view called `title`. A page held by one answers nothing over DevTools, so a `browser.dialog` wait counts a dialog already open from what is on screen.

Checked with Chrome 91, 109, and 145 on the Android 10, 13, and 17 emulators on 2026-11-06:

- The title is the page's host, then its port if the URL names one, then " says": `example.com says`, `127.0.0.1:8765 says`, `localhost:8766 says`. It has no scheme or path, and nothing else on the dialog names the tab. So PhoneAPI takes a title for a page's own only when it holds that whole host, with no more of a host or a port beside it. On Android 10, a `browser.dialog` wait on `example.com` timed out with `www.example.com says` in front, and matched in about 50 ms on the page whose dialog it was.
- With Chrome 145 and a dialog up in the tab in front, a hidden tab on the same host did not answer `Runtime.evaluate` within 5 s either, nor did a new session on the tab in front. So neither the title nor the tabs can say which of two tabs on one host a dialog belongs to, and a `browser.dialog` wait on the hidden one counts it.
- With Chrome 145, a page's own timer calling `alert` showed no dialog with no DevTools session attached, in a tab opened by `am start`. The same call from DevTools did. Why was not looked into.

Not measured: the title in another language (Chrome did not come up under DevTools with `cmd locale set-app-locales`, so the match leaves out the words around the host), a dialog from a cross-origin frame (the frame was not in `Page.getFrameTree`), and a host with non-ASCII letters.

### Script worlds

A world belongs to one document. `Runtime.evaluate` naming a world whose document has gone fails with code -32000 and the message `Cannot find context with specified id`, and runs nothing. A context id that never existed gets the same reply. Chrome gives -32000 for any server error, so PhoneAPI needs the message too, and it is the only thing that names this failure. Checked with Chrome 109 and 145 on the Android 13 and 17 emulators on 2026-11-06, with a reload between making the world and using it.

## Android's app freezer

On Android 12 and later the cached-app freezer is on by default. On the Android 16 emulator on 2026-10-21, about 2 minutes after Chrome went to the background, every Chrome process was frozen (`isFrozen=true`), and nothing answered, not even the browser endpoint. Android 11 has the freezer but leaves it off by default. On the phone on 2026-10-22, `dumpsys activity` printed "Freezer settings" with `use_freezer=false`, and `cached_apps_freezer` was unset.

The freezer's effective state is `use_freezer` under "Freezer settings" in `adb shell dumpsys activity`. `dumpsys activity settings` leaves that section out, and the whole dump is hundreds of kilobytes, so the helper reads it through `grep -m 1`. The setting behind it is `cached_apps_freezer` (`settings get global cached_apps_freezer`), unset by default. On the Android 16 emulator, changing the setting changed `use_freezer` at once, without the reboot the developer option asks for. With it `disabled`, Chrome was never frozen in 150 seconds in the background. Set back to the default, Chrome was frozen within 90 seconds. Turning it off does not thaw an app that is already frozen; that waits until the app comes to the front or the phone restarts.

Holding Chrome's Custom Tabs binding was checked on the Android 16 emulator, with the freezer at its default, on 2026-10-21, using a throwaway build:

- With the binding held, Chrome's browser process stayed at importance 200, the same as PhoneAPI's service, and Android froze none of Chrome's live processes in 10 minutes in the background. A hidden tab answered DevTools throughout. Its timers still stopped at about 5 minutes, which is Chrome's own pausing and leaves reads and input working.
- The renderers of hidden tabs sat at importance 900 to 910, the cached range, yet were not frozen. Android's low-memory killer can still take them, which is unloading as above.
- Without the binding, Chrome dropped to the cached range within a minute, and every Chrome process was frozen by 2 minutes.
- Chrome's Custom Tabs service listed PhoneAPI as its client from a foreground-service process.

On the phone on 2026-10-22, PhoneAPI held the binding (`chromeKept`).

## Starting activities

`am start` exits 0 when Android refuses an intent, and prints the result code as text. So the helper makes the call `am` makes, on `IActivityTaskManager`, and PhoneAPI decides from the code: `startActivityAndWait` when a call needs to know what took the intent, as `am start -W` does, and `startActivity` otherwise.

Read from `framework.jar` on the emulators on 2026-11-06:

- `startActivityAndWait` takes `(IApplicationThread, String, Intent, String, IBinder, String, int, int, ProfilerInfo, Bundle, int)` on Android 10. Android 13, 16, and 17 add a `String`, the calling feature id, after the calling package. `startActivity` is the same without the last `int`, the user. The helper places the arguments by their types.
- Android 11's signature has not been read from a device, though starts run there, as below. In AOSP's `android-11.0.0_r1` source, both calls have the feature id, as Android 13 does, and `am`'s `runStartActivity` adds `FLAG_ACTIVITY_NEW_TASK` and ends in `return 0` whatever the code was.
- The `ActivityManager.START_*` codes PhoneAPI uses had the same values on all four: `START_SUCCESS` 0, `START_RETURN_INTENT_TO_CALLER` 1, `START_TASK_TO_FRONT` 2, `START_DELIVERED_TO_TOP` 3, `START_SWITCHES_CANCELED` 100, `START_INTENT_NOT_RESOLVED` -91, `START_CLASS_NOT_FOUND` -92, `START_PERMISSION_DENIED` -94. The SDK hides them, so PhoneAPI has its own copies. `ActivityManager.isStartResultSuccessful` accepts 0 through 99 in the Android 11 source, which is what the audio start uses.

Run through `POST /v1/intents` and `POST /v1/apps/{pkg}/launch` on the Android 10, 13, and 17 emulators the same day:

- An intent nothing handles gave -91 and a missing class -92, both as `not_found`. Categories, extras, and flags reached the activity.
- A component that is not exported gets no code. Android throws `SecurityException`, `Permission Denial: starting Intent { … } not exported from uid …`, and the helper reports that as `START_PERMISSION_DENIED` with the exception's message.
- A `VIEW` for Chrome on Android 10 gave `START_TASK_TO_FRONT` with Chrome already open, which goes back to the client as a warning, as it did from `am`.
- A chooser has no code. `android.intent.action.WEB_SEARCH`, which has two handlers on the Android 17 image, gave 0 with `android/com.android.internal.app.ResolverActivity` as the activity that took it, the same as `am start -W` printed. So that name is the only sign of a chooser, and PhoneAPI matches it, and `ChooserActivity`, as text.
- The wait lasts as long as the launch. A cold start of Settings returned about 0.5 s after Android logged `Displayed`, at 0.7 to 1.6 s, and the first one after a boot took 5 s.
- Without the wait, `startActivity` started Chrome's launcher entry for a tab open with Chrome stopped, on all three.
- The Android 11 audio start's heap-dump activity, run on the Android 17 emulator on 2026-11-06 with a throwaway build that took out the Android 11 check: `startActivityAndWait` gave `START_SUCCESS` and the activity's name, also with the screen off, and `removeTask` left the launcher on top. A class that does not exist gave -92, and audio still started. Android logged `Displayed` at about 0.8 s, and at 2 s for the first start after a boot, which ran past a 2 s wait. So the audio start's limit is the same 20 s as an app start. The shell can't `pm disable` that activity, since `pm` threw.
On the Android 11 test phone on 2026-11-06, `app_launch` of Settings with `wait` took 0.6 to 1.3 s and returned ok. `open_intent` of a `VIEW` for Chrome, with Chrome open, opened the tab and gave `Activity not started, its current task has been brought to the front`, and the tab was the caller's. The heap-dump activity's start ran for audio. An intent refused or left unhandled was not tried there.

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

## PDFs

Checked on the Android 16 emulator with Chrome 133 on 2026-11-05, with a test PDF served from the Mac:

- With Chrome's defaults, `POST /v1/browser/tabs` to a PDF URL returned a tab id, then Chrome saved the file to `/sdcard/Download/` and the tab was gone from `browser_targets`. Nothing was left to read.
- `chrome://flags` has "Open PDF Inline on Android" (`#android-open-pdf-inline`), "Open PDF Inline on Android pre-V", and "Provide assist content for PDF", all at Default. With the first one Enabled, the tab stayed open on `chrome-native://pdf/link?url=…`, a native viewer. A flag set on `chrome://flags` and followed within seconds by a force stop was kept once and lost once; one set 20 s before a force stop was kept, so Chrome seems to write flags on a timer as well as at shutdown. The page's Relaunch button kept the flag, but left Chrome running with no activity, and `Target.createTarget` answered "Not supported" until Chrome was started again. `Browser.close` is not in Android Chrome's DevTools. `--enable-features=AndroidOpenPdfInline` in `/data/local/tmp/chrome-command-line` turned the viewer on reliably.
- On that tab the web page is empty: `read` and `find` returned 0 characters, `snapshot` showed only `RootWebArea`, and `evaluate` saw `<html><head></head><body></body></html>`. `navigate` to a PDF URL returned 400 `net::ERR_ABORTED` though the viewer opened.
- UiAutomation sees the text. Each page the viewer has laid out is an `android.view.ViewGroup` whose content description holds that page's full text, lines split by `\r\n`. `ui_find` with `descContains` returns it whole; the compact snapshot cuts it. Of a 6-page PDF, only the pages on screen had nodes (pages 1 and 2, then 5 and 6 after swiping), so reading a whole PDF this way takes a swipe and a find per screen, holding the screen.
- Script in an isolated world on the viewer can't fetch the PDF: its origin is `null`, and `fetch` failed with or without `grantUniveralAccess`. `Network.loadNetworkResource` for the viewer's frame fetched it in 8 ms, and `IO.read` gave it as base64.
- Chrome's viewer shows the file from the navigation's own response and does not fetch it again. Opened by a link on Chrome 133 with `AndroidOpenPdfInline` and on Chrome 145, the server saw one request: `Sec-Fetch-Site: same-origin`, `Sec-Fetch-Mode: navigate`, `Sec-Fetch-Dest: document`, a page's `Accept`, the linking page as `Referer`, every cookie, and no `Range`. The second request seen earlier was most likely Chrome restoring a tab as it restarted.
- Over raw DevTools on both Chromes, answering a tab's navigation to a PDF's URL with HTML through `Fetch.fulfillRequest` at the request stage gave a page at that URL and origin, with no request to the server, and no download where Chrome 133 would otherwise save the file. From that page, `fetch(location.href, {cache: "only-if-cached", mode: "same-origin"})` returned the PDF that opening it had cached, with no request to the server: with `max-age`, with only `Last-Modified` and `ETag`, and with no caching headers, on Chrome 145's viewer and on Chrome 133's download path, including the 11 MB RP2350 datasheet. The injected HTML did not replace the cached PDF. `fetch` with `cache: "force-cache"` did the same in one call, going to the network only when nothing was stored. The default cache mode instead went to the network for a PDF sent `no-cache` (a full request, not a conditional one) or with no caching headers, and served one sent `max-age` or only `Last-Modified` and `ETag` from the cache. A PDF sent `no-store` was not cached, and a normal `fetch` from the page then went out `same-origin`, `cors`, with every cookie and the PDF's URL as `Referer`, and passed a `Cross-Origin-Resource-Policy: same-origin` check. From an `http` page at a private address, Chrome refused that `fetch` as from a page that is not a secure context to a more private address space.
- On Chrome 133's download path, the server saw two page-load requests for each PDF opened by a link.
- An isolated world made with the same name in a new DevTools session did not keep the globals of the last one.

Checked through MCP on the Android 16 emulator with Chrome 133's default of saving PDFs, Chrome hidden behind the home screen, and no session holding the screen, on 2026-11-05:

- A new tab on a PDF saved the file and closed. A call to it failed with `DevTools closed the WebSocket` or `not_found`. No other app came forward.
- Navigating an existing tab to a PDF saved the file and brought Google Drive's PDF viewer forward, once in one try, so a call found `took_screen`. Why Android let it start from the background was not found.
- With the retry in place, a `url` read returned the PDF's text in 1.7 s and saved one file. Further `url` reads saved no more, and a read by `target` took 0.1 s.
- A new tab sits on `about:blank` with `readyState` `complete` until the server answers, so a page that took 3 s to answer read as an empty `about:blank` until the load check stopped counting that.
- pdf.js 6.4.299's modern build failed in Chrome 133 (`Uint8Array.prototype.toHex` is newer); its legacy build worked. Loading it into a fresh isolated world took about 110 ms.
- Through `browser_read`, a 2-page PDF printed from HTML read in 0.8 s, with its headings, lists, inline code, and both web links. A 23-page, 1.3 MB PDF of 144,000 characters read in 1.3 to 1.6 s. Its headings came out at the right levels; its tables came out as runs of cell text.

Checked on the Android 17 emulator image (`android-37.0;google_apis` revision 7) with Chrome 145 on 2026-11-05, with Chrome's default flags:

- Chrome showed a PDF in its viewer, at the same `chrome-native://pdf/link?url=…` address, without a flag, and saved nothing. The viewer's page descriptions start with "Page 1: ".
- `Page.navigate` to a PDF answered `isDownload` true and `net::ERR_ABORTED` while the viewer opened, which is where `navigate`'s 400 comes from.
- `Network.loadNetworkResource` for a PDF on `127.0.0.1` failed with `net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`: the viewer's frame has an opaque origin, which Chrome treats as public, and Chrome 145 blocks a public origin's request to a local address. A PDF from the web was fetched. So a PDF on a LAN or loopback address can't be read this way.
- pdf.js and `pdf-read.js`, run over raw DevTools as `PdfReader` runs them, read the 15-page arXiv PDF of "Attention Is All You Need" in 1.2 to 1.9 s, network fetch included: 37,600 characters, the title, every numbered section heading, and 18 links. Bold subsection headings in body-size type read as paragraphs, tables as one paragraph of cell text, and superscripts as plain digits. Before rotated text was skipped, the margin's arXiv stamp read as the top heading and a figure's axis labels as 170 one-word paragraphs.
- `/json/new` on the DevTools socket did not open a tab.

With pdf.js kept in the viewer's main world, checked through PhoneAPI on the Android 16 emulator with Chrome 133 and `AndroidOpenPdfInline` on, on 2026-11-05: the first `read` of the 23-page PDF took 2.0 s, and a `find` and a second `read` after it took 150 ms and 140 ms. The test server saw two requests for the file, one from Chrome's viewer and one from the first read. A `navigate` to another PDF in the same tab read the new file.

On the Android 11 test phone with Chrome 156.0.8078.73 on 2026-11-06, through PhoneAPI and raw DevTools, against the arXiv PDFs 1706.03762 and 1810.04805:

- Chrome saves a PDF. It has no viewer here. With Chrome in front, a tab opened on a PDF stayed on `about:blank` behind Chrome's "Choose where to download" dialog, and Cancel closed the tab with nothing saved. With Chrome hidden no dialog was seen, and the file went to `/sdcard/Download/`.
- `Page.navigate` to a PDF, on a tab with `Page.enable` on, answered in 30 ms with `isDownload` true and `net::ERR_ABORTED`. `Page.downloadWillBegin` reached the page session at 50 ms, so it is sent to a page session on this Chrome, and `Page.downloadProgress` said `completed` at 460 ms.
- `Target.createTarget` with the PDF's URL: `Page.getFrameTree` right after gave the frame a `url` of `""`, not `about:blank`, and no `Page.downloadWillBegin` came, since the save began before `Page.enable`. With Chrome hidden, `Inspector.detached` followed at 0.39 s, and the file was saved. `follow` in `TabOpening.kt` takes any first URL but `about:blank` as a commit, so it returned `COMMITTED`.
- So a first plain open of a PDF, `POST /v1/browser/tabs` or `browser_read` with `url`, returned 200 in 0.7 s with no `savedPdf`. A read of that tab returned 200, `url` `about:blank`, `extracted` `page`, and 0 characters, with no error, with Chrome in front and with it hidden.
- `browser_navigate` to a PDF failed 400 `net::ERR_ABORTED`, as on the emulators, and put the dialog up with Chrome in front.
- The stand-in works on this Chrome. `capture` true opens a blank tab and navigates it, which sees `isDownload`. It returned `savedPdf` true in 6 to 9 s, with Chrome hidden and with it in front. No dialog was on screen after it, and Chrome saved the file each time. `read` of the 15-page paper took 2.1 s, `extracted` `pdf`, 38,965 characters, with the right title. A `find` for "attention" took 0.2 s with 90 matches. `read` of the 1810.04805 paper took 1.6 s, 64,220 characters. So the Fetch domain answers the navigation to a PDF here, and the page's fetch is served.
- A URL PhoneAPI remembers needs no `capture`: `browser_read` with `url` of the first paper then took 8.1 s, and read it.

Opening every tab blank and navigating it, tried for that on 2026-11-06, was wrong, and a tab now opens on its URL again. On the Android 16 emulator with Chrome 133, whose PDF handler is Drive's `PdfViewerActivity`, over raw DevTools, three tries of each:

| | Chrome left a second ago | Chrome in front |
| --- | --- | --- |
| `Target.createTarget` on the URL | no start of Drive | no start of Drive |
| A blank tab, then `Page.navigate` | Drive on screen, 3 of 3 | Drive on screen, 3 of 3 |

With Chrome hidden for longer, Chrome still asks: through PhoneAPI, ten first opens of PDFs gave 18 `START` lines for Drive in `ActivityTaskManager`, and the launcher stayed in front. Android blocks a start from the background, and lets it through for some seconds after an app was in front. So an earlier check that saw nothing come forward with Chrome long hidden proved nothing.

What works is a browser session listening first. `Browser.setDownloadBehavior` with `behavior` `default` and `eventsEnabled` true leaves Chrome's handling as it was, and `Browser.downloadWillBegin` then reaches that session before a tab session could attach, 0.1 to 0.5 s after `Target.createTarget`, on Chrome 133, 145, and 156. Its `frameId` is the new tab's target id, so a person's own download can be told apart. `Browser.cancelDownload` on that event, on the Android 11 test phone with Chrome in front, gave `canceled` within 30 ms, no dialog, no file, and the tab closed. `behavior` `deny` did the same, but for every download while it is set. Without a cancel, the dialog stayed on screen after the tab closed, and the file was saved.

With that build on the Android 11 test phone with Chrome 156, on 2026-11-06:

- Three first opens with Chrome hidden returned `savedPdf` true in 7 to 9 s, and three with Chrome in front in 4 to 4.5 s. Each read as `pdf` in 1.3 to 2.9 s. A `capture` open in front did the same in 6.3 s.
- No dialog showed, nothing but Chrome or the launcher came forward, and nothing reached Downloads.
- A plain page saw `history.length` 1, as a tab opened on its URL does.
- A URL PhoneAPI remembered opened in 3.2 s.

The merged code, at `e25a1dc`, did the same on that phone on 2026-11-07, with Chrome 156.0.8078.79 and arXiv PDFs it had not opened before:

- Three first opens with Chrome hidden returned `savedPdf` true in 6.3 to 10.2 s, three with Chrome in front in 3.9 to 4.8 s, and a `capture` open in front in 6.7 s. A remembered URL opened in 3.3 s. Each read as `pdf` in 1.0 to 2.3 s.
- No dialog showed on the opens in front, nothing but Chrome or the launcher came forward, and nothing reached Downloads. A plain page saw `history.length` 1.
- The first hidden open was the slowest, and Chrome was on top when it returned, then the launcher. That fits PhoneAPI starting Chrome, which takes the screen and gives it back, after the phone sat asleep. Whether Chrome was running before that open was not checked.

Chrome 145 on the Android 17 emulator, opened this way, saved a PDF rather than showing its viewer, and the event and the cancel worked as on Chrome 156.

With the same build on the Android 16 emulator with Chrome 133 and Drive, on 2026-11-06, 21 opens that reached Chrome gave no `START` line for Drive, and nothing reached Downloads: one with Chrome long hidden, three just after it left, thirteen with it in front, and four with `capture`, two of those in front. `capture` still navigates, so the cancel is what stops Drive there.

Other things seen while checking, on the Android 16 emulator:

- The cancel closes the tab, so the tab's session can drop while `Target.getTargets` still lists it. So a save already heard settles what that drop was.
- 3 of 25 opens in a row with Chrome in front failed `cdp_error`, two of them read as `No node with given id found`, from `PdfReader.ownPage`. `Page.navigate` returns before the commit, so `DOM.getDocument` can name the old document and `DOM.resolveNode` then fails. This is on the stand-in's path, not the open's, and did not happen in 7 opens on the Android 11 test phone. A new open worked.
- 1 of those 25, with Chrome in front and no session holding the screen, failed `took_screen`: `Chrome showed the new tab on screen and would not switch back`.
- Chrome 133 answers `Page.navigate` for a PDF with `net::ERR_ABORTED` and no `isDownload`, and the save follows. So a navigation that fails, for `capture` or incognito, gets 2 s for the save to show before it is a 400.
- A second save of a file by the same name, with Chrome hidden, left Chrome's "Download file again?" dialog waiting. It showed when Chrome next came forward, and covered the page until cancelled.
- A fresh Chrome's "Chrome notifications make things easier" prompt also covered the page when Chrome came forward, and `touch` input got `content_unavailable` until it was dismissed.

Checked through PhoneAPI on the Android 16 emulator, Chrome 133 with `AndroidOpenPdfInline` on, on 2026-11-05, against 22 public PDFs and the scoring in `pdftotext`. Word recall against `pdftotext` was 95 to 100% for papers in one and two columns, menus, the RFC, a Census report whose table pages carry `/Rotate 90`, an IRS form, a Supreme Court opinion, slides, a designed annual report, and Chinese and Arabic reports. Running headers and footers, which `pdftotext` keeps, account for most of the rest.
- Bookmarks gave the outline where a PDF has real ones: every bookmark of the papers, the RFC, and the RISC-V manual came out as a heading at its depth, and 803 of the RP2350 datasheet's 1168, the rest being past the 2,000,000-character cap. Census and a menu had bookmarks that name nothing on the page, so they used the size and numbering rules.
- The first read of the 1371-page RP2350 datasheet took 12.4 s in 2 s steps; a find after it took 0.4 s.
- A menu whose site sends `Cross-Origin-Resource-Policy: same-origin` failed the second fetch with `net::ERR_BLOCKED_BY_RESPONSE`. The RISC-V manual's release URL is sent as an attachment, so Chrome saved it and the tab was left on `about:blank`. The 23 MB and 55 MB files were refused as over 20 MB.
- A scanned manual has no text, and a gazette whose glyphs are drawn as outlines has none either. An Arabic card whose font maps glyphs to the wrong Unicode reads as fragments, as it does in `pdftotext`.
- The second fetch went out as `Sec-Fetch-Site: cross-site`, `Sec-Fetch-Mode: no-cors`, with no `Referer` and no cookie set `SameSite=Lax`, `Strict`, or with no SameSite, while the navigation that showed the PDF sent all three.
- A find for "the" in the datasheet's 2,000,000 characters took PhoneAPI from 190 MB to 1.3 GB resident in a second, and the low-memory killer stopped it. Kotlin's `Regex.findAll` makes a `Matcher` for each match, and Android's ICU-backed `Matcher` keeps its own copy of the text in native memory, so 10,000 kept matches held about 40 GB of copies before the kill. Walking one `Matcher` kept the peak at 182 MB.

Checked through MCP on the Android 16 emulator with Chrome 133 on 2026-11-06:

- With Chrome's default of saving, `url` reads of a PDF printed from HTML and of a small hand-made one returned their text in 2.6 s, with a new tab holding the read. Two earlier reads of the hand-made file came back with 0 characters and no error, and why was not found.
- Five `url` reads in a row with `close` true: two worked, the third failed `took_screen`, and the fourth and fifth failed `tab_limit`, though no tab of theirs was left open. So a read that Chrome saves leaves something counted against the session. What was counted is the owner of a tab Chrome had closed itself: `tab_limit` counted owners, which nothing removed. On 2026-11-06 on the Android 13 and 16 emulators, with four tabs open, two closed over raw DevTools, `GET /v1/sessions` read 2, two more opened, and the next open failed `tab_limit` with four really there. The PDF reads themselves were not run again.
- With `AndroidOpenPdfInline` on, a PDF that took 17 s to answer read as `pdf` in a hidden tab, and the server saw one request for it. A `url` read of it gives up at the 12 s load wait, with `not loaded after 12 s`, so the tab has to be read again.
- After a service worker claimed the page, a PDF on that origin failed `navigate` with 400 `net::ERR_ABORTED`, and a read failed in 0.97 s with `page_error` `Chrome did not open the PDF's page: net::ERR_ABORTED`, not `pdf_failed`. That worker only passes the fetch on. The server saw one request.
- A `url` read of a page that first answered 3 s or 8 s in returned the page, with no error at the commit. One that took 40 s said `not loaded after 12 s`.
- Through Readability, an article kept its byline, published time, paywall flag, and JSON-LD, and text under `display:none` stayed out. A list of 40 links read as a page. `browser_find` found links inside a table.

On the Android 10 emulator with Chrome 91 on 2026-11-06, the takeover of an arXiv PDF answered its pause and loaded the stand-in page, and the script then failed with `SyntaxError: Unexpected token '{'`. So Chrome 91 can't run the PDF scripts, which is taken to be syntax newer than it, though the token's place was not looked up.

## Starting Chrome

Chrome opens `chrome_devtools_remote` only while it runs. A tab open with Chrome stopped starts it from its launcher entry, as tapping its icon would, and waits for the socket, as [sharing.md](sharing.md) describes.

Checked on the Android 16 emulator with Chrome 133 on 2026-10-31, after `am force-stop com.android.chrome`:

- The socket appeared and `POST /v1/browser/tabs` returned in 2.1 to 3.2 s over four runs, and MCP `browser_open` in 2.0 s. Chrome restored its earlier tabs as it started.
- A session without the screen got it for the open and gave it back. Cleanup sent Chrome home about 5.5 s after the open returned, the same as after an incognito open. One that held the screen kept it.

Chrome can run with no tab and no activity. On the Android 16 emulator on 2026-11-06, with every tab closed, the launcher in front, and two Chrome processes up, `/json/version` answered, `/json/list` and `Target.getTargets` were empty, and `/json/new` said `Could not create new page`. `GET /v1/browser/targets` gave an empty list, and `POST /v1/browser/tabs` gave 502 `cdp_error` `Not supported`, since the socket is there and nothing starts Chrome. Launching Chrome made opens work again. Taking the helper down left the tab list empty the same way once.

The first tab open after a cold boot failed once on each emulator that day: on Android 16 with 503 `cdp_error` about the wireless debugging port at 19.4 s, while the restart loop was turning the setting off and on, and on Android 13 with 502 `Could not open Chrome's DevTools socket` at 2.5 s. The next opens worked. Three more cold starts on Android 13 took 2.0 to 2.4 s.

## The top task

PhoneAPI asks for the top task before and after calls that could launch an app. The first build ran `dumpsys activity activities`. It took about 8 ms on the emulator but 90 to 140 ms on the phone, where any `dumpsys` costs that much to start, adding about a quarter second to each background input. The helper now calls `IActivityTaskManager.getTasks` directly, choosing the overload as it does for input injection, and falls back to `dumpsys` if that call fails. The same call answers the "is Chrome in front" checks behind the placeholder tab.

`getTasks` takes `(int, boolean, boolean, int)` on Android 16, and `(int)` on Android 10 and 11. Checked on both emulators and the phone on 2026-10-22, where `show` brought Chrome forward with it.

## Clipboard

Android 16 reports one clipboard write as two clipboard changes, a second apart, so a change count does not identify a clip. PhoneAPI notes the timestamp Android gives each clip it writes instead. As root, the emulator refuses the helper any clipboard access.

On the Android 16 emulator on 2026-10-29, with the helper as the shell user, cleanup cleared PhoneAPI's own clip, left a clip copied after it, and left the clip after `cleanup` false. On 2026-10-31, text a person pasted in the viewer was still on the clipboard after the viewer let go, and the next session read it as empty. A session read its own write back, found the clipboard empty after a person took over and handed the screen back, and a session that got the screen after a release with `cleanup` false found it empty too.

## Audio capture

`AudioRecord` names the app that records. Android 11 and 12 take the name from `ActivityThread.currentOpPackageName`. Android 13 and later ask the current application for its `AttributionSource` instead, so the helper's stand-in application has to answer that too. Without it the helper threw a `NullPointerException` in `AudioRecord`'s constructor and no audio stream started. The AOSP sources for Android 13 through 16 all take that path; only Android 16 was run.

On the Android 16 emulator on 2026-11-05, with the helper answering as `com.android.shell` and before the app skipped silence or joined packets, `/v1/stream/audio` sent about 50 Opus packets a second. They were 12 bytes while nothing played and 156 to 286 bytes while Chrome played a 6 s 440 Hz tone, for those 6 s.

On the Android 11 test phone on 2026-11-06, `/v1/stream/audio` sent its header 1.6 s after the socket opened, and the foreground app stayed Chrome, so the shell activity's start and `removeTask` left it as it was. One packet came in 3 s of silence. A 0.6 s 440 Hz tone from a page came as 13 packets of 170 to 534 bytes over 0.64 s. The audio itself was not decoded. Closing the socket logged `audio reader stopped`.

## Screen hand-offs

Checked on the Android 16 emulator:

- 2026-10-29: a REST session brought a page on screen, released with `cleanup` false, and ended. Chrome stayed in front and unlocked, and the tab stayed open and unowned. A viewer took the screen, which woke it when it was off, and typed into the page. When its socket closed, nothing was cleaned up. The next session found the tab in `browser_targets`, read what the viewer typed, and got the note that a person had used the screen.
- 2026-10-30, with a swipe keyguard: a session that unlocked and released to a waiter left it unlocked, with a note saying so. The waiter's release with no one waiting locked it. A phone unlocked by hand stayed unlocked after a session took and released it.
- 2026-10-30: a session's wait for `person` true matched within 0.2 s of a viewer's take, and the wait for `person` false matched when the viewer's socket closed. Neither took the screen. A session that held the screen kept holding it through both waits and got it back when the viewer let go.
- 2026-10-22: a tab Chrome opened for `open_intent` became the caller's, and another session did not see it.

With a viewer on the Android 16 and Android 13 emulators on 2026-11-06:

- A person's take during an agent's touch swipe left no finger down in `dumpsys input` a second later, and the swipe got 409 `screen_taken`.
- A session's own read of its tab during a take hid the page. Before the take and 3 s after it the tab was `visible`. By the read after that it was `hidden`, and it stayed hidden after the viewer let go. Taps from a viewer stopped reaching the page, an `adb` tap too. With no call from the session, all five of a viewer's taps reached the page. This was the same on Chrome 133 and Chrome 109. Watching over raw DevTools showed reads were not the cause: `read`, `find`, `snapshot`, `console`, and listing left the tab `visible`, and the first cdp key or `evaluate` selected the placeholder. With PhoneAPI selecting nothing for a person, the same run on both Chromes on 2026-11-06 refused those two on the tab on screen with `screen_taken`, took a key on a hidden tab, refused an open, and the tab stayed `visible` through the viewer letting go.
- A tab held by an alert did not come back after a handover: cleanup went home, and the next session's launch of Chrome showed `about:blank`.
- A read of a tab held by an alert failed `content_unavailable` after 15.1 s.
- On Chrome 109 a `touch` tap with the tab switcher open returned ok and the click was lost, once. It did not happen again on 2026-11-06, in 35 tries on the Android 13 emulator: a tap on the tab Chrome had selected and on another, raw taps 0.08 to 1 s after `Page.bringToFront`, humanized or not, with six busy processes in the emulator and host load about 25. All 35 clicked. With the switcher open the tree has `tab_list_view` and no `WebView`. `Page.bringToFront` closes the switcher on Chrome 109, and the `WebView` is back within 0.28 s, so the check that the page is on screen passes for the right reason. Chrome 133 leaves the switcher up, and the tap is refused. So the lost click is not explained, and nothing was changed for it.
- `browser.dialog` with an alert up matched in 77 ms with the screen on, on Android 16. On Android 13 the same wait, with a 4 s limit, failed 503 `cdp_timeout` at 15.1 s. With the screen off the wait never turned it on, on both. There it did not match: it timed out at 4.0 s twice, and failed `cdp_timeout` at 15.1 and 15.6 s on Android 16 with a keyguard up.

## Video and audio streams

Measured on the Android 16 emulator on 2026-11-05, with the `C2SoftAvcEnc` encoder:

- On a still home screen a viewer got the key frame it joined with, redraws about 100, 300, and 700 ms after the last mirrored frame, then one frame every 4 s. A second viewer joining got a key frame within 10 ms, and so did the first. The idle frames were deltas of 1 to 30 KB, so the encoder kept refining the still picture.
- The encoder counts `KEY_I_FRAME_INTERVAL` in frames at the configured `KEY_FRAME_RATE`, not in seconds. With 10 s and `fps=60`, a page animating at about 29 fps got timer key frames every 20 s.
- adb buffered several MB per stream between the app and the client. A viewer that stopped reading, or read at 12 fps beside a 30 fps stream, never filled PhoneAPI's 4-frame queue. Ktor's ping timeout closed it after about 45 to 50 s instead. So the queue's drop and key-frame recovery did not run on the emulator. Its unit tests cover it.
- Audio capture failed before the stream started until the helper's stand-in context answered `getAttributionSource`, as [Audio capture](#audio-capture) describes. The Android 10 emulator has no submix capture.
- A 6 s tone played in Chrome came through as 102 messages: 101 Opus packets of three 20 ms frames, 60 ms apart, and one single frame sent as silence started. Nothing was sent before or after the tone. Apple's AudioToolbox decoder, which Safari's `AudioDecoder` uses, decoded them to 6.08 s of audio, sample for sample the same as the frames split back into 20 ms packets. Chrome's decoder takes packets up to 120 ms. Firefox was not checked.

Rotating while a viewer streamed, on the Android 16 and Android 13 emulators on 2026-11-06, without the display poll: new headers came 0.2 to 1.1 s after each of 90, 180, 270, and 0, and a viewer's tap at the screen's center reached the page at each, with a `stream,control` token. With the host loaded (load average 29), one header took about 8 s, and the log showed `relay release`, `The relay thread did not answer`, and `releaseOutputBuffer() is valid only at Executing states`. No viewer was closed. `wm size` changes no display mode, so no header came and `/v1/device` kept the physical size, while Chrome's viewport did change. The emulators have one display mode, so a real resolution change was not run.

## UiAutomation

On the phone on 2026-10-22, "UiAutomationService already registered" came from two calls connecting UiAutomation at once in one helper. Connecting is now serialized, and two `ui_snapshot` calls at once then connected once. The first snapshot right after connecting saw 0 nodes and 0 windows; the next, 9 seconds later, saw 30 nodes.

Measured on the emulators on 2026-11-05, reading a web field in Chrome through `ui/find`.

- Chrome 133 on Android 16 sent UiAutomation no `TYPE_VIEW_TEXT_CHANGED` for a web field, across every typing and `setText` run. Chrome 91 on Android 10 and Chrome 145 on Android 17 sent one for each change. UiAutomation's node cache keeps a node until an event names it, so Chrome 133 left it stale.
- `setText` into a field whose keyboard had settled read the old text from the cache for over 3 seconds, while `refresh()` on the same node gave the new text. Without clearing the cache, reads looked fresh only when the keyboard opening made Chrome's view send `TYPE_WINDOW_CONTENT_CHANGED` with the subtree flag. Clearing before each read gave the new text at once.
- Chrome 133 and 145 both log an empty list of accessibility services while UiAutomation is connected, and on Android 16 `dumpsys accessibility` shows no bound services. Chrome 133's source drops events no listed service asks for, and starts a 5 second timer to turn accessibility off. Chrome 145's filter reads the same yet its events arrive, so why 133 sends none isn't settled. Idling 8 seconds before typing changed nothing.
- A selector call straight after a page changed matched the old page's field from the cache and got `stale_ref` in 4 of 4 rounds. With reads limited to 500 ms of cache age it was 2 of 5, with the other 3 an honest `not_found` for a page still loading. A navigation that doesn't go through the tree can still land inside that 500 ms.
- The first read of a page Chrome has not built an accessibility tree for finds only Chrome's own views. The next read, a second later, found the field.
- The report that `ui/find` lost a field after typing until the next page load was not reproduced as described, in 5 fresh-helper trials and 47 rounds of clearing and typing before the cache age was added. The `stale_ref` above is the closest failure found. Once the keyboard had opened, no window was missing from any list `ui/find` read in those rounds. Keyboard reads weren't logged, so this says nothing about a window dropping out during one.
- Clearing the cache before every read took a 3,000-element page from about 25 ms to 900 ms for `ui/find` on Android 17, and a small page from 9 ms to 46 ms. With the 500 ms limit, polling that large page every 50 ms on Android 16 had a median of 9 ms and a 90th percentile of 691 ms. A snapshot past its own cache took about 700 ms there, and 19 ms on a small page. Android 10 took the same shape through the hidden `clearCache`: a 90th percentile of 590 ms and snapshots of about 570 ms, so that call does clear the cache there.
- Android 13 with Chrome 109, on the emulator on 2026-11-06: the hidden `clearCache` that takes a connection id works. Polling a 3,000-element page every 50 ms had a median of 12 ms and a 90th percentile of about 1,240 ms, and nothing was logged about a failed clear. `setText` read back right away in 6 of 6 tries, and typing then `clear` went wrong 0 of 8. After the last key, a read could show the old text and one about 60 ms later the new text. That is inside the 500 ms limit, so Chrome 109 looks to send the event that Chrome 133 doesn't.

On the Android 16 emulator on 2026-11-06, Chrome's page node sometimes collapsed. The node `ContentFrame` looks for, class `WebView`, became a childless `FrameLayout` described "Web View". Every touch tap and swipe then failed `content_unavailable` with nothing over the page, `keyboard_show` found no field, and `ui/find` found no web nodes. It was seen about 7 times, once 20 s into a tap's `waitMs`. The first tap after UiAutomation connected was refused the same way, though the node was not read then. Tries to bring it on did not: 75 s idle, 60 s with no reads, a sleep and wake, the menu and BACK, and a tap with no read after. A navigation and minutes of waiting did not clear it. The tab switcher and back, or a sleep and wake, did. So what starts it is not known. It was not seen on Chrome 109, with far fewer tries.

Chrome 156 on the Android 11 test phone did the same on 2026-11-06, in ordinary use: the node read `view desc:"Web View"` with no children. It happened on the first tab after a start of Chrome, on a new tab with Chrome coming forward from the home screen, and once between `keyboard_show` and the end of a typing call, on a tab that had had its nodes. A `touch` tap, and an `auto` tap from the screen holder, then failed 409 `content_unavailable` with nothing covering the page. Mode `setText` failed `no_editor`. `clear` with the keyboard returned 200 and did not clear: a field holding `seed text` ended as `seed textNew Text`. `cdp` and `cdp-mouse` input, reads, and keyboard mode without `clear` kept working. Six seconds of waiting, a `navigate`, and a `cdp` tap did not bring the nodes back. Opening another tab and closing it did, and with them back `touch`, `clear`, and `setText` all worked, and the nodes stayed for the next 8 s.

What starts it, found later on 2026-11-06 on the Android 16 emulator: it is Chrome turning off its accessibility nodes, about 5 s after the first read of a new tab, which matches the 5 second timer under [UiAutomation](#uiautomation). A tab opened with Chrome in front and read once was fine 3 s later and collapsed at 6 and 10 s, in every run. With no read at all it stayed whole at 6 s. Polling every 0.7 to 1 s held it off for 25 s, and a tab that had been through an activity start stayed whole, through taps and 16 s of silence.

The page is fine in that state. The tab reports `visible`, `Inspector.enable` sends no `Inspector.targetCrashed`, the renderers run, and `Accessibility.getFullAXTree` over DevTools returns the page. The node is a child of `compositor_view_holder` with no children and the same bounds, which are the toolbar's too, so it cannot place a touch.

What was tried from there:

| Try | Brought the nodes back |
|--|--|
| `performAction` `focus`, `accessibilityFocus`, `showOnScreen` | No, the node rejected them |
| `performAction` `select` | No, accepted and nothing changed |
| `setServiceInfo` again, then 3 s of reads | No |
| `Target.activateTarget` to another tab and back | Yes, 2 of 2, and they stayed |
| Starting Chrome's activity while it is in front | Yes, 3 of 3, in 0.3 to 0.9 s, and they stayed |

Chrome keeps the tab selected through an activity start, so PhoneAPI uses that, as tapping Chrome's icon would. A tap on a collapsed page then took 1.2 s and clicked, and the tab switcher on Chrome 133 still failed `content_unavailable`, with no start. `clear` read the page's node as an empty field, since it was the focused node and has no text, so it reported success and typed after the old text: on the same emulator a field holding `seed text` ended as `seed textNew Text`, as it did on the phone. With a node that takes no text counting as no field, and a browser text call with `clear` or `setText` bringing the nodes back first, all four ways through ended as `New Text`, in 0.7 to 5.6 s. Without a selector the keyboard did not come back after the activity start, so `auto` used `setText` and said so, and `keyboard` mode there would fail `no_keyboard`. `POST /v1/input/text` has no page to bring back, so it fails `text_not_cleared` and types nothing. `ui/find`, `ui/snapshot`, and `keyboard_show` still see no web nodes while the nodes are off.

`contentBounds` was also the one read that skipped the node cache's age limit, so a tab just brought forward could read as the page before it. That is the first tap refused in the first paragraph, with the node never read: a first touch on a new tab failed `content_unavailable` in 3 of 5 tries, and in none of 5 once it read through the limit. Chrome 109 did not collapse in these runs. Host load was 20 to 30 throughout, so these times are loose.

A tap by `ref` from `includeInvisible` on a page with `scroll-behavior:smooth` scrolled 8,455 px, waited for the scroll to end, and clicked, in 0.96 s, on the Android 16 emulator on 2026-11-06.

With the app killed and restarted beside a live helper, on the Android 13 and 16 emulators that day, `uiAutomationConnected` read false for as long as it was watched, 331 s on Android 16, though snapshots worked. The helper's connection outlived the app, and the new app was not told. No `UiAutomation idle` line came in over 200 s on Android 13, so whether it idles out then is not settled.

## The on-screen keyboard

Measured on the Android 10 and 16 emulators on 2026-11-05, typing into a web field in Chrome through Gboard (15.1 on Android 16).

- On Android 16, Gboard relabels its letters without an event that reaches UiAutomation's node cache. After a shift tap the shift key read "Shift enabled" while the cached letters stayed lowercase for over a second. Both `AccessibilityNodeInfo.refresh()` on each node and `UiAutomation.clearCache()` gave the new labels at once. Typing 10 mixed characters took about 5.0 s from the cache, 5.5 s with `clearCache`, and 7.4 s with `refresh` on each node. `clearCache` needs Android 14. Older releases use a hidden call, and refresh each node only if that fails.
- Gboard lists its suggestions among its keys. A suggestion can be one letter, and it has no view id, where a real key's id holds `key_pos_`. Tapping one enters the letter and a space.
- A keyboard still sliding up reports keys that aren't where it ends up. Tapping then lost the first letter in 3 of 6 runs on Android 16 on the old build.
- On Android 10, labels change before the redraw ends. Tapping as soon as they changed lost or swapped a letter in 2 of 8 runs. Waiting until they hold still for one poll lost none in 16.
- Read just after a letter, the keys can still show the shift that letter ended. Pressing shift then turns it back on, and pressing it again recovers. This happened in 1 of 8 runs on Android 16.
- `findFocus(FOCUS_INPUT)` in Chrome returns a `FrameLayout`, not the web field. The field is a focused, editable node below it.
- On Android 16, the keyboard's window can drop out of UiAutomation's window list for over a second while Gboard stays up, and `ImeTracker` logs no hide. It was seen about once in 15 clears, after a tap on the field that already had focus, with `CONTROLS_CHANGED` in the log. A shorter gap of about 0.4 s followed a `Delete`. In the gap, reads find no keys and no focused field. Treating that as an empty field skipped the clear.
- Final build: Android 10 typed wrong 0 of 8 and cleared wrong 0 of 8. Android 16 typed wrong 0 of 8 and cleared wrong 0 of 8, and cleared wrong 0 of 32 in a loop that read the text back. The same loop was wrong 2 of 12 before the gaps were handled. The old build on Android 16 typed wrong 5 of 6 and cleared wrong 2 of 6.

On the Android 11 test phone on 2026-11-06, Gboard typed mixed case and symbols. A 54-character string of letters, digits, and symbols was exact in 23.4 s, and a 12-character one took 4.7 s. Each printable symbol, typed alone in `keyboard` mode:

- All but two typed right. `^` and `|` failed 422 `unsupported_char`, though both are on the keyboard. They are on the More symbols page, where the keys read `circumflex accent` and `vertical line`, names `KeyMatcher.SPOKEN` does not have. Each key has a child text node with the literal character, which isn't clickable. Other names on that page, such as `grave accent`, `Percent sign`, and `Left curly bracket`, match.
- That page has no letter or digit keys, so `looksLikeKeyboard` refuses it. Gboard stays on it after a character typed from it, such as `%`. After `50%`, the next call failed in 5.1 s, with `no_keyboard` in `keyboard` mode and `no_editor` in `auto`. A failed `^` leaves it there too. The field took nothing in either case. Hiding the keyboard and focusing the field again brought back the letters.
- With the page's nodes present, `clear` emptied the field, in a 3.5 s call that typed 8 characters after it, `setText` worked, and `keyevent` typed `Key Ev3nt!`.
- `keyboard_show` with a selector raised the keyboard in 0.5 to 1.5 s, both times it was tried.

`KeyMatcher` now knows those two names, and `looksLikeKeyboard` counts every key that types a character, by literal or spoken label. Reading the literal character from a key's child node is not safe: the letter keys' children include the digit they give on a long press, so `q` has `q` and `1`. With that build on 2026-11-06, on the Android 16 emulator and on the Android 11 test phone, `a^b|c` was exact, and text after `50%` typed in both `keyboard` and `auto` modes. On the phone a 46-character string with every ASCII symbol was exact in 17.9 s.

The merged code typed the same cases exactly on the Android 11 test phone on 2026-11-07: `^`, `|`, `a^b|c`, `50%` then ` done` in `keyboard` mode, `5%` then ` Next 7` in `auto`, and a 46-character string of letters, digits, and every symbol in 18.4 s.

`keyboard_show` on the Android 16 emulator on 2026-11-06: without a selector, with a field focused, the keyboard came up in 1.5 s. With a selector, one try said `The keyboard did not appear`, two worked, and three failed `not_found`, the Chrome node collapse above being the likely cause.

## MCP SDK

PhoneAPI uses the Kotlin MCP SDK 0.16.0. A prototype with 0.15.0 on the Android 16 emulator showed, on 2026-10-20:

- `initialize` assigns a random session id. `notifications/cancelled` cancels the tool's coroutine at once. `DELETE` cancels every call in flight, and the session then answers 404.
- The id a tool handler sees, `ClientConnection.sessionId`, is the SDK's own, not the `Mcp-Session-Id` the client holds. PhoneAPI creates each session's transport and `Server` together, with `setSessionIdGenerator` returning an id it picked, so that id is in the tools' closure. Leases and tabs are keyed by it.
- A call cut off by `DELETE` comes back as HTTP 500 `Job was cancelled`, from PhoneAPI's error mapping. It should be a JSON-RPC error.
- The SDK's streaming mode answers a POST with an event stream that Ktor closes as soon as the transport hands the request off, before the result, so it does not work without more plumbing. In JSON mode the SDK sends a request's progress on the client's GET stream, and drops it when the client has none. So `/mcp` answers POSTs with JSON, refuses the GET stream with 405, and sends no progress.
- A tool ran to the end after its client dropped the connection, on stateless `/mcp` as well as with sessions. That matches the spec, which says not to treat a disconnect as a cancellation, so clients have to send `notifications/cancelled` to stop a wait. Legwork's Go SDK (v1.8.0) does that when a call's context ends, sends `DELETE` when the session closes, and opens the GET stream after `initialize`.
- Nothing from the SDK, session ids included, reached logcat in a debug build.

The SDK has a stateful transport with session ids, `DELETE`, and open and close callbacks. Since 0.16.0 its Ktor helper `mcpStreamableHttp` expires idle sessions and caps how many are open, but the transport does neither, and the helper still keeps the session table private. So PhoneAPI owns the `/mcp` route and drives `StreamableHttpServerTransport` itself, keeping expiry, the token check, and release in one place. The SDK makes ids with Kotlin's `Uuid.random()`, which uses `SecureRandom` on the JVM, so PhoneAPI keeps its generator.

SDK 0.16.0 answers a POST that repeats a request id with 400 `Invalid Request`, where 0.15.0 answered 200. That was seen on the Android 16 and Android 10 emulators on 2026-11-05, for an id sent twice in one POST. The transport's code treats an id still in flight the same way. An id can be used again once its call has finished.

Progress would need one of two things. The session's GET stream already carries it in JSON mode, but clients may skip that stream. Streaming POST responses, which every client must accept, would need PhoneAPI's own transport on the SDK's `Transport` interface, about 250 to 350 lines, owning session ids, headers, and errors. Wrapping the SDK's transport instead is smaller but depends on its internals.

MCP's stateless revision (2026-07-28) drops protocol sessions: servers that need state across calls mint handles that clients pass as tool arguments, as `session_start` does. SDK 0.16.0 does not speak that revision yet (its latest protocol version is 2025-11-25), so such clients cannot connect, but the tools would carry over.

## Wireless debugging pairing

Checked on the Android 13 emulator on 2026-11-06, through the notification from start to a running helper:

- Pulling down the shade leaves the "Pair device with pairing code" dialog open, and the pairing service stays up. The shade covers the code, though, so it has to be read first.
- Android folds an app's ungrouped notifications into one group in the shade, where each is a collapsed row. Two were enough. A notification with its own `setGroup` stays out of that group, and at high importance it sits at the top already expanded, even with no group summary, so "Enter code" shows with no expanding.
- A notification posted silently and then updated without `setOnlyAlertOnce` drops down over the dialog, about 2 to 4 seconds after the dialog opens.
- After a wrong code the dialog stayed open, and the code it then showed paired.
- Tapping another notification's row that opens an app closes the dialog.

On the Android 11 test phone on 2026-11-06, the same flow reached a running helper. mDNS found the dialog there, and the code went in through the notification. Android didn't group the notifications, with the listener, status, and pairing ones all up. A silent post updated to an alerting one showed in the shade but did not drop down over the dialog, with Do Not Disturb off. A new post, after cancelling the silent one, did drop down. Android 11's `HeadsUpController.updateHunState` reads as if the update should alert too, so the cause is unknown. PhoneAPI cancels and posts again.

On the Android 12 test phone on 2026-11-06, Do Not Disturb hid the pairing notification. Its policy included `SUPPRESSED_EFFECT_PEEK` and `SUPPRESSED_EFFECT_NOTIFICATION_LIST`, so the notification existed in `dumpsys notification` but showed neither as a banner nor in the shade. With Do Not Disturb off, a new post dropped down over the dialog and pairing finished through it.

The Android 13 emulator cuts a toast off at two lines, about 75 characters on its screen, so the Do Not Disturb warning is kept short.

`createNotificationChannel` on an existing channel changed its name on that phone and left a channel the user had turned off still off.

Turning off the listener's notification channel on the Android 13 emulator on 2026-11-06 removed the notification, and the system posted none in its place. The service stayed `isForeground=true`, the process stayed `fg-service` at `oom_score_adj` 200, and the socket kept answering. The Android 11 and 12 test phones did the same that day: the process stayed `fg-service`, and the system had posted no notification of its own when checked some seconds later.

Android turns wireless debugging off when the access point changes. On the Android 12 test phone on 2026-11-06, moving between rooms logged `Detected wifi network change. Disabling adbwifi.`. Android 12's `AdbDebuggingManager` compares the BSSID, so roaming on one network counts. It also trusts networks by BSSID: turning wireless debugging on at an access point it hasn't approved shows "Allow wireless debugging on this network?" and puts the setting back to 0. So each access point needs "Always allow" once.

On the Android 13 emulator that day, with the helper down on an unapproved network, the app's retries put that prompt up every 15 seconds without end, because each change to the setting reset the failure count. With that fixed, tapping Allow turned the setting on, which the app's settings observer saw, and the helper came back.

With the restart loop rewritten to tell its own writes from everyone else's, on 2026-11-06: on the Android 16 emulator with its network's trust removed (the `wifiAP` entries in `/data/misc/adb/adb_temp_keys.xml`, edited with `abx2xml` and `xml2abx`, then a reboot), the app's start turned the setting on, Android put it back to 0 within 80 ms and showed the prompt, the 15 s search found nothing, and the loop made no further attempt in the next 80 s. Ticking "Always allow on this network" and Allow turned the setting on, and the helper was registered 5 s later. On the Android 13 emulator, `mdnsd` stops about 12 s after the last search; killing the helper then gave a 15 s search that found nothing, the setting off for one second and on again, adbd listening on a new port at once, and the helper registered 19 s after it died, with the setting still 1. On the Android 16 emulator `mdnsd` kept running for over a minute after a search, so a helper killed there was back in 2 s without a cycle.

On the Android 11 test phone the same day, with a dev build paired beside the release app and the setting turned on over `adb shell` first, the loop ran its six attempts, each one search, one off and on of the setting (adbd logged `adbwifi started` on a new port each time), and one more search, then gave up with the setting still on. No search found the port: the first search discovered two `_adb-tls-connect` records, the phone's own and one from an emulator elsewhere on the LAN, `NsdManager.resolveService` on the emulator's record never answered, and every later resolve, the phone's own included, failed at once with error 3 (`FAILURE_ALREADY_ACTIVE`) for the rest of the process's life. Android 11 resolves one service at a time and has no `stopServiceResolution` (API 34), so one hung resolve of a record that isn't this phone's wedges the walk. `pairingPort()` used Kadb's own mDNS then, and found the pairing dialog there.

With the port walk changed to resolve only records named for this phone's serial, on the same phone later that day: a helper started over USB registered and the app recorded `ro.serialno` (`ZY227DX4L7`) through it. A second launch for the same package logged "already running" and exited. With wireless debugging on and the helper killed, the search found the phone's own record and adbd listening on it 100 ms after the death, with the emulator's record still on the LAN and never resolved, and the helper was registered 4 s after it died. The `_adb-tls-connect` record was `adb-ZY227DX4L7-...`, as the filter expects; the `_adb-tls-pairing` record's name was not read.

Kadb's `KadbMdnsAndroid` browses `_adb._tcp`, `_adb-tls-connect`, and `_adb-tls-pairing` by default. Below API 34 it calls `resolveService` on every record of all three as it is found, with no queue, as its bytecode for 2.1.4 shows. So both `pairingPort()` and the pairing notification's five-minute watch could wedge NsdManager for the whole process, connection searches included. All three searches now share one walk and PhoneAPI no longer uses Kadb's mDNS. On the Android 17 emulator on 2026-11-06, with the walk in place: with the serial known, `pairingPort()` found the pairing dialog and paired, so that record was named for the serial. A bare TCP connect to the pairing port before pairing did no harm there. With no serial stored, the notification changed to asking for the code once the dialog opened, and went back when it closed. No API below 34 has run this walk.

On the Android 12 test phone on 2026-11-06, browser calls found no wireless debugging port while adbd was listening. The setting was 1, `/proc/net/tcp6` showed a socket owned by uid 2000 on the port mDNS had returned earlier, and mDNS searches found nothing for their full 15 s. In the log, a search found the service only after a toggle, when `mdnssd start-service` failed with `Service already running`. NsdService ran `mdnssd stop-service` 10 s after each search, and every later search started the daemon itself and found nothing, so adbd's registration seems to end with that stop.

SELinux keeps `service.adb.tls.port` from the app on that phone, and from the shell on the Android 11 test phone: it reads as empty, with an `avc: denied` for `adbd_prop`. It read as empty from the shell on the Android 12 test phone too, with adbd listening. So an empty value from `adb shell getprop` says nothing about the port.

The Android 13 emulator showed the same thing on 2026-11-06. After one search, `mdnsd` stopped by itself, `getAdbWirelessPort` still gave the port, and the next search found nothing for 15 s. Turning the setting off, then on a second later, made adbd register again, and the next search found it. Stopping `mdnsd` with `ctl.stop` is not the same: searches then find nothing even after adbd registers. On the Android 16 and 17 emulators, mDNS doesn't use `mdnsd`. Android 17 still found the port with it stopped.

`IAdbManager.getAdbWirelessPort()` does give it. `adb shell service call adb 10` on the Android 12 test phone returned 41609 while mDNS found nothing. It needs `MANAGE_DEBUGGING`, which the Shell package holds on Android 11, 12, and 13. The helper reached it on the Android 13, 16, and 17 emulators.

A release build with the helper no longer exempt from R8, signed with the debug key, on the Android 16 emulator on 2026-11-06: the helper registered (protocol 7), `/v1/device` listed every capability, and a session's wake, `ui/snapshot` (a full launcher tree once UiAutomation had connected; the first snapshot before that connect came back with no windows), screenshot, injected tap, and clipboard listener registration all ran, with nothing about a missing class in logcat. A second launch for the same package logged "already running" and exited while the first, running as shell, kept the abstract name.

On the Android 16 emulator on 2026-11-06, `ro.serialno` was `EMULATOR37X2X13X0` and adbd's record was `adb-EMULATOR37X1X11X0-VVtpcr`. The emulator is 37.2.13, so the record's name likely dates from an older emulator; the shell can't read the stored name to confirm it. With the port changed and the helper down, the search skips that record as another phone's, and the restart loop turned the setting off and on every 32 s with adbd listening. So on that image a helper lost after the port changes does not come back alone, and one started by hand lost its first race after an app update: the new launch said `already running`, and the old helper left 2 s later with `APK is gone`. A real phone's serial does not change this way.

A wrong code sent once on the Android 16 emulator that day failed once in 0.2 s, with `Could not exchange peer info`. The dialog stayed open with the same code, and the app's pairing kept working. The broadcast's result code was 0 with the failure in its text.

## The listener's socket

`LocalServerSocket.close()` does not wake a thread in `accept()`, on the Android 13 and 16 emulators on 2026-11-06. After `ListenerService` stopped, the next connection was accepted and never answered, and each cycle left one more connected socket in `/proc/net/unix`. The abstract name stays bound until that happens. So a stop and a start with no client between failed: `Failed to open the abstract socket`, `java.io.IOException: Address already in use`, with the service still foreground and nothing serving. A later start worked. With a client in between, three of three cycles came back.

`Os.shutdown` with `SHUT_RDWR` on the listening socket's descriptor does wake that `accept()`, on the Android 10 (kernel 4.14), 13, and 16 emulators on 2026-11-06. Each ran three stops and starts with no client between, where `/proc/net/unix` showed the name gone within a second of `am stopservice` returning, and three more back to back. The host was overloaded, so that time is only an upper bound. The API answered every time, nothing failed to bind, and on Android 16 a client connecting after a stop failed at once rather than hanging. These ran on throwaway AVDs of the same system images, since other work held the named ones.

## Unlocking

On the Android 16 emulator on 2026-11-06, with the lock screen turned on, a swipe keyguard went away through `wm dismiss-keyguard` in 3.6 s with no swipe. A PIN unlock worked three of three times in 4.2 to 5.2 s, and the PIN did not reach the log. With the helper down, `POST /v1/device/wake` failed `wake_failed`, since Android dropped `WakeActivity`'s start from the background.

The PIN field on the Android 10 and 17 emulators on 2026-11-06 was `com.android.systemui:id/pinEntry`, a visible, non-clickable node with the password flag. Its text holds one character per digit, as `•` on Android 17, and is empty when the field is. Delete is `delete_button`, labelled Delete. The Android 17 count followed each digit and delete tap, one at a time, though a read can come before the tap shows. On both, with two digits left in the field, `POST /v1/device/unlock` cleared them and unlocked, in 6.2 s on Android 17 and 10.6 s on Android 10. On Android 17 the PIN reached no PhoneAPI log. With three emulators sharing the machine, at a load average near 30, a cold unlock often failed with no keypad: on Android 17 the keys took 1.9 to 4.1 s to appear after `wm dismiss-keyguard`, past the 1.5 s the bouncer gets. That failure types nothing, and it is the same wait as before the field check.

## App launch and copy

On the Android 16 emulator on 2026-11-06, `app_launch` through the helper brought Settings forward in 1.5 s. Without the helper Android dropped the start, and the call failed 503 `helper_unavailable` after 5 s. A copy with `KEYCODE_COPY` in Chrome returned the selected text. An `open_intent` tab was claimed within 2.2 s, under the URL match main has since replaced. Chrome 133 returned `loaderId` from `Page.getFrameTree`, sent the `load` lifecycle event, and sent `requestWillBeSent` again with the same id for a redirect. A debug start logged a StrictMode `DiskReadViolation` from `getFilesDir` in `AdbEndpointFinder`'s constructor, on the main thread. A helper registering while the app starts threw `lateinit property graph has not been initialized` across binder, and it got in on the app's next try.

Run again on the Android 16 emulator on 2026-11-06, once `app_launch` decided from whether PhoneAPI is the top app, with the helper stopped by PhoneAPI's own switch and its foreground service running:

| PhoneAPI | Screen | Result |
| --- | --- | --- |
| In front | On | `ok` true, `backend` `activity`, in 0.25 to 0.44 s, with `wait` true and false, and Settings came forward |
| Behind the launcher | On | 503 `helper_unavailable` in 0.25 to 0.36 s, and Settings did not start |
| In front | Off | 503 `helper_unavailable`, and the screen stayed off |

So the foreground service does not count as in front, and a process that is on top with the screen off does not either. Without the helper nothing reports the foreground app, which is why such a start returns without waiting for it. Through the helper, a cold Settings took 10 s on the loaded host, and `ok` true.

`getFilesDir` stats the directory on every call, not only the first, so a second call on the main thread was what StrictMode reported. With one call left, the app logged no StrictMode line on the Android 10, 13, and 16 emulators on 2026-11-06, from a clean install through pairing and six listener restarts, and none on an Android 16 cold start. The same logs held up to 502 StrictMode lines from other processes, such as the Google app, the dialer, and `system_server`, so filter by the app's pid.

## Browser waits

On the Android 10 emulator (Chrome 91) on 2026-11-06, with a wait's events read through `CdpSession.listen`:

- `browser.lifecycle`, `browser.request`, and `browser.url` together matched across a navigation to a page with 300 images, in six runs of six.
- `browser.newTarget` matched a page that was already open, in about 70 ms. The build before the change did too. So Chrome's `Target.targetCreated` events after `Target.setDiscoverTargets` include open pages, and PhoneAPI counts them as new.
- `browser.element` in a wait that spans a navigation failed with `cdp_error` "Could not find node with given id", in six runs of six, on both builds.
- With a hidden tab's renderer killed, a call got `tab_reloaded` and the next one worked, through a listener held for `Inspector.enable`.

On the Android 16 emulator (Chrome 133) on 2026-11-06, with a wait's set-up and looks ending at its limit, and the host under heavy load (load average about 30):

| Page | Condition | Limit | Result |
| --- | --- | --- | --- |
| No dialog | `browser.dialog` | 1500 ms | Timed out at 1518 ms |
| No dialog | `browser.dialog` | 300 ms | Timed out at 1066 ms, since set-up alone took about that long |
| Held by an alert | `browser.dialog` | 4000 ms | Matched at 2601 ms |
| Held by an alert | `browser.url` | 4000 ms | Timed out at 4102 ms |
| Held by an alert | `browser.element` | 4000 ms | Timed out at 4088 ms |
| Held by an alert, screen off | `browser.dialog` | 4000 ms | Timed out at 4002 ms |
| Chrome under `am freeze` | `browser.dialog` | 3000 ms | Timed out at 3078 ms |
| Chrome under `am freeze` | `browser.url` | 3000 ms | Timed out at 3018 ms |

Before this, a `browser.dialog` wait on a page that did not answer failed `cdp_timeout` at about 15 s, as [Screen hand-offs](#screen-hand-offs) says. The other rows were not run on the build before. After the alert was dismissed, and after `am unfreeze`, a wait on the same tab matched in under 1 s. With `timeoutMs` 0, `browser.url`, `browser.element`, and `browser.settled` on a page that had loaded all matched, in 170 to 990 ms, so a look fits the 1 s a short limit gets.

## Still to check on the phone

- That a browser call reconnects after DevTools' idle disconnect on the Android 12 test phone, using the helper's port lookup, and that a helper restart with no mDNS record recovers by turning the setting off and on. Both worked on the Android 13 emulator. Reaching the Android 11 and 12 phones needs a release build, since a dev build beside a running release helper doesn't start its own.
- That the restart loop recovers a lost mDNS record in one start on a phone, as it did on the Android 13 emulator. On the Android 11 test phone the record was present, so the search found it at once and the off/on cycle was not exercised with the serial filter in place. The one-prompt refusal on an unapproved network was checked only on the Android 16 emulator, since clearing a network's trust needs root. Giving up with the setting left as it was found was checked on the Android 11 test phone.
- What happens when mDNS misses the pairing dialog, and `pair()` then looks for the port itself. mDNS found the dialog on Android 11, 12, and 13.
- That turning off the listener's notification leaves the service foreground on Android 10, and whether Android posts its own notification in its place. Android 11, 12, and 13 were checked. The listener's text says it can be turned off.
- How long a large page takes to read past the cache on a phone. The emulators took about 900 ms for 3,000 elements.
- What a plain open of a PDF does on a Chrome that shows PDFs in its viewer. Chrome 145 on the Android 17 emulator saved it when opened on its URL, though it showed its viewer on 2026-11-05.
- That `PdfReader.ownPage` is not caught by a commit still to come on the phone, as it was 3 times in 25 on the Android 16 emulator.
- That the PIN field and delete key read the same on the Android 11 and 12 phones and any OEM keyguard: a visible node with the password flag whose text length is the digit count. An unlock refuses when no such field is found, so a keyguard that hides it, as a PIN auto-confirm or PIN-shape setting may, can't be unlocked with a stored PIN. Also a real cancelled entry cleared on the phone, and whether 1.5 s covers its bouncer coming up.
- That the shared mDNS walk finds the pairing dialog and the connect port on the Android 11 and 12 phones, below API 34, where a hung resolve can't be cancelled and `FAILURE_ALREADY_ACTIVE` is what a search waits out. Only the Android 17 emulator has run it. With another device's record on the LAN and the serial known, its record should never be resolved. With the serial not known yet, as on a first pairing, every record is, so a hung foreign one still blocks that search. Also a bare connect to the pairing port leaving the dialog working there, which `pairingPort()` does.
- That PIN unlock works on the phone, which has only run it on emulators with these waits. Also how long a keyboard read takes there with `refresh` on each node, and whether Android 11's Gboard goes stale as Android 16's does.
- How long a tab switch takes on the phone, and whether 2 seconds always covers it. A `touch` tap is refused with `not_visible` past that.
- That Chrome on the phone opens an incognito tab for `OPEN_PRIVATE_TAB` from the shell user and lists it with its own `browserContextId`, and whether Chrome's "Lock Incognito tabs when you leave Chrome" setting blocks a tab after cleanup sends Chrome home. Also that the viewer shows an incognito tab black there, as on the emulator, and that the phone's Chrome has the Incognito Screenshot flag and it makes the viewer show the tab.
- Whether a tab opened while Chrome is in the background becomes Chrome's selected tab, as it does on the Android 16 emulator.
- That the shell user can clear the clipboard through `IClipboard.clearPrimaryClip` and read `getPrimaryClipDescription` on Android 11.
- That a session finds the clipboard empty on Android 11 after a person copied something, and that the clear leaves no toast or visible change.
- That clipboard images go both ways on Android 11, checked so far on the Android 10 and 16 emulators. Also that the helper can read an image another app copied, which the emulators' Chrome would not put on the clipboard.
- That the viewer's copy and paste work from a real keyboard in Chrome, Safari, and Firefox, including the second Cmd+C that Safari and Firefox need when the page guessed text and the phone held an image.
- How many hidden tabs Chrome keeps loaded on the Moto G Power before it unloads them, to check the cap of 8.
- Whether a release build logs anything from the SDK, such as session ids. A release build uses a different logging backend from the debug build checked above.
- That binding Chrome's Custom Tabs service works on a real Android 12 or later phone, and does not change anything visible on the Android 11 test phone.
- That Chrome 155 still sends `Inspector.targetCrashed` on `Inspector.enable` for a tab it unloaded.
- Whether the first `ui_snapshot` after UiAutomation connects can come back empty, as one did, and should wait for windows.
- Whether a hidden tab's timers also stop while Chrome itself is in front, with the tab behind another, or only while Chrome is in the background.
- That `show` brings a background tab on screen, including when Chrome is behind another app, and that `shown` and `pending` match what is on screen.
- Whether Android 11 applies a change to `cached_apps_freezer` as quickly as Android 16 does.
- That a sound comes through whole on Android 11, without its start or end clipped, once decoded. That checks the Opus encoder's output timestamps against its input.
- That a still screen on the Moto G Power looks sharp after scrolling, with the relay's settle redraws in place of `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, and sends a frame every 4 s.
- That the viewer in Firefox plays the 60 ms Opus packets the phone sends.
- That a viewer on a slow link pauses rather than smears, and catches up on a key frame. adb's buffering kept the emulator from reaching that path.
- How long a long PDF takes to read on the phone, against DevTools' 15 s call limit. Also `browser_read` end to end through PhoneAPI on Android 17, which was checked only over raw DevTools. Also a PDF from a site whose service worker answers navigations, which may answer before DevTools can pause it.
- Whether a first-run or sign-in screen keeps Chrome from opening its DevTools socket after a tab open starts it.
- That a card number in a toast or a window title reaches `WS /v1/events` as `[card number]`, and the rest of the text is unchanged.

## Checked on the phone

On 2026-11-06, a dev build of `main` at 4059efb ran beside the release app on the Android 11 test phone, with Chrome 156.0.8078.73 and no secure lock screen, for ordinary use through REST and MCP. Failures are in the sections above, and only what passed is listed here.

- `phoneapi-dev setup --no-build` installed, paired, and reported the helper running in 18 s over USB. `/v1/device` listed every capability but `ui.stableIds`.
- Pairing beside the running helper: `uiautomator dump` can't run, so PhoneAPI's own `ui/snapshot` read the code and port for `PAIR_ADB`, whose result was `Paired. Starting the helper.`. `am start` of `.SubSettings` is refused for the shell on Android 11, so the way in is `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` and the Wireless debugging row. A tap on a list still moving only stops the list, so wait for `idle` first.
- Wake, lock, and state. `device_lock` turned the screen off, and with no keyguard set the state stayed `locked` false. `device_unlock` said `The device was not locked`.
- `ui/snapshot`, `ui/find`, `ui_act`, screenshot (PNG, 0.8 to 1 s), tap by selector and point, swipe, key, and waits for `window`, `node`, `idle`, `screen`, `keyguard`, and `ime`.
- Clipboard text: write, read, paste, and copy of a selection, with `changed` true.
- The screen lease between two REST sessions: a waiter got the screen at once on release, and took it from an idle holder at 30.06 s, each with the cleanup note. `session_start` and `session_end` too.
- `/v1/events` delivered `window.changed`, `ui.changed`, and `screen.on`.
- The viewer: `?access_token=` gave a cookie and a redirect, and the cookie opened `/viewer`, and got 404 on `/v1/device`. An `Origin` from another host got 404 on the upgrade. Video at `maxSize` 720 was H.264 High, 338x720, first frame at 0.8 s, and 27 of 29 frames decoded in `ffprobe`. The page in a browser played it with WebCodecs at 600x1280, and after `screen_release` with `cleanup` false, Take over and a click on the canvas tapped the page's button. A read by the session during a take hid the tab, as on the emulators, and taps then landed on the placeholder.
- Browser, over REST and MCP, on a local test page, example.com, and en.wikipedia.org: open, `read`, `find`, links, outline, snapshot, embedded data by path and `*`, `evaluate`, `tap` with `cdp`, `cdp-mouse`, and `touch` (`isTrusted` true each time), swipe with `touch` and `cdp`, text with `touch` and `cdp`, key, console, `capture` with `response-1` read by path and `find`, close, and waits for `browser.settled`, `loaded`, `element`, `url`, `request`, and `networkIdle`. An open with Chrome stopped took 3.3 s and showed no first-run screen. With it running, an open took 0.5 s.
- `chrome-input-check` on Chrome 156: all ten checks gave what the script expects, in 28 s.
- Helper restart over wireless debugging. With the setting on, the helper was registered 3.4 s after `kill`, and browser calls worked. With the setting put to 0 by hand and the helper running, 12 s passed with the helper up and the setting left at 0. After a `kill` from there, the app set it to 1 at once, and the helper was registered in 5 s. The setting then stays 1 while the helper runs, as [setup.md](setup.md) says.
- One swipe sent right after `BACK`, as the window changed, returned 200 with `ok` false and `The system cancelled the gesture`. The error table's 409 `gesture_cancelled` did not come.

Not run that day: PIN unlock, since no PIN is stored for the dev build; `device_orientation`; incognito tabs; `app_clear`; clipboard images; `browser.newTarget`; `browser.dialog`, a known failure on the emulators.

On 2026-10-22, with v0.3.0 and then a dev build beside it, the phone's agent and adb checked:

- Tabs a request opened were gone in the next request, from when REST calls held the screen for one call.
- A session's own tab stayed on screen while another session opened a tab, so switching back works on Chrome 155.
- `session_conflict` both ways, `screenHolder` as `you`, `agent`, `parent`, and `subagent`, and the cleanup and waited notes.
- Restarting the dev helper closed both test tabs a live session had, since the startup sweep ran on every helper connection. It now runs once per app start. The release build's helper also restarted during cleanup, when the USB connection dropped, and its app relaunched it within 30 seconds.

From the 2026-11-06 audit fixes, what the emulators could not settle. The rest was run that day on the Android 16 emulator, and part of it on Android 13. The sections above give the results, including the checks that failed.

- Android 10 and 11 hardware: a frozen Chrome's handshake on the Android 10 USB path; `wm dismiss-keyguard` on Android 10 and 11; rotation on Android 10 and 11 and viewer taps on Android 10, since the Android 10 emulator has no video; Android 10 taps in the viewer opening no audio socket.
- Audio: restarting for a second viewer on Android 11 and later. On the Android 13 emulator each viewer got a header and one packet and the reader stopped cleanly, but the tone never started. The Android 11 audio start: how long the first one after a boot takes, what a locked phone does to the wait, and which of `setProcessImportant` and the activity lets `startRecording` attach.
- A real resolution change while streaming, which needs a phone with more than one display mode.
- Chrome 145: `loaderId` from `Page.getFrameTree`, and the PDF takeover. On Chrome 133, whether `Runtime.callFunctionOn` works on a `DOM.resolveNode` handle in the stand-in and `Page.navigate` returns only after the fulfil were not looked at apart from the reads that depend on them. A site whose service worker answers the navigation itself. A quick PDF through Chrome 133's viewer: only the 17 s one was seen to return text, since the two `url` reads of a quick one were cut off in the test's output.
- A release and a dev helper side by side, with the UiAutomation contention, which needs a release build.
- UiAutomation idling out after the app process is killed and restarted, as under [UiAutomation](#uiautomation).
- The `_adb-tls-pairing` record's name on Android 11. With the serial known, the pairing search now resolves only records named `adb-<serial>-…`, so a different name there means it finds no dialog.
- `chrome-input-check` on a newer phone. On the Android 16 emulator its hidden checks passed, then `monkey` refused to run (`SYS_KEYS has no physical keys`), so the checks with Chrome on screen need a phone.
- Added after these checks ran, on code they did not test: an `open_intent` tab claimed within 2 s: that the start names `com.android.chrome` as what took a URL with no package named, with Chrome in front and behind, and that the new tab has no `openerId`, including across a redirect; that `app_launch` of a running Chrome opens no new tab.
- Opening a tab, past what [PDFs](#pdfs) says of Chrome 156 on the Android 11 test phone: `Page.downloadWillBegin` for a Chrome 133 PDF save, a PDF sent as an attachment, and Chrome 145's viewer, and whether the viewer's `chrome-native://pdf/link` commit sends `Page.frameNavigated` within 2 s of it. Without that event, a save that leaves the tab on `about:blank` reads as a server that has not answered, as it did before. A Chrome 133 save closing the tab within 2 s of the event, no Drive viewer coming forward, and one file saved. A server that takes over 12 s: `loading` true, then `browser.settled` matching once it has loaded. A hidden tab's `document.readyState` read in an isolated world for `browser.settled`, with `Page.loadEventFired` arriving for a load that finishes after it, and on a paused tab.
- `app_launch` without the helper on Android 10 and 11 and on a phone, and `ActivityManager.getMyMemoryState`'s importance there: it was read only on the Android 16 emulator. A browser wait's limit on the Android 10 USB path.
- Touch on a page Chrome turned the nodes off for, as under [UiAutomation](#uiautomation): whether starting Chrome shows a page a blur or `visibilitychange`, whether an activity start brings the nodes back on a phone, where opening and closing a tab did, and Chrome 145. Whether `ui/find` and `keyboard_show` should recover too, and whether the keyboard should be brought back after the start.
- The lost click on Chrome 109 with the tab switcher open, under [Screen hand-offs](#screen-hand-offs), which did not come back in 35 tries.
- Starting activities, as [Starting activities](#starting-activities) describes: all of it on phones other than the Android 11 test phone, since an OEM can change this interface; the wait running out at 20 s, which no emulator reached; an incognito open, whose start runs only on a Chrome that reports a tab's profile (Chrome 91 and 109 stop before it, with `Chrome did not say which profile a new tab is in`).
