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

Checked through PhoneAPI on the Android 16 emulator, Chrome 133 with `AndroidOpenPdfInline` on, on 2026-11-05, against 22 public PDFs and the scoring in `pdftotext`. Word recall against `pdftotext` was 95 to 100% for papers in one and two columns, menus, the RFC, a Census report whose table pages carry `/Rotate 90`, an IRS form, a Supreme Court opinion, slides, a designed annual report, and Chinese and Arabic reports. Running headers and footers, which `pdftotext` keeps, account for most of the rest.
- Bookmarks gave the outline where a PDF has real ones: every bookmark of the papers, the RFC, and the RISC-V manual came out as a heading at its depth, and 803 of the RP2350 datasheet's 1168, the rest being past the 2,000,000-character cap. Census and a menu had bookmarks that name nothing on the page, so they used the size and numbering rules.
- The first read of the 1371-page RP2350 datasheet took 12.4 s in 2 s steps; a find after it took 0.4 s.
- A menu whose site sends `Cross-Origin-Resource-Policy: same-origin` failed the second fetch with `net::ERR_BLOCKED_BY_RESPONSE`. The RISC-V manual's release URL is sent as an attachment, so Chrome saved it and the tab was left on `about:blank`. The 23 MB and 55 MB files were refused as over 20 MB.
- A scanned manual has no text, and a gazette whose glyphs are drawn as outlines has none either. An Arabic card whose font maps glyphs to the wrong Unicode reads as fragments, as it does in `pdftotext`.
- The second fetch went out as `Sec-Fetch-Site: cross-site`, `Sec-Fetch-Mode: no-cors`, with no `Referer` and no cookie set `SameSite=Lax`, `Strict`, or with no SameSite, while the navigation that showed the PDF sent all three.
- A find for "the" in the datasheet's 2,000,000 characters took PhoneAPI from 190 MB to 1.3 GB resident in a second, and the low-memory killer stopped it. Kotlin's `Regex.findAll` makes a `Matcher` for each match, and Android's ICU-backed `Matcher` keeps its own copy of the text in native memory, so 10,000 kept matches held about 40 GB of copies before the kill. Walking one `Matcher` kept the peak at 182 MB.

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

## Audio capture

`AudioRecord` names the app that records. Android 11 and 12 take the name from `ActivityThread.currentOpPackageName`. Android 13 and later ask the current application for its `AttributionSource` instead, so the helper's stand-in application has to answer that too. Without it the helper threw a `NullPointerException` in `AudioRecord`'s constructor and no audio stream started. The AOSP sources for Android 13 through 16 all take that path; only Android 16 was run.

On the Android 16 emulator on 2026-11-05, with the helper answering as `com.android.shell` and before the app skipped silence or joined packets, `/v1/stream/audio` sent about 50 Opus packets a second. They were 12 bytes while nothing played and 156 to 286 bytes while Chrome played a 6 s 440 Hz tone, for those 6 s.

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
- Audio capture failed before the stream started until the helper's stand-in context answered `getAttributionSource`, as [Audio capture](#audio-capture) describes. The Android 10 emulator has no submix capture.
- A 6 s tone played in Chrome came through as 102 messages: 101 Opus packets of three 20 ms frames, 60 ms apart, and one single frame sent as silence started. Nothing was sent before or after the tone. Apple's AudioToolbox decoder, which Safari's `AudioDecoder` uses, decoded them to 6.08 s of audio, sample for sample the same as the frames split back into 20 ms packets. Chrome's decoder takes packets up to 120 ms. Firefox was not checked.

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

## Still to check on the phone

- How long a large page takes to read past the cache on a phone. The emulators took about 900 ms for 3,000 elements.
- That typing with mixed case and symbols, `clear`, and PIN unlock work on the phone, which has only run them on emulators with these waits. Also how long a keyboard read takes there with `refresh` on each node, and whether Android 11's Gboard goes stale as Android 16's does.
- How long a tab switch takes on the phone, and whether 2 seconds always covers it. A `touch` tap is refused with `not_visible` past that.
- That audio still starts on Android 11 with the helper's `getAttributionSource` override in place. Android 11 has no such method, so it is never called there.
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
- That the viewer in Firefox plays the 60 ms Opus packets the phone sends.
- That a viewer on a slow link pauses rather than smears, and catches up on a key frame. adb's buffering kept the emulator from reaching that path.
- What the phone's Chrome does with a PDF by default: save it, as Chrome 133 on the Android 16 emulator did, or show it in its viewer, as Chrome 145 on the Android 17 emulator did. Reports say the viewer needs Android 12 or later, so the Android 11 test phone likely saves it, and `browser_read` has no tab to read. Where it shows, how long a long PDF takes to read on the phone, against DevTools' 15 s call limit. Also `browser_read` end to end through PhoneAPI on Android 17, which was checked only over raw DevTools. That the phone's Chrome lets the Fetch domain answer a tab's navigation to a PDF with PhoneAPI's page, and serves that page's `force-cache` fetch from the cache, as both emulators' Chromes did. Also a PDF from a site whose service worker answers navigations, which may answer before DevTools can pause it.
- How long Chrome takes on the Moto G Power to open its DevTools socket after a tab open starts it, against the 10 s limit, and whether a first-run or sign-in screen keeps it from opening one.
- That a card number in a toast or a window title reaches `WS /v1/events` as `[card number]`, and the rest of the text is unchanged.
- That a tab Chrome unloaded still reloads for a read when `readyState` is read in an isolated world right after `Page.reload`, before the new document exists.

## Checked on the phone

On 2026-10-22, with v0.3.0 and then a dev build beside it, the phone's agent and adb checked:

- Tabs a request opened were gone in the next request, from when REST calls held the screen for one call.
- A session's own tab stayed on screen while another session opened a tab, so switching back works on Chrome 155.
- `session_conflict` both ways, `screenHolder` as `you`, `agent`, `parent`, and `subagent`, and the cleanup and waited notes.
- Restarting the dev helper closed both test tabs a live session had, since the startup sweep ran on every helper connection. It now runs once per app start. The release build's helper also restarted during cleanup, when the USB connection dropped, and its app relaunched it within 30 seconds.
