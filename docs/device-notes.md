# Device notes

This is what Android, Chrome, and the MCP SDK were seen to do, which FingertipsMCP's design depends on, and what is still to check. Each section ends with what is still to check on its topic, so an open item goes there, not in a list of its own. [sharing.md](sharing.md) describes the behavior built on it. Each check gives its date. Chrome and Android change between releases, so a check is worth running again after either updates. `scripts/chrome-input-check` checks Chrome's input behavior against an attached phone, as [development.md](development.md) describes.

The emulators run Android 10 with Chrome 91, Android 13 with Chrome 109, Android 16 with Chrome 133, and Android 17 with Chrome 145. Chrome 91 and 109 are older than FingertipsMCP supports, as [development.md](development.md#which-chrome-to-test) says, so what these notes say of them is history. The emulator's Chrome is a debug app, and the helper often runs there as root after `adb root`. The test phone runs Android 11 with Chrome 156.0.8078.79.

## Last phone run

On 2026-11-08, a dev build of `main` at 3fd0bef ran on the Android 11 test phone, with Chrome 156.0.8078.79, for closing a tab and `browser_key` with Shift over `cdp`. On 2026-11-07, a dev build of `main` at 9e047f7 ran on the Android 11 test phone, with Chrome 156.0.8078.79. A dev build of `main` at 4a18edd ran there earlier that day, for what had landed since 2acb53e and raw DevTools checks of Chrome's target events. A dev build at 2acb53e ran there earlier that day, to recheck what had changed since 4059efb. On 2026-11-06, a dev build at 4059efb ran there beside the release app, with Chrome 156.0.8078.73 and no secure lock screen, for ordinary use through REST and MCP. On 2026-11-08, a release build of `main` at bbf6f1a, with R8, signed with the debug key and installed as `com.fingertipsmcp`, ran on the Android 16 emulator with Chrome 133 and the Android 10 emulator with Chrome 91. It ran the helper, MCP's 41 tools, sessions, UI snapshot, find, and taps, screenshot, clipboard, tokens, and logcat on both, and video and every browser call on Android 16 (Android 10 took only open, read, and find). Only `GET /viewer` failed, with 500 `internal`: the page was looked up relative to a class R8 had moved to another package. It now uses an absolute path, and the rebuilt APK passed the same list. What a run finds goes in the section for its topic, and a list of what passed goes in the run's PR.

Not run on 2026-11-06: PIN unlock, since no PIN is stored for the dev build; `device_orientation`; incognito tabs; `app_clear`; clipboard images; `browser.newTarget`; `browser.dialog`, a known failure on the emulators.

## Chrome tabs in the background

### Selecting tabs and popups

- Chrome on Android ignores `Target.createTarget`'s background flag and switches to a new tab while Chrome is in front. That is why `tookScreen` exists.
- On the emulator, `Target.activateTarget` and `Page.bringToFront` brought Chrome forward from the background, even 15 seconds after HOME. On the phone, on 2026-10-21, they only changed which tab was selected, and the launcher stayed in front. The emulator's debug Chrome under `adb root` may explain the difference. FingertipsMCP never sends either for a session without the screen.
- A popup from a tab not on screen becomes the tab Chrome shows while Chrome is in front, with no change of foreground app, on the emulator and on the phone.
- `/json/list` does not report `openerId`. On the emulator `Target.getTargets` reported it for `window.open` and `target=_blank` popups, with the opener on screen, behind another tab, and with Chrome in the background. The phone reported it too.
- Page commands work on each page's own DevTools endpoint. On the phone, attaching through the browser connection got no answers.
- `Page.bringToFront` answers before the switch is done, and nothing announces the end of it, so FingertipsMCP polls `document.visibilityState`. On the Android 10 and Android 16 emulators, on 2026-11-05, a `touch` tap on a hidden tab took 70 to 100 ms longer than one on the tab already showing.
- On the Android 16 emulator, a tab opened while Chrome is in the background becomes Chrome's selected tab. Chrome selects it as it comes forward, even over a `Page.bringToFront` sent just before, which is why `show` checks that its tab is still showing and tries again. Opening the tab did not bring Chrome forward.

On the Android 13 emulator with Chrome 109 on 2026-11-06, `/json/list` named a tab `36`, and `Target.getTargets` named the same tab with 32 hex digits. So `POST /v1/browser/tabs` returned the first, and `GET /v1/browser/targets` listed the tab under the second with `owned` false. Ending the session still closed the tab. Chrome 133 has one id per tab.

The first id is the Android tab's number, and it is what `Target.createTarget` answers with on Chrome 109, while `Target.getTargets` has the second. Measured that day: a page's own connection answers `Target.getTargetInfo` with the listed id on Chrome 109 and 133, reached through either id, and the browser connection's `Target.getTargetInfo` echoes the number back. So FingertipsMCP asked the new page, until that connection turned out to race Chrome closing the tab. It now reads the id from `Target.getTargets`, as [Events for a new tab](#events-for-a-new-tab) says. The mismatch had also kept the startup sweep from matching a leftover, made each put-back open a new placeholder, and stopped incognito opens with `Chrome did not say which profile a new tab is in`. With the listed id, on the Android 13 emulator, a session's tab listed `owned` true, a leftover closed at the next start, and an incognito tab opened.

`Target.getTargets` lists only tabs Chrome has loaded. On Chrome 109 that day `/json/list` had 33 tabs, restored from earlier runs, and `Target.getTargets` had 3. A restarted Chrome also gives its tabs new hex ids, as one tab did on the Android 13 emulator that day, so a tab left by a run before Chrome restarted cannot be matched, and the sweep leaves it open.

The startup sweep used to read the saved list when it first got an answer from Chrome, by which time a live session's tabs were on it. On the Android 13 emulator on 2026-11-06, with Chrome stopped at the first helper connect, a tab a session then opened stayed open through a helper restart, and a leftover closed at the next start with Chrome running.

On the Android 11 test phone on 2026-10-22, with Chrome 155, a session's own tab stayed on screen while another session opened a tab, so switching back works.

#### A new tab is selected twice

Chrome 91, 109, 133, and 145 on the emulators on 2026-11-07, over raw DevTools with no FingertipsMCP involved, with a kept tab on screen and its `document.visibilityState` read every 20 ms. Times are from `Target.createTarget`. Each time the kept tab went hidden, `Target.activateTarget` selected it again.

| Chrome | Opens | Times Chrome took the screen | First | Second, idle | Second, every core busy |
| --- | --- | --- | --- | --- | --- |
| 91 | 6 | 2 in all 6 | 0.05 to 0.20 s | 0.35 to 0.62 s | Not read |
| 109 | 16, with one `activateTarget` each, so a third time would not show | At least 2 | Before 0.17 s | 0.36 to 0.73 s | 0.51 to 1.52 s |
| 133 | 14 | 2 in 13, 1 in the other, with every core busy | 0.05 to 0.25 s | 0.39 to 0.76 s | 0.47 to 0.86 s |
| 145 | 16 | 2 in all 16 | 0.08 to 0.23 s | 0.45 to 0.57 s | 0.57 to 0.72 s |

- The second selection does not depend on the page. With a server that took 2 s, on Chrome 109, it came at 0.38 to 0.40 s, long before the commit, and `about:blank` got it at 0.36 to 0.37 s. So it behaves like a timer in Chrome, and the end of the new-tab animation would fit, though that is a guess.
- Nothing on the browser session marks it. The new tab's only events in that time were `targetCreated` and, later, `targetInfoChanged` with its title.
- Sent before the second selection, `Target.activateTarget` is undone by it. Chrome 109, sent at 0.03 to 0.07 s: the kept tab read visible, then hidden at 0.36 to 0.45 s, 5 of 5. Sent at 0.29 to 0.44 s it was sometimes lost outright, never reading visible, 2 of 4. Sent from 0.47 s on it stuck, 12 of 12. On Chrome 145, sent at 0.29 to 0.51 s, it was undone 6 of 6, by 0.59 s.
- So `took_screen` from `Tabs.place` was not Chrome taking long to switch, or `showing` reading stale. In FingertipsMCP's own trace on Chrome 109, one `activateTarget` went out, the kept tab read hidden on all of about 30 polls in 2 s, and the new tab read visible at the end. A retry worked because its `activateTarget` landed after the second selection.
- The same race ended worse when the selection landed and read back first. The open returned 200, and the second selection then left the new tab on the holder's screen, until the holder's next screen call. On Chrome 109 that was 1 of 20 on `main` and 1 of 20 on v0.3.8, and 4 of 40 on `main` with logging added.
- It is not new, and #103 did not cause it. On Chrome 109, on the same emulator with one open at a time, v0.3.8 failed 1 of 20. Its build needed a change to find the emulator's wireless debugging port, and nothing else. `main` at 220f73a failed 6 of 20, and with logging added 4 of 40 and 0 of 14. All of those are one race, so the rate moves with how long the first check takes.
- `showing` is slow just after a tab is made: on Chrome 145 it took 329 ms there and about 40 ms after, and a first pass took 680 ms. That is why a run on a given day can show none: `activateTarget` then lands after the second selection. So a count of selections can't say when Chrome is done, since both can pass inside one check.
- A new tab's own page can't be asked: a new connection to it is held until the commit, so `showing` on a tab still opening gives no answer.

HOME reaches the top task well after the key is pressed. On the Android 13 emulator on 2026-11-07, with Chrome in front, a screen call made just after a session ended got the screen 0.77 to 2.27 s later, once cleanup saw the home app on top. Before cleanup waited for that, an open 300 ms after a session ended could still read Chrome on top and then the launcher, and was blamed for it.

### Launching apps from a tab

On the emulator, tabs that were not on screen launched no app in 18 tries: an `intent:` link, an app link, and a custom scheme, each by mouse click, by script, and by script with a user gesture, from a tab behind another and with Chrome in the background. App links opened inside the tab instead. From the tab on screen, the same clicks launched YouTube.

On the phone, on 2026-10-21, tabs not on screen launched no app in 8 tries with `tel:` and `intent:` links, by click and by script with a user gesture. From the tab on screen, both opened the dialer.

### Throttling and pausing

Measured on the Android 16 emulator on 2026-10-21. With Chrome in front and the tab behind another, its timers ran about once a second for the first minute, about once a minute after that, and stopped at about 5 minutes. A paused tab still answers DevTools: script evaluation, reading, mouse input, and navigation all work. Only the page's own JavaScript is paused, so content it would load or poll for does not arrive. `Page.setWebLifecycleState` with `active` answered but did not restart its timers. Navigating or reloading gives a fresh page that runs until it pauses again.

More on the emulator on 2026-10-22, with Chrome in the background and FingertipsMCP's helper holding Chrome's binding:

- The 5 minutes count from when the tab was hidden, not created. A tab kept on screen for 3 minutes paused about 5 minutes after it was hidden. Tabs hidden from the start paused at 5 minutes. Reading a tab over DevTools once a minute did not delay it.
- `Page.setWebLifecycleState` with `frozen` and then `active` thaws a paused tab without bringing it forward. A fetch the page started then completed, where one on a paused tab never did. Its timers ran about once a minute, as for any tab hidden that long. It had not paused again 9 minutes later.
- With Chrome started with `--disable-background-timer-throttling --disable-renderer-backgrounding`, tabs still paused at 5 minutes, but a thawed tab then ran its timers once a second.
- A tab reloaded while hidden ran its timers once a second for a minute, then about once a minute. It paused again about 5 minutes after the reload, so a reload restarts the clock.
- A paused tab still evaluates DevTools script but runs none of its own tasks, so a message it posts to itself never arrives. That tells a paused tab from a running one: about 10 ms on a running tab, and a 200 ms timeout on a paused one. A page busy running script answers no script at all, which tells it apart.

On the phone, on 2026-10-22, with the phone locked and Chrome in the background, a hidden tab's timers ran once a second for about a minute, then stopped entirely, where the emulator kept them at about once a minute. The page itself paused between 7 and 9.5 minutes, later than the emulator's 5. Between the two, input and reads work but the page's timers never fire, so a page that reacts through them, such as a debounced search box, never does. That is why FingertipsMCP checks timers as well as pauses.

The tab Chrome had selected kept running at full rate and reported `visible` with Chrome in the background and the screen off, so only tabs behind another one in Chrome go hidden.

Measured on the phone on 2026-11-07 with Chrome 156.0.8078.79, over raw DevTools, with FingertipsMCP's helper not running. Chrome 156 freezes a hidden tab whole, with no stage where its timers stop but the page still runs:

- A hidden tab ran its timers 4 times a second until about 32 s after it was hidden, then once a second.
- At about 90.6 s the page got `freeze`, and its timers, fetches, the DOM changes they make, and a `MessageChannel` loop all stopped together. That held in 10 tabs. The 1 ms timer check in an isolated world stopped at the same moment.
- `Page.setWebLifecycleState` with `frozen` and then `active` thawed it without the screen, 2 of 2. The page saw only `resume` and stayed `hidden`. It had not frozen again 8 to 12 minutes later, when the run ended. Its timers ran once a second, then once a minute from about 330 s after it was hidden. `active` alone did nothing.
- A reload or navigate restarted the page, and it froze again 60 s after the load.
- Chrome's selected tab never froze, even with Chrome in the background.

That differs from Chrome 155 on 2026-10-22, where timers stopped at about a minute and the page paused minutes later. That run went through FingertipsMCP, with its helper running, and this one did not, which may account for it.

FingertipsMCP fails a wait on a frozen tab with `tab_frozen` rather than thawing it. A thaw shows the page a `resume` while it stays hidden and then leaves the tab running in the background for minutes, which a person's tab would not do.

### The timer check

Chrome runs a hidden tab's timers at most about once a second even when it has not stopped them, so the check allows 1.1 seconds, too long to wait before every input. It starts alongside each `cdp` or `cdp-mouse` input, in the background, on its own DevTools connection, and the next call on that tab reads the result.

The check's timer has a 1 ms delay. Chrome runs a zero-delay timer at once on a queue it never throttles, which on the emulator fired at once in a tab whose other timers had slowed to once a minute. On the phone, both stopped along with the page's own. Both checks run in an isolated world, so the page can neither see them nor fool them by replacing `MessageChannel` or `setTimeout`. Chrome stops that world's timers along with the page's, since it throttles per frame.

Checked on the emulators on 2026-10-22: a tab hidden for a minute ran a single 1 ms timer within a second, while its `setInterval` ran once a minute, so the timer check let input through. With the check's timer swapped for a chain of nested timers, which Chrome had slowed to once a minute, a background key was let through, a read noted `timers stopped`, the next key was refused with `tab_frozen` after the 1.1 s confirmation, a wait on the tab failed the same way, and a tab opened a moment before was left alone. Bringing the tab forward with `show` cleared it.

Checked on the phone on 2026-10-22: a hidden tab's timers ran once a second until about a minute after it was hidden, then all stopped at once: its `setInterval`, and a 0 ms and a 1 ms timer in an isolated world, none of which fired within 5 seconds. So on Chrome 155 the 1 ms check sees what the page sees. About 2.5 minutes after hiding, the pause check, by then in an isolated world, refused the first background key with `tab_frozen`, much sooner than v0.3.0's main-world check caught a pause (7 to 9.5 minutes). A `wait_for` on the tab ran out its 8-second timeout, since that refused key had started no timer check; waits now start their own.

### Unloaded tabs

Opening heavy pages one after another on the 2 GB emulator, nothing was unloaded through 15 tabs, ten of them real sites such as YouTube, CNN, Amazon, Maps, and the New York Times, with free memory down to about 220 MB. From 17 tabs, with 250 MB pages added, Chrome unloaded the oldest tabs first, six by 19 tabs. That is where the cap of 8 tabs comes from: it leaves room for the tab on screen, unowned tabs, and other apps on a 4 GB phone.

An unloaded tab stays in the tab list. `Page.reload` and `Page.navigate` bring it back as a fresh load and leave it hidden. Script evaluation and `Page.getFrameTree` never answer, since its renderer is gone. That matches the long-hidden tabs on the test phone. Selecting it also reloads it, but puts it on screen. `document.wasDiscarded` stayed false, so a changed `performance.timeOrigin` is what shows the reload.

FingertipsMCP tells an unloaded tab apart with `Inspector.enable` on the page's own connection. Chrome answers it from the browser process and, for a tab with no renderer, sends `Inspector.targetCrashed` first. A script probe with a timeout cannot tell an unloaded tab from a page whose main thread is busy: on the emulator, a page in an 8-second loop answered neither script nor, on a fresh connection, `Runtime.getIsolateId`, and the first build reloaded it. With `Inspector.enable`, that read waited for the loop and kept the page. When `Inspector.enable` gets no answer in 2 seconds, FingertipsMCP checks Chrome's browser endpoint on a socket that gives up reading after 2 seconds. A read of an unloaded tab returned the reloaded page in about a second on the emulator, with Chrome's renderers killed to stand in for memory pressure. When Android has frozen Chrome, the page connection's handshake waits out the 15-second read timeout before the browser check, so `browser_frozen` takes about 17 seconds rather than four 15-second retries. That was checked with `am freeze --sticky com.android.chrome`.

Checked on the Android 16 emulator with Chrome 133 on 2026-11-06, with `Page.crash` standing in for an unload. A read of the tab returned the new page with `reloaded` true, so `readyState` read in an isolated world right after `Page.reload` does not lose the reload. A key got `tab_reloaded` in 0.15 s. With a page that took 12 s to answer, a read failed `tab_reloaded` at 10.1 s, and a read after the page loaded worked.

On that emulator, with a page running 3 s tasks back to back, 3 `cdp` keys into the visible tab and 4 into a hidden one all went through, with no false `tab_frozen`. The first took 4 to 6 s, waiting for a gap between tasks. A visible tab kept its timer at one tick a second for 331 s, past the 5 minutes that hidden tabs get, and took a `cdp` key at the end. `am freeze` on Chrome gave `browser_frozen` at 17.1 s for a read and for a key, which is the 15 s handshake and the 2 s probe, and `cdp_timeout` at 15.1 s for the target list.

### Closed tabs

Chrome refuses a DevTools connection to a closed tab with 500 "No such target id", in Chrome 133 on the emulator and Chrome 155 on the phone, and 404 in some releases. FingertipsMCP reports it as `not_found` rather than retrying. Chrome 156 on the phone did the same on 2026-11-07, every time: 30 of 30 reconnects after a cancelled save closed a tab, and 10 of 10 after `Target.closeTarget` closed a tab with a call waiting on it. That call's connection dropped with no answer. After the saves, `Target.targetDestroyed` had already come, so a reconnect ahead of that event was not tried.

Closing Chrome's last tab, which opens the placeholder first, was checked on 2026-11-08 on the Android 16 emulator with Chrome 133 and the Android 17 emulator with Chrome 145, with Chrome holding only the session's tab (older tabs and the placeholder closed over raw DevTools first). With the session holding the screen and Chrome showing that tab, `POST /v1/browser/targets/{id}/close` left Chrome in front showing `about:blank`, 1 of 1 on Chrome 133 and 3 of 3 on Chrome 145. With Chrome behind the home screen, the home screen stayed in front, 1 of 1 each. Each time Chrome's own list then held only the placeholder, and `GET /v1/browser/targets` listed nothing. A restored tab Chrome had not loaded was in `/json/list`, with a short numeric id, and not in `Target.getTargets`, on both.

On the Android 11 test phone on 2026-11-08, with Chrome 156.0.8078.79, Chrome kept two tabs that were not the test's, so the session's tab was never Chrome's last, and the last-tab path was not reached. What was checked is that closing a tab beside others opens no placeholder. With Chrome behind the home screen and no placeholder open, Chrome's `/json/list` held one restored tab. A session opened a tab and brought it on screen with a `read` with `show`, and Chrome then also listed a restored new tab page it had not listed before. With Chrome in front showing the session's tab, `POST /v1/browser/targets/{id}/close` answered in 0.36 s, Chrome stayed in front, and `/json/list` held the two other tabs and no `about:blank`, 1 of 1. Ending that session went home and put the placeholder up. A new session without the screen then opened and closed a tab with Chrome behind the home screen. The close answered in 0.37 s, the home screen stayed in front, `/json/list` held the placeholder and the two tabs, and `GET /v1/browser/targets` listed the two tabs, unowned, and not the placeholder, 1 of 1.

### Target events

Chrome 156 on the Android 11 test phone on 2026-11-07, over raw DevTools, on a browser session with `Target.setDiscoverTargets`. Times are from the call that started it. Chrome hidden and Chrome in front gave the same events, unless noted.

| Tab | `targetCreated` | `targetId` | Then |
| --- | --- | --- | --- |
| `Target.createTarget`, blank or a URL | 13 to 41 ms, before the reply at 89 to 167 ms, in every try | The reply's | `url` is empty in the event |
| `am start` of a `VIEW` intent | 290 ms, with `am` back at 166 ms | Not known ahead | `url` empty, then `targetInfoChanged` at 548 ms |
| `window.open` with a user gesture | 154 ms | Not known ahead | `openerId` set, `url` empty |
| `/json/new` | None | None | 500 `Could not create new page` |

| Close | `targetDestroyed` | Off `/json/list` |
| --- | --- | --- |
| `Target.closeTarget` | 28 to 50 ms | 44 to 66 ms |
| `/json/close` | 28 ms | 37 ms |
| `window.close()` from its opener | 112 ms | Not read |
| A cancelled PDF save, Chrome hidden | 10 of 10, 25 ms after `Browser.downloadWillBegin` at the median, 29 ms at most | 13 to 23 ms after that |
| A cancelled PDF save, Chrome in front | 20 of 20, 38 ms at the median, 99 ms at most | 18 to 52 ms after that |

- A cancelled save's tab sends `targetInfoChanged` with an empty `url` just before `targetDestroyed`. Its page connection closes within 6 ms of `targetDestroyed`. `downloadWillBegin`'s `frameId` was the `targetId` in all 30, and it came 119 to 181 ms after `createTarget` was sent, after the reply each time.
- Discover answers in about 6 ms, with a `targetCreated` for each tab read before the reply, `attached` false. A second `setDiscoverTargets` replays nothing.
- The replay has one more target than `/json/list` and `Target.getTargets`: a page on `chrome-native://newtab/` with no `browserContextId`. So the replay and the list can't be compared as sets.
- Open on a URL, `targetInfoChanged` comes twice. The first is at the start of the navigation, about 130 ms in, with the URL and an empty title, so it does not mean a commit. A PDF that never commits can send it too. The second comes at the commit, with a title, which is the host until the page sets one.
- With the screen off, a fast page opened on its URL sent only the second, at 392 to 476 ms, in 2 tries.
- A blank tab that then navigates sends one, 25 to 36 ms after `Page.frameNavigated`, in 4 of 4, and none at the start.
- A tab opened on its URL does not answer `Page.enable` on a new connection until the commit, 1.7 to 1.8 s in with a server that took 1.5 s. So `Page.frameNavigated` did not reach that session in 16 of 16 tries, 8 hidden and 8 in front, slow and fast. Before the reply it sends `frameStartedNavigating`, at about 150 ms with the navigation's loader id, and `frameStoppedLoading`. `Page.setLifecycleEventsEnabled` then gives `commit`, `DOMContentLoaded`, and `load` at once, and `Page.getFrameTree` the URL and that loader id.
- `location.hash` and `history.pushState` send `targetInfoChanged` with the new URL, 3 to 8 ms before `Page.navigatedWithinDocument`. Setting `document.title` sent nothing in 700 ms.
- A refused connection sends the URL at the start, then a title at 400 to 600 ms, with no `Page.frameNavigated` on a session opened right after creating the tab.

### Page dialogs

Chrome draws a page's alert, confirm, or prompt in `tab_modal_dialog_container`, with the title in a view called `title`. A page held by one answers nothing over DevTools, so a `browser.dialog` wait counts a dialog already open from what is on screen.

Checked with Chrome 91, 109, and 145 on the Android 10, 13, and 17 emulators on 2026-11-06:

- The title is the page's host, then its port if the URL names one, then " says": `example.com says`, `127.0.0.1:8765 says`, `localhost:8766 says`. It has no scheme or path, and nothing else on the dialog names the tab. So FingertipsMCP takes a title for a page's own only when it holds that whole host, with no more of a host or a port beside it. On Android 10, a `browser.dialog` wait on `example.com` timed out with `www.example.com says` in front, and matched in about 50 ms on the page whose dialog it was.
- With Chrome 145 and a dialog up in the tab in front, a hidden tab on the same host did not answer `Runtime.evaluate` within 5 s either, nor did a new session on the tab in front. So neither the title nor the tabs can say which of two tabs on one host a dialog belongs to, and a `browser.dialog` wait on the hidden one counts it.
- With Chrome 145, a page's own timer calling `alert` showed no dialog with no DevTools session attached, in a tab opened by `am start`. The same call from DevTools did. Why was not looked into.

Not measured: the title in another language (Chrome did not come up under DevTools with `cmd locale set-app-locales`, so the match leaves out the words around the host), a dialog from a cross-origin frame (the frame was not in `Page.getFrameTree`), and a host with non-ASCII letters.

### Script worlds

A world belongs to one document. `Runtime.evaluate` naming a world whose document has gone fails with code -32000 and the message `Cannot find context with specified id`, and runs nothing. A context id that never existed gets the same reply. Chrome gives -32000 for any server error, so FingertipsMCP needs the message too, and it is the only thing that names this failure. Checked with Chrome 109 and 145 on the Android 13 and 17 emulators on 2026-11-06, with a reload between making the world and using it.

### Still to check

- How long a tab switch takes on the phone, and whether 2 seconds always covers it. A `touch` tap is refused with `not_visible` past that.
- Whether Chrome 109 selects a new tab only twice, with the kept tab selected again each time, as was counted on Chrome 91, 133, and 145.
- Whether Chrome 156 on the Android 11 test phone selects a new tab twice, when the second comes, and whether 3 seconds covers it on a busy phone. Also that three parallel sessions with a kept tab showing no longer fail there, which was 12 of 69 before.
- Whether HOME takes as long to become the top task on the phone as on the emulators.
- Why the first `DELETE /v1/sessions/current` on the Android 16 emulator took 15.03 s once. It is not cleanup, which `DELETE` does not wait for. It closes the session's tabs, which can need a new DevTools connection, and the search for wireless debugging's port takes 15 s when it finds nothing, as on 2026-11-07 when a browser call failed that way after 15.09 s with another emulator running. So that search is the likely cause, but changing the port just before a `DELETE`, twice, left it at 0.05 s, so this was not reproduced.
- Whether a tab opened while Chrome is in the background becomes Chrome's selected tab, as it does on the Android 16 emulator.
- How many hidden tabs Chrome keeps loaded on the Moto G Power before it unloads them, to check the cap of 8.
- That Chrome 155 still sends `Inspector.targetCrashed` on `Inspector.enable` for a tab it unloaded.
- Whether a hidden tab's timers also stop while Chrome itself is in front, with the tab behind another, or only while Chrome is in the background.
- Whether Chrome 156 still freezes a hidden tab whole at about 90 s with FingertipsMCP's helper running, as it did on 2026-11-07 without it.
- That `show` brings a background tab on screen, including when Chrome is behind another app, and that `shown` and `pending` match what is on screen.
- That Chrome on the phone still ignores `Target.createTarget`'s `background` flag, now that a session without the screen sends it, or if it honors it, that the tab opens hidden.
- That closing Chrome's last tab on the phone's Chrome 156 does what it did on Chrome 133 and 145. On 2026-11-08 the phone's Chrome had tabs that were not the test's, as [Closed tabs](#closed-tabs) says, so this needs a Chrome holding only the session's tab.

## Android's app freezer

On Android 12 and later the cached-app freezer is on by default. On the Android 16 emulator on 2026-10-21, about 2 minutes after Chrome went to the background, every Chrome process was frozen (`isFrozen=true`), and nothing answered, not even the browser endpoint. Android 11 has the freezer but leaves it off by default. On the phone on 2026-10-22, `dumpsys activity` printed "Freezer settings" with `use_freezer=false`, and `cached_apps_freezer` was unset.

The freezer's effective state is `use_freezer` under "Freezer settings" in `adb shell dumpsys activity`. `dumpsys activity settings` leaves that section out, and the whole dump is hundreds of kilobytes, so the helper reads it through `grep -m 1`. The setting behind it is `cached_apps_freezer` (`settings get global cached_apps_freezer`), unset by default. On the Android 16 emulator, changing the setting changed `use_freezer` at once, without the reboot the developer option asks for. With it `disabled`, Chrome was never frozen in 150 seconds in the background. Set back to the default, Chrome was frozen within 90 seconds. Turning it off does not thaw an app that is already frozen; that waits until the app comes to the front or the phone restarts.

Holding Chrome's Custom Tabs binding was checked on the Android 16 emulator, with the freezer at its default, on 2026-10-21, using a throwaway build:

- With the binding held, Chrome's browser process stayed at importance 200, the same as FingertipsMCP's service, and Android froze none of Chrome's live processes in 10 minutes in the background. A hidden tab answered DevTools throughout. Its timers still stopped at about 5 minutes, which is Chrome's own pausing and leaves reads and input working.
- The renderers of hidden tabs sat at importance 900 to 910, the cached range, yet were not frozen. Android's low-memory killer can still take them, which is unloading as above.
- Without the binding, Chrome dropped to the cached range within a minute, and every Chrome process was frozen by 2 minutes.
- Chrome's Custom Tabs service listed FingertipsMCP as its client from a foreground-service process.

On the phone on 2026-10-22, FingertipsMCP held the binding (`chromeKept`).

A frozen process still takes connections. On the Android 17 emulator (kernel 6.12, WebView 145) on 2026-11-07, with the Google app's `googleapp` process frozen by `am freeze --sticky`, its `webview_devtools_remote_*` socket connected and never answered. Each connection stayed queued on the listener after the client hung up, as a row in `/proc/net/unix` under the listener's name, with flags `00000000`, state 02, and inode 0. Nothing limited the queue up to 938 rows. A thaw drained them at tens of rows a second. A connection Chrome has accepted has a row under the listener's name too, with state 03. Only the listener itself has flags `00010000` (`__SO_ACCEPTCON`) and state 01.

FingertipsMCP used to take every row with a DevTools name for a socket. Each listing then connected once per row and added as many rows again, so a frozen WebView's rows doubled at each listing. Measured on that build:

- At 90 rows, one listing blocked 63 of the 64 IO threads, and a blocking read of an API connection took the last. `GET /v1/device` stalled until the 5 s limit closed the streams. Past that, reads queue for a thread, so more rows stall the API for longer.
- 12 clients at once, or a few hundred rows, ran the app out of heap and it restarted, since each Kadb stream allocates a 1 MiB send buffer.
- The helper searched `/proc` for each row's owner and found none, which took up to 0.3 s a row.
- With three idle connections to Chrome, one listing returned each of the 2 tabs 4 times.

The threads were not leaked. Closing an `AdbStream` ended every read on it: 62 of them were in `MessageQueue.take`'s `await`, which `stopListening` wakes, and one was in the socket read, which needs the next message on the connection. No thread was left in either place 25 s after the listing, and the closing timer ran with the pool full.

With only listeners counted, on the same emulator, a listing with 90 rows queued added one row, used one thread, and took 5.1 s, and `GET /v1/device` answered in 12 ms. 12 clients making 2 listings each all got 200 within 5.4 s, adding 24 rows and using 12 threads. After a thaw, a listing took 0.14 s. `GET /v1/browser/targets` made one listing. Opening a tab, a snapshot, navigating, evaluating, and a tap made none, and nothing listed while idle.

On the Android 17 emulator on 2026-11-08, with no `am freeze`, the Google app's `googleapp` process sat in the cached state (`cch-empty`) a few minutes after boot, with 35 rows queued on its `webview_devtools_remote_*` listener. Its freeze state was not read. Every listing then took 5.0 s, the listing's limit: `GET /v1/browser/targets`, and each tab close, since closing a tab lists Chrome's tabs first to see whether it is the last. A close took 5.0 to 5.3 s, 8 of 8, against 0.02 to 0.17 s on the Android 16 emulator, which had no such socket. Over raw DevTools the same `Target.closeTarget` took 0.01 s. A session end that went home took 10.4 s to get there.

On the Android 10 emulator (kernel 4.14) the same day, the listeners had the same flags and state, and a listing through the USB path found Chrome's tab.

On the Android 11 test phone on 2026-11-07, at 9e047f7, with 3 idle connections to Chrome open, `/proc/net/unix` showed 4 rows under Chrome's socket name, and 1 again after they closed. Each of 5 listings returned the 2 tabs once, in 0.10 to 0.12 s. No frozen WebView could be tried there: no `webview_devtools_remote` socket was listed, and `cached_apps_freezer` is unset.

Closing a tab used to list every DevTools socket first, to see whether the tab was Chrome's last, so a WebView that did not answer held each close for the listing's 5 s limit. Now that check, and the rest of FingertipsMCP's questions about which tabs Chrome has, list Chrome's socket alone. On the Android 17 emulator with Chrome 145 on 2026-11-08, the Google app's search results put a `webview_devtools_remote_*` socket in its `googleapp` process, which was then sent home and frozen with `am freeze --sticky`. A full listing (`GET /v1/browser/targets`) still took 5.0 to 5.1 s, 3 of 3, with Chrome running. Opening a tab and closing it over REST took 0.08 to 0.15 s for the close, 8 of 8. Ending a session with 3 tabs, after it had given the screen back, took 0.30 s. Ending one that held the screen with Chrome in front, holding 2 tabs, took 0.13 to 0.18 s, and the launcher had focus 0.19 to 0.55 s after the end was sent, 3 of 3.

### Still to check

- That binding Chrome's Custom Tabs service works on a real Android 12 or later phone, and does not change anything visible on the Android 11 test phone.
- Whether Android 11 applies a change to `cached_apps_freezer` as quickly as Android 16 does.
- A frozen Chrome's handshake on the Android 10 USB path.
- That a WebView Android itself froze behaves as one frozen by `am freeze`. Whether a listener's queue has a limit, and what adbd does to a connect once it is reached.

## Starting activities

`am start` exits 0 when Android refuses an intent, and prints the result code as text. So the helper makes the call `am` makes, on `IActivityTaskManager`, and FingertipsMCP decides from the code: `startActivityAndWait` when a call needs to know what took the intent, as `am start -W` does, and `startActivity` otherwise.

Read from `framework.jar` on the emulators on 2026-11-06:

- `startActivityAndWait` takes `(IApplicationThread, String, Intent, String, IBinder, String, int, int, ProfilerInfo, Bundle, int)` on Android 10. Android 13, 16, and 17 add a `String`, the calling feature id, after the calling package. `startActivity` is the same without the last `int`, the user. The helper places the arguments by their types.
- Android 11's signature has not been read from a device, though starts run there, as below. In AOSP's `android-11.0.0_r1` source, both calls have the feature id, as Android 13 does, and `am`'s `runStartActivity` adds `FLAG_ACTIVITY_NEW_TASK` and ends in `return 0` whatever the code was.
- The `ActivityManager.START_*` codes FingertipsMCP uses had the same values on all four: `START_SUCCESS` 0, `START_RETURN_INTENT_TO_CALLER` 1, `START_TASK_TO_FRONT` 2, `START_DELIVERED_TO_TOP` 3, `START_SWITCHES_CANCELED` 100, `START_INTENT_NOT_RESOLVED` -91, `START_CLASS_NOT_FOUND` -92, `START_PERMISSION_DENIED` -94. The SDK hides them, so FingertipsMCP has its own copies. `ActivityManager.isStartResultSuccessful` accepts 0 through 99 in the Android 11 source, which is what the audio start uses.

Run through `POST /v1/intents` and `POST /v1/apps/{pkg}/launch` on the Android 10, 13, and 17 emulators the same day:

- An intent nothing handles gave -91 and a missing class -92, both as `not_found`. Categories, extras, and flags reached the activity.
- A component that is not exported gets no code. Android throws `SecurityException`, `Permission Denial: starting Intent { … } not exported from uid …`, and the helper reports that as `START_PERMISSION_DENIED` with the exception's message.
- A `VIEW` for Chrome on Android 10 gave `START_TASK_TO_FRONT` with Chrome already open, which goes back to the client as a warning, as it did from `am`.
- A chooser has no code. `android.intent.action.WEB_SEARCH`, which has two handlers on the Android 17 image, gave 0 with `android/com.android.internal.app.ResolverActivity` as the activity that took it, the same as `am start -W` printed. So that name is the only sign of a chooser, and FingertipsMCP matches it, and `ChooserActivity`, as text.
- The wait lasts as long as the launch. A cold start of Settings returned about 0.5 s after Android logged `Displayed`, at 0.7 to 1.6 s, and the first one after a boot took 5 s.
- Without the wait, `startActivity` started Chrome's launcher entry for a tab open with Chrome stopped, on all three.
- The Android 11 audio start's heap-dump activity, run on the Android 17 emulator on 2026-11-06 with a throwaway build that took out the Android 11 check: `startActivityAndWait` gave `START_SUCCESS` and the activity's name, also with the screen off, and `removeTask` left the launcher on top. A class that does not exist gave -92, and audio still started. Android logged `Displayed` at about 0.8 s, and at 2 s for the first start after a boot, which ran past a 2 s wait. So the audio start's limit is the same 20 s as an app start. The shell can't `pm disable` that activity, since `pm` threw.
On the Android 11 test phone on 2026-11-06, `app_launch` of Settings with `wait` took 0.6 to 1.3 s and returned ok. `open_intent` of a `VIEW` for Chrome, with Chrome open, opened the tab and gave `Activity not started, its current task has been brought to the front`, and the tab was the caller's. The heap-dump activity's start ran for audio. An intent refused or left unhandled was not tried there.

### Still to check

- Added after these checks ran, on code they did not test: an `open_intent` tab claimed within 2 s: that the start names `com.android.chrome` as what took a URL with no package named, with Chrome in front and behind, and that the new tab has no `openerId`, including across a redirect; that `app_launch` of a running Chrome opens no new tab.
- All of this section on phones other than the Android 11 test phone, since an OEM can change this interface; the wait running out at 20 s, which no emulator reached; an incognito open, whose start runs only on a Chrome that reports a tab's profile (Chrome 91 and 109 stop before it, with `Chrome did not say which profile a new tab is in`).

## Incognito tabs

DevTools cannot open an incognito tab. Chrome on Android fails `Target.createBrowserContext`, and opens a normal tab when `Target.createTarget` names the incognito tabs' `browserContextId`. So FingertipsMCP starts Chrome's "New Incognito tab" shortcut instead, as [sharing.md](sharing.md#incognito-tabs) describes.

DevTools can clear some incognito data without closing tabs, but not all of it. `Storage.clearCookies` failed for the incognito `browserContextId` ("Failed to find browser context"), as `createTarget` ignores it. `Network.clearBrowserCookies` from an incognito tab cleared the incognito cookies and left the normal ones. Local storage stayed until `Storage.clearDataForOrigin` cleared it, one origin at a time, and DevTools has no list of the origins that hold data. So closing the tabs is the only full reset.

Checked on the Android 16 emulator with Chrome 133 on 2026-10-31, with the helper as the shell user:

- Over raw DevTools, the shell user can start the shortcut, each start opens a new incognito tab listed with its own `browserContextId`, and `screencap` of it is black.
- Through `POST /v1/browser/tabs`, `/v1/stream/video` and `/v1/screenshot` showed an incognito tab black and a normal tab in its place drawn. An incognito open took 1.5 s the first time, with the blank tab to learn the normal context, and 0.8 s after.
- A cookie set in a normal tab was not in the incognito tab. `read`, `snapshot`, `evaluate`, and a `cdp-mouse` tap on a link worked on it.
- Ending the session closed both incognito tabs within 0.5 s in two runs. In an earlier run, the second incognito tab was still listed 2 s after the session ended; that was not seen again.
- A session without the screen got it back free after the open, with Chrome sent home and the tab still readable. One that held the screen kept it.
- A cookie set in one session's incognito tab showed in another session's, and in a third opened after the first closed its tab. Once every incognito tab was closed, a new one had no cookie.
- With an incognito tab opened outside FingertipsMCP holding a cookie, an incognito open closed it, and the new tab, opened right after, was in a new browser context with no cookies. A second session's open kept the first session's tab, saw its cookie, and got the note.
- The Incognito Screenshot flag was in `chrome://flags`. It stayed on after Relaunch; Chrome writes flags as it shuts down, so a force stop loses the change. With it on, the video and `/v1/screenshot` showed an incognito tab's page and Chrome's incognito icon.

On the Android 11 test phone on 2026-11-07, at 9e047f7, Chrome opened an incognito tab for `OPEN_PRIVATE_TAB` from the shell user, and FingertipsMCP took it as such, in the one open tried. It took the screen, and after the session ended no tab was left in another browser context.

### Still to check

- Whether Chrome's "Lock Incognito tabs when you leave Chrome" setting blocks a tab after cleanup sends Chrome home. Also that the viewer shows an incognito tab black there, as on the emulator, and that the phone's Chrome has the Incognito Screenshot flag and it makes the viewer show the tab.

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

With pdf.js kept in the viewer's main world, checked through FingertipsMCP on the Android 16 emulator with Chrome 133 and `AndroidOpenPdfInline` on, on 2026-11-05: the first `read` of the 23-page PDF took 2.0 s, and a `find` and a second `read` after it took 150 ms and 140 ms. The test server saw two requests for the file, one from Chrome's viewer and one from the first read. A `navigate` to another PDF in the same tab read the new file.

On the Android 11 test phone with Chrome 156.0.8078.73 on 2026-11-06, through FingertipsMCP and raw DevTools, against the arXiv PDFs 1706.03762 and 1810.04805:

- Chrome saves a PDF. It has no viewer here. With Chrome in front, a tab opened on a PDF stayed on `about:blank` behind Chrome's "Choose where to download" dialog, and Cancel closed the tab with nothing saved. With Chrome hidden no dialog was seen, and the file went to `/sdcard/Download/`.
- `Page.navigate` to a PDF, on a tab with `Page.enable` on, answered in 30 ms with `isDownload` true and `net::ERR_ABORTED`. `Page.downloadWillBegin` reached the page session at 50 ms, so it is sent to a page session on this Chrome, and `Page.downloadProgress` said `completed` at 460 ms.
- `Target.createTarget` with the PDF's URL: `Page.getFrameTree` right after gave the frame a `url` of `""`, not `about:blank`, and no `Page.downloadWillBegin` came, since the save began before `Page.enable`. With Chrome hidden, `Inspector.detached` followed at 0.39 s, and the file was saved. `follow` in `TabOpening.kt` takes any first URL but `about:blank` as a commit, so it returned `COMMITTED`.
- So a first plain open of a PDF, `POST /v1/browser/tabs` or `browser_read` with `url`, returned 200 in 0.7 s with no `savedPdf`. A read of that tab returned 200, `url` `about:blank`, `extracted` `page`, and 0 characters, with no error, with Chrome in front and with it hidden.
- `browser_navigate` to a PDF failed 400 `net::ERR_ABORTED`, as on the emulators, and put the dialog up with Chrome in front.
- The stand-in works on this Chrome. `capture` true opens a blank tab and navigates it, which sees `isDownload`. It returned `savedPdf` true in 6 to 9 s, with Chrome hidden and with it in front. No dialog was on screen after it, and Chrome saved the file each time. `read` of the 15-page paper took 2.1 s, `extracted` `pdf`, 38,965 characters, with the right title. A `find` for "attention" took 0.2 s with 90 matches. `read` of the 1810.04805 paper took 1.6 s, 64,220 characters. So the Fetch domain answers the navigation to a PDF here, and the page's fetch is served.
- A URL FingertipsMCP remembers needs no `capture`: `browser_read` with `url` of the first paper then took 8.1 s, and read it.

Opening every tab blank and navigating it, tried for that on 2026-11-06, was wrong, and a tab now opens on its URL again. On the Android 16 emulator with Chrome 133, whose PDF handler is Drive's `PdfViewerActivity`, over raw DevTools, three tries of each:

| | Chrome left a second ago | Chrome in front |
| --- | --- | --- |
| `Target.createTarget` on the URL | no start of Drive | no start of Drive |
| A blank tab, then `Page.navigate` | Drive on screen, 3 of 3 | Drive on screen, 3 of 3 |

With Chrome hidden for longer, Chrome still asks: through FingertipsMCP, ten first opens of PDFs gave 18 `START` lines for Drive in `ActivityTaskManager`, and the launcher stayed in front. Android blocks a start from the background, and lets it through for some seconds after an app was in front. So an earlier check that saw nothing come forward with Chrome long hidden proved nothing.

What works is a browser session listening first. `Browser.setDownloadBehavior` with `behavior` `default` and `eventsEnabled` true leaves Chrome's handling as it was, and `Browser.downloadWillBegin` then reaches that session before a tab session could attach, 0.1 to 0.5 s after `Target.createTarget`, on Chrome 133, 145, and 156. Its `frameId` is the new tab's target id, so a person's own download can be told apart. `Browser.cancelDownload` on that event, on the Android 11 test phone with Chrome in front, gave `canceled` within 30 ms, no dialog, no file, and the tab closed. `behavior` `deny` did the same, but for every download while it is set. Without a cancel, the dialog stayed on screen after the tab closed, and the file was saved.

With that build on the Android 11 test phone with Chrome 156, on 2026-11-06:

- Three first opens with Chrome hidden returned `savedPdf` true in 7 to 9 s, and three with Chrome in front in 4 to 4.5 s. Each read as `pdf` in 1.3 to 2.9 s. A `capture` open in front did the same in 6.3 s.
- No dialog showed, nothing but Chrome or the launcher came forward, and nothing reached Downloads.
- A plain page saw `history.length` 1, as a tab opened on its URL does.
- A URL FingertipsMCP remembered opened in 3.2 s.

The merged code, at `e25a1dc`, did the same on that phone on 2026-11-07, with Chrome 156.0.8078.79 and arXiv PDFs it had not opened before:

- Three first opens with Chrome hidden returned `savedPdf` true in 6.3 to 10.2 s, three with Chrome in front in 3.9 to 4.8 s, and a `capture` open in front in 6.7 s. A remembered URL opened in 3.3 s. Each read as `pdf` in 1.0 to 2.3 s.
- No dialog showed on the opens in front, nothing but Chrome or the launcher came forward, and nothing reached Downloads. A plain page saw `history.length` 1.
- The first hidden open was the slowest, and Chrome was on top when it returned, then the launcher. That fits FingertipsMCP starting Chrome, which takes the screen and gives it back, after the phone sat asleep. Whether Chrome was running before that open was not checked.

A later check of `main` at 2acb53e on the same phone that day found a race. 2 of 12 first opens in a row with Chrome in front failed 502 `cdp_error`, `DevTools closed the WebSocket`, at 0.4 s, far too soon to be the file loading. Two hidden opens and 25 plain pages in the same rhythm were fine, and neither failed open left a tab. The cancel starts as soon as Chrome names the tab, so it can close the tab while the connection asking for its listed id waits on `Target.getTargetInfo`. That cause comes from reading the code, and a test that drops that connection gave the same error. A build that treated a begun save as the tab closing, in `listedId` only, read 30 of 30 first opens in front as `pdf`, in 3.8 to 7.2 s, and nothing reached Downloads. That change did not land, since `usePage` now answers `not_found` for a tab that closes during any call, and that fix has not run on this phone.

Chrome 145 on the Android 17 emulator, opened this way, saved a PDF rather than showing its viewer, and the event and the cancel worked as on Chrome 156.

With the same build on the Android 16 emulator with Chrome 133 and Drive, on 2026-11-06, 21 opens that reached Chrome gave no `START` line for Drive, and nothing reached Downloads: one with Chrome long hidden, three just after it left, thirteen with it in front, and four with `capture`, two of those in front. `capture` still navigates, so the cancel is what stops Drive there.

Other things seen while checking, on the Android 16 emulator:

- The cancel closes the tab, so the tab's session can drop while `Target.getTargets` still lists it. So a save already heard settles what that drop was.
- 3 of 25 opens in a row with Chrome in front failed `cdp_error`, two of them read as `No node with given id found`, from `PdfReader.ownPage`. `Page.navigate` returns before the commit, so `DOM.getDocument` can name the old document and `DOM.resolveNode` then fails. This is on the stand-in's path, not the open's, and did not happen in 7 opens on the Android 11 test phone. A new open worked. `PdfReader` now waits for the commit, as below.
- 1 of those 25, with Chrome in front and no session holding the screen, failed `took_screen`: `Chrome showed the new tab on screen and would not switch back`.
- Chrome 133 answers `Page.navigate` for a PDF with `net::ERR_ABORTED` and no `isDownload`, and the save follows. So a navigation that fails, for `capture` or incognito, gets 2 s for the save to show before it is a 400.
- A second save of a file by the same name, with Chrome hidden, left Chrome's "Download file again?" dialog waiting. It showed when Chrome next came forward, and covered the page until cancelled.
- A fresh Chrome's "Chrome notifications make things easier" prompt also covered the page when Chrome came forward, and `touch` input got `content_unavailable` until it was dismissed.

The stand-in's commit, with Chrome in front, on 2026-11-07. Over raw DevTools, on a blank tab, the navigation fulfilled through the Fetch domain as `PdfReader` does:

- On the Android 16 emulator with Chrome 133, `Page.frameNavigated` came a median of 30 ms after `Page.navigate`'s reply, and at most 107 ms after. In 71 of 90 tries it had not arrived when `DOM.getDocument` was sent, and one try failed `No node with given id found`. Its `loaderId` was the one `Page.navigate` gave, as `Page.getFrameTree` said too.
- Waiting for that event first, on Chrome 133, 60 of 60 had committed and none failed. On the Android 13 emulator with Chrome 109, 30 of 30 did, with the event a median of 52 ms after the reply, and at most 122 ms.
- Through FingertipsMCP on the Android 16 emulator, 82 first opens that reached the stand-in read as `pdf` with the wait in place. So did 27 of 27 without it, so that burst shows the wait does no harm rather than that the failure is gone. The raw tries show that.
- The wait was not enough. On the Android 17 emulator with Chrome 145, with the wait, one of 100 first opens still failed `No node with given id found`. Chrome drops the `nodeId`s it has given at `DOMContentLoaded`, which can come just after the commit. With a page held on a script that the Fetch domain paused, a `nodeId` taken before `DOMContentLoaded` failed `DOM.resolveNode` after it 20 of 20 times on each of Chrome 109, 133, and 145. The same document's `backendNodeId` resolved 20 of 20 times, and `Runtime.callFunctionOn` worked on the handle. So `PdfReader` waits for the commit, since a backend id taken before it would name the old document, and resolves the backend id.
- Against a 1 KB PDF from the Mac, many first opens failed `cdp_error` `DevTools closed the WebSocket` in 0.1 to 0.65 s, with or without the wait: 14 of 40 without it, and 46 of 129 with it, on Chrome 133, and 9 of 40 on Chrome 145. Chrome closed the saved tab during `listedId`'s `Target.getTargetInfo`. In all 9 on Chrome 145, logged after the drop, the tab was off `Target.getTargets`, and a new connection was refused. So a session that drops now reconnects to see whether Chrome refuses, and answers `not_found` if it does.
- With both changes, 110 of 110 first opens read as `pdf` on Chrome 145, and 79 of 80 on Chrome 133. The one that failed was the first of its burst, a 503 from the wireless debugging port lookup, which came right on the next open. Nothing reached Downloads on Chrome 145. On Chrome 145 the stand-in also read with the wait in place, so `Page.frameNavigated` reaches its session there.

On the Android 11 test phone on 2026-11-07, with Chrome 156.0.8078.79 and Chrome in front:

- Over raw DevTools, with the navigation fulfilled as `PdfReader` does, `Page.frameNavigated` reached the stand-in's session with `Page.navigate`'s loader id in 20 of 20 tries, a median of 14 ms after the reply, and 9 to 23 ms.
- With a page held on a paused script, a `nodeId` taken before `DOMContentLoaded` failed `DOM.resolveNode` after it, `No node with given id found`, 20 of 20 times. The `backendNodeId` worked 20 of 20.
- Through FingertipsMCP at 4a18edd, 30 of 30 first opens of arXiv PDFs in a row read as `pdf`, in up to 6.2 s, and so did 30 of 30 of a 1 KB PDF from the Mac, opened in 3.2 to 4.1 s. Nothing reached Downloads. Over raw DevTools, that small PDF's save closes the tab about 40 ms after it starts, so it can race the id lookup. Which opens raced can't be told from outside. Two more arXiv URLs were withdrawn papers, which answer 404 with a page, and read as a page.

Checked through FingertipsMCP on the Android 16 emulator, Chrome 133 with `AndroidOpenPdfInline` on, on 2026-11-05, against 22 public PDFs and the scoring in `pdftotext`. Word recall against `pdftotext` was 95 to 100% for papers in one and two columns, menus, the RFC, a Census report whose table pages carry `/Rotate 90`, an IRS form, a Supreme Court opinion, slides, a designed annual report, and Chinese and Arabic reports. Running headers and footers, which `pdftotext` keeps, account for most of the rest.
- Bookmarks gave the outline where a PDF has real ones: every bookmark of the papers, the RFC, and the RISC-V manual came out as a heading at its depth, and 803 of the RP2350 datasheet's 1168, the rest being past the 2,000,000-character cap. Census and a menu had bookmarks that name nothing on the page, so they used the size and numbering rules.
- The first read of the 1371-page RP2350 datasheet took 12.4 s in 2 s steps; a find after it took 0.4 s.
- A menu whose site sends `Cross-Origin-Resource-Policy: same-origin` failed the second fetch with `net::ERR_BLOCKED_BY_RESPONSE`. The RISC-V manual's release URL is sent as an attachment, so Chrome saved it and the tab was left on `about:blank`. The 23 MB and 55 MB files were refused as over 20 MB.
- A scanned manual has no text, and a gazette whose glyphs are drawn as outlines has none either. An Arabic card whose font maps glyphs to the wrong Unicode reads as fragments, as it does in `pdftotext`.
- The second fetch went out as `Sec-Fetch-Site: cross-site`, `Sec-Fetch-Mode: no-cors`, with no `Referer` and no cookie set `SameSite=Lax`, `Strict`, or with no SameSite, while the navigation that showed the PDF sent all three.
- A find for "the" in the datasheet's 2,000,000 characters took FingertipsMCP from 190 MB to 1.3 GB resident in a second, and the low-memory killer stopped it. Kotlin's `Regex.findAll` makes a `Matcher` for each match, and Android's ICU-backed `Matcher` keeps its own copy of the text in native memory, so 10,000 kept matches held about 40 GB of copies before the kill. Walking one `Matcher` kept the peak at 182 MB.

Checked through MCP on the Android 16 emulator with Chrome 133 on 2026-11-06:

- With Chrome's default of saving, `url` reads of a PDF printed from HTML and of a small hand-made one returned their text in 2.6 s, with a new tab holding the read. Two earlier reads of the hand-made file came back with 0 characters and no error, and why was not found.
- Five `url` reads in a row with `close` true: two worked, the third failed `took_screen`, and the fourth and fifth failed `tab_limit`, though no tab of theirs was left open. So a read that Chrome saves leaves something counted against the session. What was counted is the owner of a tab Chrome had closed itself: `tab_limit` counted owners, which nothing removed. On 2026-11-06 on the Android 13 and 16 emulators, with four tabs open, two closed over raw DevTools, `GET /v1/sessions` read 2, two more opened, and the next open failed `tab_limit` with four really there. The PDF reads themselves were not run again.
- With `AndroidOpenPdfInline` on, a PDF that took 17 s to answer read as `pdf` in a hidden tab, and the server saw one request for it. A `url` read of it gives up at the 12 s load wait, with `not loaded after 12 s`, so the tab has to be read again.
- After a service worker claimed the page, a PDF on that origin failed `navigate` with 400 `net::ERR_ABORTED`, and a read failed in 0.97 s with `page_error` `Chrome did not open the PDF's page: net::ERR_ABORTED`, not `pdf_failed`. That worker only passes the fetch on. The server saw one request.
- A `url` read of a page that first answered 3 s or 8 s in returned the page, with no error at the commit. One that took 40 s said `not loaded after 12 s`.
- Through Readability, an article kept its byline, published time, paywall flag, and JSON-LD, and text under `display:none` stayed out. A list of 40 links read as a page. `browser_find` found links inside a table.

With a tab's open heard only on the browser session, as [Events for a new tab](#events-for-a-new-tab) says, on 2026-11-07, against a 1 KB PDF from the Mac, which earlier failed 46 of 129 first opens on Chrome 133 and 9 of 40 on Chrome 145:

- On Chrome 133, 45 of 45 first opens in front and 10 of 10 hidden read as `pdf`, in 1.1 to 2.6 s and 0.6 to 0.9 s. Nothing reached Downloads, and `ActivityTaskManager` logged no start of Drive.
- On Chrome 145, 102 of 115 in front and 10 of 10 hidden did. The 13 that failed were one run of 500 `internal`, as [The listener's socket](#the-listeners-socket) says, and the next 90 had no failure.
- A PDF on a server that took 14 s came back with `loading` true at 12.1 to 12.7 s, and its save was cancelled when it began. The tab then closed, and nothing reached Downloads, on Chrome 91, 109, 133, and 145, with Chrome hidden and, on Chrome 133, in front.
- On Chrome 91 and 109 the save is now cancelled too, since `Browser.downloadWillBegin`'s `frameId` is the tab's own target, which `Target.createTarget`'s number never matched. Nothing reached Downloads in 25 first opens on Chrome 109 and 10 on Chrome 91. The read then fails: Chrome 109 lacks `Promise.withResolvers`, which pdf.js needs, and answers `pdf_failed`, and Chrome 91 fails with `page_error` as below.
- A PDF sent `inline; filename=statement`, with no extension, was named `statement` by `Browser.downloadWillBegin` on Chrome 109, 133, and 145, and saved as `statement.pdf`. So its save was left alone, and on Chrome 133 and 145 a stand-in read it too. `Browser.downloadProgress` gives a type no more than the start does: its `filePath` at the end is a `content://` address.
- A file sent as `application/octet-stream` failed `pdf_failed`, not `download_saved`, on Chrome 133 and 145, since a stand-in takes that type for a PDF until pdf.js refuses it.

On the Android 10 emulator with Chrome 91 on 2026-11-06, the takeover of an arXiv PDF answered its pause and loaded the stand-in page, and the script then failed with `SyntaxError: Unexpected token '{'`. So Chrome 91 can't run the PDF scripts, which is taken to be syntax newer than it, though the token's place was not looked up.

### Still to check

- What opens Chrome's viewer. On 2026-11-07 neither a new tab nor a navigation reached it, through a 193-byte PDF or arXiv's, on Chrome 133 with `AndroidOpenPdfInline` on, which `chrome://version` showed, or on Chrome 145. Both saved the file, as a new tab on Chrome 145 had before, though both showed the viewer on 2026-11-05.
- How long a long PDF takes to read on the phone, against DevTools' 15 s call limit. Also `browser_read` end to end through FingertipsMCP on Android 17, which was checked only over raw DevTools. Also a PDF from a site whose service worker answers navigations, which may answer before DevTools can pause it.
- Opening a tab, past what this section says of Chrome 156 on the Android 11 test phone: a PDF sent as an attachment, and whether the viewer's `chrome-native://pdf/link` commit sends `Target.targetInfoChanged` with a title for a tab opened on its URL, and `Page.frameNavigated` for one that navigates. Without those, a viewer reads as a save, and the file goes to a stand-in or, in an incognito tab, fails `download_saved`. A hidden tab's `document.readyState` read in an isolated world for `browser.settled`, with `Page.loadEventFired` arriving for a load that finishes after it, and on a paused tab.
- On Chrome 145, `loaderId` from `Page.getFrameTree`, and the PDF takeover. On Chrome 133, whether `Runtime.callFunctionOn` works on a `DOM.resolveNode` handle in the stand-in and `Page.navigate` returns only after the fulfil were not looked at apart from the reads that depend on them. A site whose service worker answers the navigation itself. A quick PDF through Chrome 133's viewer: only the 17 s one was seen to return text, since the two `url` reads of a quick one were cut off in the test's output.
- That a read with `show` of a PDF tab read before reports `shown` true on the phone, now that each read reports the page's visibility as it is then. Before that, a later read repeated the first read's.

## Starting Chrome

Chrome opens `chrome_devtools_remote` only while it runs. A tab open with Chrome stopped starts it from its launcher entry, as tapping its icon would, and waits for the socket, as [sharing.md](sharing.md) describes.

Checked on the Android 16 emulator with Chrome 133 on 2026-10-31, after `am force-stop com.android.chrome`:

- The socket appeared and `POST /v1/browser/tabs` returned in 2.1 to 3.2 s over four runs, and MCP `browser_open` in 2.0 s. Chrome restored its earlier tabs as it started.
- A session without the screen got it for the open and gave it back. Cleanup sent Chrome home about 5.5 s after the open returned, the same as after an incognito open. One that held the screen kept it.

Chrome can run with no tab and no activity. On the Android 16 emulator on 2026-11-06, with every tab closed, the launcher in front, and two Chrome processes up, `/json/version` answered, `/json/list` and `Target.getTargets` were empty, and `/json/new` said `Could not create new page`. `GET /v1/browser/targets` gave an empty list, and `POST /v1/browser/tabs` gave 502 `cdp_error` `Not supported`, since the socket is there and nothing starts Chrome. Launching Chrome made opens work again. Taking the helper down left the tab list empty the same way once.

On Chrome 109 on 2026-11-07 that state follows from closing the last tab, whether FingertipsMCP closed it or Chrome did after a cancelled save. The process and socket stay up, `dumpsys activity activities` soon has no Chrome activity, `/json/list` and `Target.getTargets` are empty, and `Target.createTarget` answers -32000 `Not supported`. The activity ends about 1 s after the tab closes:

- A tab made 0.3 s after the close was accepted, and destroyed at 1.0 s. The same with `am start` of the launcher entry at once, which took 74 ms and changed nothing, so a launch in that second is lost. A tab made within 30 ms of the close stayed, and the activity with it.
- `am start` 0.8 s after the close took 1.4 s, and the tab made after it stayed. On a Chrome already at `Not supported`, it took 3.4 s and made no tab of its own.
- So a PDF on a cleared Chrome failed 404 `not_found` at 5.2 to 6.2 s, 2 of 2: the cancelled save closed the only tab, and the stand-in went with the activity. Open, close, open from a session holding the screen failed `Not supported` 3 of 3. A session without the screen didn't get there, since cleanup's placeholder was still open when its tab closed.

FingertipsMCP leaves this alone, since Chrome below 125 gets only easy fixes. Chrome 133 and 145 did not end the activity that day: with every tab closed over DevTools, in front or with the launcher in front, `Target.createTarget` worked 4 s later. A fix that started Chrome whenever it had no tab would have brought those forward for nothing. Chrome 91, 133, and 145 start a cleared profile on a new tab page, so an open and close on one doesn't reach the last tab. How the Android 16 emulator reached `Not supported` on 2026-11-06 was not found.

An open right after an open that started Chrome fails 409 `took_screen`, naming the launcher, 3 of 3 on Chrome 133 after a force stop on 2026-11-07: the first open's screen is given back during the second, and its cleanup sends Chrome home.

The emulator process itself died twice on 2026-11-07 around `am force-stop` of Chrome, on the Android 10 and 13 emulators, both in `EglContext::~EglContext` in gfxstream. So a run that force-stops Chrome often needs to expect that.

The first tab open after a cold boot failed once on each emulator that day: on Android 16 with 503 `cdp_error` about the wireless debugging port at 19.4 s, while the restart loop was turning the setting off and on, and on Android 13 with 502 `Could not open Chrome's DevTools socket` at 2.5 s. The next opens worked. Three more cold starts on Android 13 took 2.0 to 2.4 s.

That failure was still there on Chrome 109 on 2026-11-07, as 5 of 18 first opens right after a reboot, the helper having just started, each 502 `Could not open Chrome's DevTools socket` in 3.2 to 4.7 s. The next opens worked. The message is wrong about the cause: the one caught had `SocketException: Connection reset` on a new wireless debugging connection, and Chrome's socket was up. adbd had accepted a connection and closed it 2 ms earlier, then accepted FingertipsMCP's on the same file descriptor, and the reset came 10 ms later. `CdpForward` tries again only on a connection it has used before.

On the Android 11 test phone on 2026-11-06, with Chrome 156, an open with Chrome stopped took 3.3 s and showed no first-run screen. With it running, an open took 0.5 s.

### Events for a new tab

Over raw DevTools on 2026-11-07, on a browser session with `Target.setDiscoverTargets` and, for saves, `Browser.setDownloadBehavior`, against pages and a 193-byte PDF from the Mac. Times are from the call that started it. Chrome 91 got fewer tries, and "Not run" marks what it skipped. [Target events](#target-events) has Chrome 156. A later boot of Chrome 109 had its GPU process crashing, so only order comes from that one.

| | Chrome 91 (Android 10) | Chrome 109 (Android 13) | Chrome 133 (Android 16) | Chrome 145 (Android 17) |
| --- | --- | --- | --- | --- |
| `targetCreated` for `createTarget`, blank or a URL | Before the reply, 5 of 5, 6 to 53 ms | Before the reply, 29 of 29, 3 to 87 ms | Before the reply, 29 of 29, 3 to 101 ms | Before the reply, 29 of 29, 3 to 35 ms |
| `targetId` against the reply | Differs: the reply is the tab's number | Differs: the reply is the tab's number | The same | The same |
| Targets per tab `createTarget` makes | Two: its own, then its number | Two: its own, then its number | One | One |
| `url` in its own `targetCreated` | The URL | Empty | Empty | Empty |
| `targetCreated` for a `VIEW` intent | Not run | 115 to 135 ms, its own target only | 116 to 312 ms | 98 to 139 ms |
| `targetDestroyed` after `Target.closeTarget` | 33 to 39 ms | 20 to 124 ms | 84 to 276 ms | 25 to 94 ms |
| `targetDestroyed` after a cancel closes the tab, in front and hidden | 57 to 75 ms, 2 of 2 | 10 to 387 ms, 10 of 10 | 22 to 448 ms, 10 of 10 | 9 to 95 ms, 10 of 10 |
| `targetDestroyed` after a save with no cancel | Not run | 189 to 388 ms | 174 to 469 ms | 284 to 489 ms |
| `downloadWillBegin`'s `frameId` | The tab's own target, not the reply | The tab's own target, not the reply | Both, being one id | Both, being one id |
| `targetInfoChanged` with no connection to the tab | One, at the commit, with a title | One, at the commit, with a title | One, at the commit, with a title | One, at the commit, with a title |
| The commit of a page that took 1.5 s | 1566 ms | 1532 to 1534 ms | 1529 to 1531 ms | 1527 to 1540 ms |

- A page connection dropped within 6 ms of `targetDestroyed`, on either side of it, on Chrome 109, 133, and 145. After `targetDestroyed`, a new connection was refused, 43 of 43 on all four, and the tab was off `Target.getTargets`.
- The number is a target of its own: `targetCreated`, `targetInfoChanged` when a connection attaches, and `targetDestroyed` all come for it, and `Target.getTargets` leaves it out. A tab Chrome made itself, such as from an intent or at startup, has no number.
- Discover replays every tab before its reply, with `attached` false and the page's own title, on all four, numbers included. A second call replays nothing. So the replay has more targets than `Target.getTargets`, and is told from a new tab by coming before the reply.
- With Chrome 109 and 91 opens running four at a time on separate sessions, the tab's own target came just before its number in each session's order, 60 of 60, checked against which id `Target.getTargets` gave that tab's URL once it loaded. `Target.getTargetInfo` on the browser session echoes the number. That order does not hold on a Chrome just started, as the next point says, so the page on `Target.getTargets` that discover did not replay names the tab instead.
- On Chrome 109 started from its launcher entry after a reboot, on 2026-11-07, the tab's own target was already there before `Target.createTarget`: discover replayed it with `type` `other`, an empty URL, and the normal browser context, and `/json/list` had it too. `createTarget` then announced only the number. No event said the target had changed. `Target.getTargets` right after the reply listed it as a `page` on the new URL, and the commit's `targetInfoChanged` came under its id. Through FingertipsMCP with the first order assumed, an open failed 502 `Chrome did not name a new tab's target`, and the target that was closed with the number was this one. With the tab named from `Target.getTargets` less what discover replayed, no first open failed that way: 13 of 18 reboots worked with a build that diffed the list instead, and the other 5 are the reset under [Starting Chrome](#starting-chrome). That build also passed 4 of 4 reboots on Chrome 91. The replay version was checked only in unit tests, since testing below Chrome 125 stopped that day. Started with `-d about:blank` after a reboot, the same Chrome announced the tab's own target as usual, once. What the `other` target is for was not found. It has no tab until the open takes it, which fits `other` being what Chrome calls a page with no tab.
- A connection to the tab adds `targetInfoChanged` as it attaches, with `attached` true and an empty title, 30 to 250 ms in. Its `url` is the one being loaded, so `url` does not mean a commit. On a tab opened on its URL a `title` does: only the commit's event had one, on all four and on Chrome 156 from [Target events](#target-events). It is the host until the page sets one, and the page's own title sends nothing.
- `Target.targetCrashed` came before `targetDestroyed` on Chrome 91 and 109 when a tab with a connection closed, and in some saves. Nothing crashed.
- A failed load counts as a commit by that event: a refused connection sent it at 224 to 438 ms, with the error page, and a 404 at 250 to 477 ms. A 204 sent nothing, and the tab stayed.
- On a blank tab that then navigates, with `Page.enable` on, the title came 12 to 57 ms after `Page.frameNavigated` on Chrome 145, and 3 to 23 ms before it on Chrome 109. The blank page's own commit sends one too, at 120 to 610 ms, so it could come after a navigation has started. That is why such a tab goes by `Page.frameNavigated`'s loader id.
- A new connection to a tab opened on its URL got no answer until the commit, on Chrome 109, 133, and 145, as on Chrome 156. Against a server that took 20 s, `Page.enable` answered at 20.1 s on Chrome 133 and 145, past the 15 s FingertipsMCP gives a DevTools call. `Page.frameNavigated` did not reach that session on Chrome 133 and 145, and did on Chrome 109. On Chrome 133 a connection made 12.5 s into a 20 s load was held the same way, with or without an earlier connection that had closed.
- A tab that navigates from a page it has committed is not held. On Chrome 109, a new connection to an incognito tab 4 s into a navigation answered `Page.enable` in 3 ms, with the frame on `chrome-native://newtab/`, `complete`. That is a page a wait must not take for the one loading.
- On that tab the title went `chrome-native://newtab`, then `New Incognito tab` with no event of its own. It showed at the next connection coming or going, and those events carry the URL being loaded. So a change of title since the last event is not a commit there. The commit was the first title other than what discover's replay names.
- A save that starts late is heard: with a server that took 14 s, `Browser.downloadWillBegin` reached a session held open at 14.0 to 14.1 s, and `Browser.cancelDownload` then closed the tab.
- `Page.navigate` on a blank tab for a PDF answered `net::ERR_ABORTED` on Chrome 109, 133, and 145, with `isDownload` true only on Chrome 145, and `downloadWillBegin` came 3 to 128 ms after the reply. That tab stays open after a cancel, since it holds a committed page.
- An incognito tab reached the browser session like any other on Chrome 109 and 145: `targetCreated` at 309 to 465 ms, `targetInfoChanged` at its commits, and `downloadWillBegin` for a save, with `eventsEnabled` set on the default context. `setDownloadBehavior` with that tab's `browserContextId` failed `Failed to find browser context`.

Through FingertipsMCP with its opens built on these events, on 2026-11-07:

- Plain opens read as the page they asked for: 20 of 20 on Chrome 91, 40 of 40 on Chrome 109, 9 of 10 on Chrome 133, and 130 of 130 on Chrome 145, in 0.1 to 1.7 s. On Chrome 91 and 109, where the ids differ, each returned the id `GET /v1/browser/targets` lists. The one that failed was the first on Chrome 133, a 503 while the port lookup ran. A page that took 3 s returned at 3.1 to 3.3 s.
- A page that took 20 s returned `loading` true at 12.2 to 12.4 s on all four, and read as the page at 21 s.
- `capture` and incognito opens of a page returned the page on all four. An incognito open of a PDF failed `download_saved` in 2.8 to 3.4 s.
- A wait on a tab that came back `loading` connects only once the server has answered. With a server that took 20 to 40 s, `browser.settled` matched 0.5 to 1.6 s after it answered: on Chrome 133 for a plain open and incognito, on Chrome 109 for a plain open, `capture`, and incognito, 3 of 3, and on Chrome 145 for all three. `browser.loaded` on Chrome 133 did too. Before that, on Chrome 133, a `browser.settled` wait failed 503 `cdp_timeout` at 15 s, its `Page.enable` being held. On Chrome 133, 3 s waits while the server stayed silent timed out with no match.
- A call that uses the page of such a tab fails 409 `content_unavailable` at once. On Chrome 133 and 145, during a 20 s server's silence, `read`, `find`, `evaluate`, and `snapshot` each answered in 4 to 17 ms. Then `browser.settled` matched at 20.8 s, and `read` returned the page. Before that, `read` on Chrome 133 waited for the commit, and failed 503 `cdp_timeout` at 15 s when the server took longer. The refusal lasts only while the open's watch runs, so a tab nothing listens for any more is read as before.

On the Android 11 test phone with Chrome 156.0.8078.79 on 2026-11-07, at 9e047f7:

- 30 of 30 first opens of arXiv PDFs in a row with Chrome in front read as `pdf`, opened in 3.9 to 6.6 s. So did 30 of 30 of a 1 KB PDF from the Mac, in 3.6 to 4.2 s. Nothing reached Downloads, and in the 45 opens where it was watched only Chrome was on top. Google Drive is installed there.
- Plain opens came back committed, with no `loading`, hidden and in front: six public sites and a local page in 0.41 to 0.79 s, a server that took 3 s in 3.3 s, a page with no `<title>` in 0.43 to 0.58 s, and a refused connection in 0.48 to 0.55 s, as Chrome's error page. With the screen off, 9 of 9 fast pages, 3 of them with no `<title>`, came back in 0.51 to 0.59 s. So a page with no title still gets the title signal, since Chrome gives the host.
- A hidden read of one site right after the open was 200 with 0 characters, with `document.readyState` `interactive`. That is the page still building, which `browser.settled` waits for.
- 120 opens and closes in 3 parallel sessions with Chrome hidden, a quarter of them PDFs, all worked in 195 s. Plain opens took 0.57 s at the median, and PDFs 11.6 s, 16.7 s at most, against 6 to 7 s alone.
- 90 more with Chrome in front and no holder ended with 78 working. 11 plain opens failed 409 `took_screen`, and 1 failed 409 `tab_stuck`. None of the 21 PDFs failed.
- None of those 210 opens failed 500 `internal`, and the log held nothing from okio or `AdbWriter`.

### Still to check

- Whether a first-run or sign-in screen keeps Chrome from opening its DevTools socket after a tab open starts it.
- A late save with Chrome in front, and whether its cancel closes Chrome's download dialog. With Chrome hidden, a save that began at 16 s was cancelled, the tab closed, and nothing reached Downloads.
- The title at a commit on a Chrome that shows a PDF in its viewer, as under [PDFs](#pdfs).
- A wait on a tab that came back `loading`, on the phone's Chrome 156. There a `browser.settled` wait connected to such a tab at 4a18edd and matched, and the tab read as `about:blank`, so its connection was answered, which no emulator reproduced for a tab opened on its URL. It should now wait for the commit. Why that connection was answered while a raw one at the start of a load was held was not found. So FingertipsMCP's own connection may have been held at the start too, or not.
- A call to a tab that came back `loading`, on the phone, where `read` returned 200 with no text at 4a18edd. It should now fail `content_unavailable` until the commit.
- Why adbd resets a new wireless debugging connection after a boot, whether it does on a phone, and whether an open should reconnect once for it.
- What happens to Chrome 156's activity when its last tab closes, and what the `other` target on a just-started Chrome 109 is for, and whether Chrome 91 has one. Chrome 91's reboots named their tabs either way.

## The top task

FingertipsMCP asks for the top task before and after calls that could launch an app. The first build ran `dumpsys activity activities`. It took about 8 ms on the emulator but 90 to 140 ms on the phone, where any `dumpsys` costs that much to start, adding about a quarter second to each background input. The helper now calls `IActivityTaskManager.getTasks` directly, choosing the overload as it does for input injection, and falls back to `dumpsys` if that call fails. The same call answers the "is Chrome in front" checks behind the placeholder tab.

`getTasks` takes `(int, boolean, boolean, int)` on Android 16, and `(int)` on Android 10 and 11. Checked on both emulators and the phone on 2026-10-22, where `show` brought Chrome forward with it.

## Clipboard

Android 16 reports one clipboard write as two clipboard changes, a second apart, so a change count does not identify a clip. FingertipsMCP notes the timestamp Android gives each clip it writes instead. As root, the emulator refuses the helper any clipboard access.

On the Android 16 emulator on 2026-10-29, with the helper as the shell user, cleanup cleared FingertipsMCP's own clip, left a clip copied after it, and left the clip after `cleanup` false. On 2026-10-31, text a person pasted in the viewer was still on the clipboard after the viewer let go, and the next session read it as empty. A session read its own write back, found the clipboard empty after a person took over and handed the screen back, and a session that got the screen after a release with `cleanup` false found it empty too.

### Still to check

- That the shell user can clear the clipboard through `IClipboard.clearPrimaryClip` and read `getPrimaryClipDescription` on Android 11.
- That a session finds the clipboard empty on Android 11 after a person copied something, and that the clear leaves no toast or visible change.
- That clipboard images go both ways on Android 11, checked so far on the Android 10 and 16 emulators. Also that the helper can read an image another app copied, which the emulators' Chrome would not put on the clipboard.
- That the viewer's copy and paste work from a real keyboard in Chrome, Safari, and Firefox, including the second Cmd+C that Safari and Firefox need when the page guessed text and the phone held an image.

## Audio capture

`AudioRecord` names the app that records. Android 11 and 12 take the name from `ActivityThread.currentOpPackageName`. Android 13 and later ask the current application for its `AttributionSource` instead, so the helper's stand-in application has to answer that too. Without it the helper threw a `NullPointerException` in `AudioRecord`'s constructor and no audio stream started. The AOSP sources for Android 13 through 16 all take that path; only Android 16 was run.

On the Android 16 emulator on 2026-11-05, with the helper answering as `com.android.shell` and before the app skipped silence or joined packets, `/v1/stream/audio` sent about 50 Opus packets a second. They were 12 bytes while nothing played and 156 to 286 bytes while Chrome played a 6 s 440 Hz tone, for those 6 s.

On the Android 11 test phone on 2026-11-06, `/v1/stream/audio` sent its header 1.6 s after the socket opened, and the foreground app stayed Chrome, so the shell activity's start and `removeTask` left it as it was. One packet came in 3 s of silence. A 0.6 s 440 Hz tone from a page came as 13 packets of 170 to 534 bytes over 0.64 s. The audio itself was not decoded. Closing the socket logged `audio reader stopped`.

### Still to check

- That a sound comes through whole on Android 11, without its start or end clipped, once decoded. That checks the Opus encoder's output timestamps against its input.
- Restarting for a second viewer on Android 11 and later. On the Android 13 emulator each viewer got a header and one packet and the reader stopped cleanly, but the tone never started. The Android 11 audio start: how long the first one after a boot takes, what a locked phone does to the wait, and which of `setProcessImportant` and the activity lets `startRecording` attach.

## Screen hand-offs

Checked on the Android 16 emulator:

- 2026-10-29: a REST session brought a page on screen, released with `cleanup` false, and ended. Chrome stayed in front and unlocked, and the tab stayed open and unowned. A viewer took the screen, which woke it when it was off, and typed into the page. When its socket closed, nothing was cleaned up. The next session found the tab in `browser_targets`, read what the viewer typed, and got the note that a person had used the screen.
- 2026-10-30, with a swipe keyguard: a session that unlocked and released to a waiter left it unlocked, with a note saying so. The waiter's release with no one waiting locked it. A phone unlocked by hand stayed unlocked after a session took and released it.
- 2026-10-30: a session's wait for `person` true matched within 0.2 s of a viewer's take, and the wait for `person` false matched when the viewer's socket closed. Neither took the screen. A session that held the screen kept holding it through both waits and got it back when the viewer let go.
- 2026-10-22: a tab Chrome opened for `open_intent` became the caller's, and another session did not see it.

With a viewer on the Android 16 and Android 13 emulators on 2026-11-06:

- A person's take during an agent's touch swipe left no finger down in `dumpsys input` a second later, and the swipe got 409 `screen_taken`.
- A session's own read of its tab during a take hid the page. Before the take and 3 s after it the tab was `visible`. By the read after that it was `hidden`, and it stayed hidden after the viewer let go. Taps from a viewer stopped reaching the page, an `adb` tap too. With no call from the session, all five of a viewer's taps reached the page. This was the same on Chrome 133 and Chrome 109. Watching over raw DevTools showed reads were not the cause: `read`, `find`, `snapshot`, `console`, and listing left the tab `visible`, and the first cdp key or `evaluate` selected the placeholder. With FingertipsMCP selecting nothing for a person, the same run on both Chromes on 2026-11-06 refused those two on the tab on screen with `screen_taken`, took a key on a hidden tab, refused an open, and the tab stayed `visible` through the viewer letting go.
- A tab held by an alert did not come back after a handover: cleanup went home, and the next session's launch of Chrome showed `about:blank`.
- A read of a tab held by an alert failed `content_unavailable` after 15.1 s.
- On Chrome 109 a `touch` tap with the tab switcher open returned ok and the click was lost, once. It did not happen again on 2026-11-06, in 35 tries on the Android 13 emulator: a tap on the tab Chrome had selected and on another, raw taps 0.08 to 1 s after `Page.bringToFront`, humanized or not, with six busy processes in the emulator and host load about 25. All 35 clicked. With the switcher open the tree has `tab_list_view` and no `WebView`. `Page.bringToFront` closes the switcher on Chrome 109, and the `WebView` is back within 0.28 s, so the check that the page is on screen passes for the right reason. Chrome 133 leaves the switcher up, and the tap is refused. So the lost click is not explained, and nothing was changed for it.
- `browser.dialog` with an alert up matched in 77 ms with the screen on, on Android 16. On Android 13 the same wait, with a 4 s limit, failed 503 `cdp_timeout` at 15.1 s. With the screen off the wait never turned it on, on both. There it did not match: it timed out at 4.0 s twice, and failed `cdp_timeout` at 15.1 and 15.6 s on Android 16 with a keyguard up.

A session's own read of its tab during a take hid it on the Android 11 test phone on 2026-11-06 too, and taps then landed on the placeholder.

On the Android 11 test phone on 2026-11-07, after `screen/release` with `cleanup` false and no viewer, the tab stayed `visible` for 3.6 s with no calls, read over raw DevTools. The next `evaluate` hid it at once, and the placeholder became `visible`, 2 of 2 times, and a `read` left it alone. So the earlier `cdp` tap that failed `not_visible` found the tab already hidden, by the test's own `evaluate` before it. 8 of 8 tries with an `evaluate` after the release showed the tab hidden after it.

At 9e047f7 on the same phone, with the tab's state read over raw DevTools, the kept tab stayed `visible` through the session's next `evaluate`, 5 of 5. A new tab went behind it, 5 of 5. A popup from a `cdp-mouse` tap, which takes no screen, left it `visible` over the next 1.7 s and through another `evaluate`, 3 of 3. A `cdp` tap takes the screen back, 3 of 3, so after one the session is the holder: a new tab or a popup then shows, 5 of 5 each, as for any holder.

### Still to check

- The lost click on Chrome 109 with the tab switcher open, as above, which did not come back in 35 tries.

## Video and audio streams

Measured on the Android 16 emulator on 2026-11-05, with the `C2SoftAvcEnc` encoder:

- On a still home screen a viewer got the key frame it joined with, redraws about 100, 300, and 700 ms after the last mirrored frame, then one frame every 4 s. A second viewer joining got a key frame within 10 ms, and so did the first. The idle frames were deltas of 1 to 30 KB, so the encoder kept refining the still picture.
- The encoder counts `KEY_I_FRAME_INTERVAL` in frames at the configured `KEY_FRAME_RATE`, not in seconds. With 10 s and `fps=60`, a page animating at about 29 fps got timer key frames every 20 s.
- adb buffered several MB per stream between the app and the client. A viewer that stopped reading, or read at 12 fps beside a 30 fps stream, never filled FingertipsMCP's 4-frame queue. Ktor's ping timeout closed it after about 45 to 50 s instead. So the queue's drop and key-frame recovery did not run on the emulator. Its unit tests cover it.
- Audio capture failed before the stream started until the helper's stand-in context answered `getAttributionSource`, as [Audio capture](#audio-capture) describes. The Android 10 emulator has no submix capture.
- A 6 s tone played in Chrome came through as 102 messages: 101 Opus packets of three 20 ms frames, 60 ms apart, and one single frame sent as silence started. Nothing was sent before or after the tone. Apple's AudioToolbox decoder, which Safari's `AudioDecoder` uses, decoded them to 6.08 s of audio, sample for sample the same as the frames split back into 20 ms packets. Chrome's decoder takes packets up to 120 ms. Firefox was not checked.

Rotating while a viewer streamed, on the Android 16 and Android 13 emulators on 2026-11-06, without the display poll: new headers came 0.2 to 1.1 s after each of 90, 180, 270, and 0, and a viewer's tap at the screen's center reached the page at each, with a `stream,control` token. With the host loaded (load average 29), one header took about 8 s, and the log showed `relay release`, `The relay thread did not answer`, and `releaseOutputBuffer() is valid only at Executing states`. No viewer was closed. `wm size` changes no display mode, so no header came and `/v1/device` kept the physical size, while Chrome's viewport did change. The emulators have one display mode, so a real resolution change was not run.

On the Android 11 test phone on 2026-11-06, video at `maxSize` 720 was H.264 High, 338x720, with its first frame at 0.8 s, and 27 of 29 frames decoded in `ffprobe`. A PNG screenshot took 0.8 to 1 s.

On the Android 16 and Android 17 emulators on 2026-11-08, with the refusal reasons coming from `DeviceInfoProvider`:

- With the helper running, `capabilities` had `stream.video.mirror` and `stream.audio.submix` true. The video socket sent its header and 6 to 10 frames in 4 s of a still home screen. The audio socket sent its header and one packet in 4 s, on emulators started with `-no-audio`, and the helper logged `audio reader stopped` when it closed.
- With the helper killed, both capabilities read false, and the video socket closed with 1003 `Video needs the shell helper` and the audio socket with 1003 `Audio needs Android 11 or later and the shell helper`, each within 0.2 s and before any message. On the Android 17 emulator the app started the helper again over wireless debugging 0.55 s after the kill, so the sockets were opened right after it. On the Android 16 emulator it did not, since wireless debugging's port was not found then.
- `GET /v1/screenshot` with `scale` 0.1 returned a 108x240 PNG of the 1080x2400 screen, and 0.09 and 1.01 got 400 `bad_request` `scale must be 0.1 to 1.0`. MCP `screenshot` did the same.

### Still to check

- That a still screen on the Moto G Power looks sharp after scrolling, with the relay's settle redraws in place of `KEY_REPEAT_PREVIOUS_FRAME_AFTER`, and sends a frame every 4 s.
- That the viewer in Firefox plays the 60 ms Opus packets the phone sends.
- That a viewer on a slow link pauses rather than smears, and catches up on a key frame. adb's buffering kept the emulator from reaching that path.
- A real resolution change while streaming, which needs a phone with more than one display mode.
- Rotation on Android 10 and 11 and viewer taps on Android 10, since the Android 10 emulator has no video. Android 10 taps in the viewer opening no audio socket.
- That the audio socket refuses with `Audio needs Android 11 or later and the shell helper` on Android 10 with the helper running. Only the helper being down was checked on a device.

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

Chrome keeps the tab selected through an activity start, so FingertipsMCP uses that, as tapping Chrome's icon would. A tap on a collapsed page then took 1.2 s and clicked, and the tab switcher on Chrome 133 still failed `content_unavailable`, with no start. `clear` read the page's node as an empty field, since it was the focused node and has no text, so it reported success and typed after the old text: on the same emulator a field holding `seed text` ended as `seed textNew Text`, as it did on the phone. With a node that takes no text counting as no field, and a browser text call with `clear` or `setText` bringing the nodes back first, all four ways through ended as `New Text`, in 0.7 to 5.6 s. The keyboard did not come back by itself after the activity start, though the field kept focus.

On the same emulator on 2026-11-07, every call that acts got its nodes back the same way, from the input calls as well as the browser ones. Each row started with a page whose nodes were off and `seed text` in its field:

| Call | Result | Time |
| --- | --- | --- |
| Browser text, `clear`, no selector, `auto` and `keyboard` | `New Text`, by the keyboard, with no `setText` | 9.3 to 9.9 s |
| Browser text, `clear`, no selector, `setText` | `New Text` | 0.6 s |
| `POST /v1/input/text`, `clear`, `keyboard` | `New Text` | 8.4 s |
| `POST /v1/input/text`, `clear`, `setText` | `New Text` | 0.5 s |
| `POST /v1/input/tap` with a page's text as selector | Clicked once | 3.0 s |
| `POST /v1/ime/show`, keyboard hidden, with and without a selector | Keyboard up, 4 of 4 | 0.5 to 0.8 s |

Reads did not start Chrome, and the nodes stayed off after them. `ui/snapshot` said `pageNodesOff`, `ui/find` for the page's button failed `page_nodes_off`, `ui/find` for `url_bar` still matched, and a `wait` for that button to be `absent` timed out rather than matching.

Chrome's address bar is editable, so with the nodes off a search for editable nodes finds it and no page field. That is why the focused field is asked for with `focused` in the selector: a search that filters afterward finds something, so nothing says the nodes are off.

On the Android 11 test phone on 2026-11-07, with Chrome 156 and a dev build at 4a18edd, the nodes were off after a run of calls ending in keyboard typing, 25 of 25 times: open a tab, open a second, show the first, close the second, two taps, tap a field, and type. No step before the typing left them off, in 2 tries of each, with one read at the end. Typing on a lone tab did not, in 14 tries. A `ui/find` of page text just before and after the typing kept them on, 4 of 4, and a snapshot of 5 nodes did not, 3 of 3. A quiet tab read every 3 to 7 s stayed whole for 50 s. So the 5 s after the first read is not all that decides it on the phone. An activity start brought them back, `pageNodesOff` being false in the 14 reads after one, and the page saw `blur` then `focus`, 23 of 23, with no `visibilitychange`.

| Call, with the nodes off | Result | Time |
| --- | --- | --- |
| Browser `touch` tap, keyboard hidden | Clicked, 3 of 3 | 0.4 to 0.5 s |
| Browser `touch` tap, keyboard up | 200, and the tap missed, 9 of 9, before the fix below | 0.5 to 0.8 s |
| `POST /v1/input/tap` by text, keyboard hidden | Clicked, 2 of 2 | 0.3 s |
| `POST /v1/input/tap` by text, keyboard up | 404 `not_found`, with the button under the keyboard, 1 of 1 | 0.3 s |
| `POST /v1/input/text`, `clear`, `keyboard` | Replaced, 9 of 9 | 7.5 to 7.9 s |
| `POST /v1/input/text`, `clear`, `auto` | Replaced, 1 of 1 | 7.7 s |
| `POST /v1/input/text`, `clear`, `setText` | Replaced, 1 of 1 | 0.2 s |
| Browser text, `clear` | Replaced, 2 of 2 | 7.8 to 7.9 s |
| `POST /v1/input/text`, `keyboard`, no `clear` | Typed, 1 of 1, with no start, and the nodes stayed off | 1.5 s |

The missed taps went to the field, a paragraph, or the body. The page saw `blur`, `focus`, and two `resize` events before the touch, which landed at y 1053 to 1191, where the button sits with the keyboard up. With the nodes on and the keyboard up, the same tap clicked 9 of 9 times, at y 1048 to 1131. The start moved the page after the point was taken. With the page's bounds read first, and a wait for the layout to read the same twice, the same tap clicked 9 of 9 times in 0.76 to 0.90 s, on the button at y 1601 to 1717, where it sits with the keyboard gone. The two `resize` events came 293 to 395 ms into the call, and the touch 362 to 426 ms after the last one. A keyboard-hidden tap with the nodes off clicked 3 of 3, in 0.69 to 0.81 s. A tap with the nodes on and the keyboard up clicked 3 of 3, in 0.32 to 0.34 s.

With the box read after the page's bounds, at 0f37952, the same taps clicked: 9 of 9 with the keyboard up, in 0.76 to 0.90 s, and 3 of 3 with it hidden, in 0.69 to 0.81 s. The two `resize` events came 293 to 395 ms after the call started, and the touch 362 to 426 ms after the last of them, on the button. The start still closes the keyboard, and the page sees it.

At 9e047f7, with the nodes off and the keyboard up, the other touch paths landed too. A `swipe` from a selector started on the element and scrolled the page, 3 of 3, in 0.98 to 1.08 s. A `gesture` at the button clicked it, 3 of 3, in 0.63 to 0.67 s. Browser text with a selector typed into that field, 3 of 3, in 2.6 to 2.7 s.

Chrome 145 on the Android 17 emulator did not turn the nodes off that day, on nine tabs, each watched for 15 s after a read. Chrome 109 on the Android 13 emulator, with tabs opened on their URL, passed an open, its listed id, a touch tap, `clear` with and without a selector, `setText`, `ui/find`, and `keyboard_show`, on one tab.

A touch call on a new tab on Chrome 133 failed `content_unavailable` 2 times in about 20 that day, once on the first tab after the helper started and once right after a tab closed. The same call a second later worked. The tree was not read at the moment it failed, so why is not known.

`contentBounds` was also the one read that skipped the node cache's age limit, so a tab just brought forward could read as the page before it. That is the first tap refused in the first paragraph, with the node never read: a first touch on a new tab failed `content_unavailable` in 3 of 5 tries, and in none of 5 once it read through the limit. Chrome 109 did not collapse in these runs. Host load was 20 to 30 throughout, so these times are loose.

A tap by `ref` from `includeInvisible` on a page with `scroll-behavior:smooth` scrolled 8,455 px, waited for the scroll to end, and clicked, in 0.96 s, on the Android 16 emulator on 2026-11-06.

With the app killed and restarted beside a live helper, on the Android 13 and 16 emulators that day, `uiAutomationConnected` read false for as long as it was watched, 331 s on Android 16, though snapshots worked. The helper's connection outlived the app, and the new app was not told. No `UiAutomation idle` line came in over 200 s on Android 13, so whether it idles out then is not settled.

### Still to check

- Whether the first `ui_snapshot` after UiAutomation connects can come back empty, as one did, and should wait for windows.
- A release and a dev helper side by side, with the UiAutomation contention, which needs a release build.
- The occasional `content_unavailable` on a new tab on Chrome 133, as above: what the tree holds when it happens.
- UiAutomation idling out after the app process is killed and restarted, as above.

## The on-screen keyboard

Measured on the Android 10 and 16 emulators on 2026-11-05, typing into a web field in Chrome through Gboard (15.1 on Android 16).

- On Android 16, Gboard relabels its letters without an event that reaches UiAutomation's node cache. After a shift tap the shift key read "Shift enabled" while the cached letters stayed lowercase for over a second. Both `AccessibilityNodeInfo.refresh()` on each node and `UiAutomation.clearCache()` gave the new labels at once. Typing 10 mixed characters took about 5.0 s from the cache, 5.5 s with `clearCache`, and 7.4 s with `refresh` on each node. `clearCache` needs Android 14. Older releases use a hidden call, and refresh each node only if that fails.
- Gboard lists its suggestions among its keys. A suggestion can be one letter, and it has no view id, where a real key's id holds `key_pos_`. Tapping one enters the letter and a space. Gboard 17.2 changed the ids, as below.
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

On the Android 11 test phone on 2026-11-07, at 2acb53e, keyboard `clear` failed 409 `text_not_cleared` in 1 of 16 tries, with the field unchanged, so nothing was typed. The likely cause is the page's nodes being off. The 15 tries that worked ran `ui/find` between steps, which keeps them on. With no such reads, the same steps left the nodes off 25 of 25 times at 4a18edd, where `clear` recovers, as under [UiAutomation](#uiautomation). Each such `clear` took 7.5 to 7.9 s, against 2 s with the nodes on. The failure itself was not run again on the old build.

Typing `www` into Chrome's address bar, on 2026-11-08, on the Android 17 emulator (Gboard 17.2, Chrome 145) and the Android 16 emulator (Gboard 15.1, Chrome 133):

- After a `w`, both show a suggestion strip reading `w`, `what`, `we`, listed ahead of the keys, and keep it up on the symbols page, which has no `w` key. Gboard 15.1 gives its letter keys ids such as `key_pos_0_1`. Gboard 17.2 gives them position codes such as `D02`, `C01`, and `A08`, and keeps `key_pos_` only for keys with a job, such as `key_pos_shift`, `key_pos_space`, and `key_pos_header_power_key`. In both, every key on the letter, symbols, and More symbols pages has an id, and only the suggestions have none.
- On Gboard 17.2, the old build typed `www` as `w w`, in one call and in three calls of one letter each, as the viewer sends them: the second `w` tapped the suggestion. It looked for `key_pos_` in the id, and fell back to the first node labeled `w`. `KeyMatcher.withoutSuggestions` now drops nodes without an id from a keyboard whose character keys have ids. With it, on Gboard 17.2, `www` was exact 4 of 4 in one call, 11 of 12 in three calls, and 3 of 3 through the viewer socket, and `wwwww`, `w.w`, `a@b`, `w1w`, and `Hello, World! 50%` were exact. The one miss was the first run after reinstalling the app, which typed `ww`, and was not seen again in the 11 runs after it. On Gboard 15.1, `www` and `w|w|w` were exact 2 of 2 each, and `a|@` and `Hello, World! 50%` were exact.
- On Gboard 17.2, `Wow, www!` came out `Wow, wew!` in 6 of 12 runs, through the viewer socket and REST alike, always at the middle `w` of the second word. That tap landed on the `w` key, 15 px right of its center and 39 px from `e`, and other taps 1 to 3 px from center stayed `w`. That points to Gboard's own key correction picking `we` from the words before it, though this was not checked further. FingertipsMCP aims a tap anywhere in the middle 60% of a key.
- On the Android 11 test phone on 2026-11-08 (Gboard 18.6, Chrome 156), Gboard names its keys as 17.2 does, such as `D02`, and its suggestions have no id. It labels `.` as `Period`. Before the change, `www` typed as `w w` 4 of 4 times, in one call and in three. After it, `www` was exact 12 of 12 times over REST, half in one call and half in three, and 3 of 3 through the viewer socket. `a|@` and `Hello, World! 50%` were exact. `Wow, www!` came out `Wow, wew!` 2 of 3 times, as on the emulator.

### Still to check

- A keyboard that gives its keys no ids keeps its suggestions in the key list, so a suggestion that reads like a key can still be tapped there.

## MCP SDK

FingertipsMCP uses the Kotlin MCP SDK 0.16.0. A prototype with 0.15.0 on the Android 16 emulator showed, on 2026-10-20:

- `initialize` assigns a random session id. `notifications/cancelled` cancels the tool's coroutine at once. `DELETE` cancels every call in flight, and the session then answers 404.
- The id a tool handler sees, `ClientConnection.sessionId`, is the SDK's own, not the `Mcp-Session-Id` the client holds. FingertipsMCP creates each session's transport and `Server` together, with `setSessionIdGenerator` returning an id it picked, so that id is in the tools' closure. Leases and tabs are keyed by it.
- A call cut off by `DELETE` comes back as HTTP 500 `Job was cancelled`, from FingertipsMCP's error mapping. It should be a JSON-RPC error.
- The SDK's streaming mode answers a POST with an event stream that Ktor closes as soon as the transport hands the request off, before the result, so it does not work without more plumbing. In JSON mode the SDK sends a request's progress on the client's GET stream, and drops it when the client has none. So `/mcp` answers POSTs with JSON, refuses the GET stream with 405, and sends no progress.
- A tool ran to the end after its client dropped the connection, on stateless `/mcp` as well as with sessions. That matches the spec, which says not to treat a disconnect as a cancellation, so clients have to send `notifications/cancelled` to stop a wait. Legwork's Go SDK (v1.8.0) does that when a call's context ends, sends `DELETE` when the session closes, and opens the GET stream after `initialize`.
- Nothing from the SDK, session ids included, reached logcat in a debug build.

The SDK has a stateful transport with session ids, `DELETE`, and open and close callbacks. Since 0.16.0 its Ktor helper `mcpStreamableHttp` expires idle sessions and caps how many are open, but the transport does neither, and the helper still keeps the session table private. So FingertipsMCP owns the `/mcp` route and drives `StreamableHttpServerTransport` itself, keeping expiry, the token check, and release in one place. The SDK makes ids with Kotlin's `Uuid.random()`, which uses `SecureRandom` on the JVM, so FingertipsMCP keeps its generator.

SDK 0.16.0 answers a POST that repeats a request id with 400 `Invalid Request`, where 0.15.0 answered 200. That was seen on the Android 16 and Android 10 emulators on 2026-11-05, for an id sent twice in one POST. The transport's code treats an id still in flight the same way. An id can be used again once its call has finished.

Progress would need one of two things. The session's GET stream already carries it in JSON mode, but clients may skip that stream. Streaming POST responses, which every client must accept, would need FingertipsMCP's own transport on the SDK's `Transport` interface, about 250 to 350 lines, owning session ids, headers, and errors. Wrapping the SDK's transport instead is smaller but depends on its internals.

MCP's stateless revision (2026-07-28) drops protocol sessions: servers that need state across calls mint handles that clients pass as tool arguments, as `session_start` does. SDK 0.16.0 does not speak that revision yet (its latest protocol version is 2025-11-25), so such clients cannot connect, but the tools would carry over.

In a release build on the Android 16 emulator on 2026-11-08, neither an MCP session id nor a REST one reached logcat. FingertipsMCP's own lines there show sessions only by their hash.

## Wireless debugging pairing

Checked on the Android 13 emulator on 2026-11-06, through the notification from start to a running helper:

- Pulling down the shade leaves the "Pair device with pairing code" dialog open, and the pairing service stays up. The shade covers the code, though, so it has to be read first.
- Android folds an app's ungrouped notifications into one group in the shade, where each is a collapsed row. Two were enough. A notification with its own `setGroup` stays out of that group, and at high importance it sits at the top already expanded, even with no group summary, so "Enter code" shows with no expanding.
- A notification posted silently and then updated without `setOnlyAlertOnce` drops down over the dialog, about 2 to 4 seconds after the dialog opens.
- After a wrong code the dialog stayed open, and the code it then showed paired.
- Tapping another notification's row that opens an app closes the dialog.

On the Android 11 test phone on 2026-11-06, the same flow reached a running helper. mDNS found the dialog there, and the code went in through the notification. Android didn't group the notifications, with the listener, status, and pairing ones all up. A silent post updated to an alerting one showed in the shade but did not drop down over the dialog, with Do Not Disturb off. A new post, after cancelling the silent one, did drop down. Android 11's `HeadsUpController.updateHunState` reads as if the update should alert too, so the cause is unknown. FingertipsMCP cancels and posts again.

On the Android 12 test phone on 2026-11-06, Do Not Disturb hid the pairing notification. Its policy included `SUPPRESSED_EFFECT_PEEK` and `SUPPRESSED_EFFECT_NOTIFICATION_LIST`, so the notification existed in `dumpsys notification` but showed neither as a banner nor in the shade. With Do Not Disturb off, a new post dropped down over the dialog and pairing finished through it.

The Android 13 emulator cuts a toast off at two lines, about 75 characters on its screen, so the Do Not Disturb warning is kept short.

`createNotificationChannel` on an existing channel changed its name on that phone and left a channel the user had turned off still off.

Turning off the listener's notification channel on the Android 13 emulator on 2026-11-06 removed the notification, and the system posted none in its place. The service stayed `isForeground=true`, the process stayed `fg-service` at `oom_score_adj` 200, and the socket kept answering. The Android 11 and 12 test phones did the same that day: the process stayed `fg-service`, and the system had posted no notification of its own when checked some seconds later.

Android turns wireless debugging off when the access point changes. On the Android 12 test phone on 2026-11-06, moving between rooms logged `Detected wifi network change. Disabling adbwifi.`. Android 12's `AdbDebuggingManager` compares the BSSID, so roaming on one network counts. It also trusts networks by BSSID: turning wireless debugging on at an access point it hasn't approved shows "Allow wireless debugging on this network?" and puts the setting back to 0. So each access point needs "Always allow" once.

On the Android 13 emulator that day, with the helper down on an unapproved network, the app's retries put that prompt up every 15 seconds without end, because each change to the setting reset the failure count. With that fixed, tapping Allow turned the setting on, which the app's settings observer saw, and the helper came back.

With the restart loop rewritten to tell its own writes from everyone else's, on 2026-11-06: on the Android 16 emulator with its network's trust removed (the `wifiAP` entries in `/data/misc/adb/adb_temp_keys.xml`, edited with `abx2xml` and `xml2abx`, then a reboot), the app's start turned the setting on, Android put it back to 0 within 80 ms and showed the prompt, the 15 s search found nothing, and the loop made no further attempt in the next 80 s. Ticking "Always allow on this network" and Allow turned the setting on, and the helper was registered 5 s later. On the Android 13 emulator, `mdnsd` stops about 12 s after the last search; killing the helper then gave a 15 s search that found nothing, the setting off for one second and on again, adbd listening on a new port at once, and the helper registered 19 s after it died, with the setting still 1. On the Android 16 emulator `mdnsd` kept running for over a minute after a search, so a helper killed there was back in 2 s without a cycle.

On the Android 11 test phone the same day, with a dev build paired beside the release app and the setting turned on over `adb shell` first, the loop ran its six attempts, each one search, one off and on of the setting (adbd logged `adbwifi started` on a new port each time), and one more search, then gave up with the setting still on. No search found the port: the first search discovered two `_adb-tls-connect` records, the phone's own and one from an emulator elsewhere on the LAN, `NsdManager.resolveService` on the emulator's record never answered, and every later resolve, the phone's own included, failed at once with error 3 (`FAILURE_ALREADY_ACTIVE`) for the rest of the process's life. Android 11 resolves one service at a time and has no `stopServiceResolution` (API 34), so one hung resolve of a record that isn't this phone's wedges the walk. `pairingPort()` used Kadb's own mDNS then, and found the pairing dialog there.

With the port walk changed to resolve only records named for this phone's serial, on the same phone later that day: a helper started over USB registered and the app recorded `ro.serialno` (`ZY227DX4L7`) through it. A second launch for the same package logged "already running" and exited. With wireless debugging on and the helper killed, the search found the phone's own record and adbd listening on it 100 ms after the death, with the emulator's record still on the LAN and never resolved, and the helper was registered 4 s after it died. The `_adb-tls-connect` record was `adb-ZY227DX4L7-...`, as the filter expects; the `_adb-tls-pairing` record's name was not read.

Kadb's `KadbMdnsAndroid` browses `_adb._tcp`, `_adb-tls-connect`, and `_adb-tls-pairing` by default. Below API 34 it calls `resolveService` on every record of all three as it is found, with no queue, as its bytecode for 2.1.4 shows. So both `pairingPort()` and the pairing notification's five-minute watch could wedge NsdManager for the whole process, connection searches included. All three searches now share one walk and FingertipsMCP no longer uses Kadb's mDNS. On the Android 17 emulator on 2026-11-06, with the walk in place: with the serial known, `pairingPort()` found the pairing dialog and paired, so that record was named for the serial. A bare TCP connect to the pairing port before pairing did no harm there. With no serial stored, the notification changed to asking for the code once the dialog opened, and went back when it closed. No API below 34 has run this walk.

On the Android 12 test phone on 2026-11-06, browser calls found no wireless debugging port while adbd was listening. The setting was 1, `/proc/net/tcp6` showed a socket owned by uid 2000 on the port mDNS had returned earlier, and mDNS searches found nothing for their full 15 s. In the log, a search found the service only after a toggle, when `mdnssd start-service` failed with `Service already running`. NsdService ran `mdnssd stop-service` 10 s after each search, and every later search started the daemon itself and found nothing, so adbd's registration seems to end with that stop.

SELinux keeps `service.adb.tls.port` from the app on that phone, and from the shell on the Android 11 test phone: it reads as empty, with an `avc: denied` for `adbd_prop`. It read as empty from the shell on the Android 12 test phone too, with adbd listening. So an empty value from `adb shell getprop` says nothing about the port.

The Android 13 emulator showed the same thing on 2026-11-06. After one search, `mdnsd` stopped by itself, `getAdbWirelessPort` still gave the port, and the next search found nothing for 15 s. Turning the setting off, then on a second later, made adbd register again, and the next search found it. Stopping `mdnsd` with `ctl.stop` is not the same: searches then find nothing even after adbd registers. On the Android 16 and 17 emulators, mDNS doesn't use `mdnsd`. Android 17 still found the port with it stopped.

`IAdbManager.getAdbWirelessPort()` does give it. `adb shell service call adb 10` on the Android 12 test phone returned 41609 while mDNS found nothing. It needs `MANAGE_DEBUGGING`, which the Shell package holds on Android 11, 12, and 13. The helper reached it on the Android 13, 16, and 17 emulators.

A release build with the helper no longer exempt from R8, signed with the debug key, on the Android 16 emulator on 2026-11-06: the helper registered (protocol 7), `/v1/device` listed every capability, and a session's wake, `ui/snapshot` (a full launcher tree once UiAutomation had connected; the first snapshot before that connect came back with no windows), screenshot, injected tap, and clipboard listener registration all ran, with nothing about a missing class in logcat. A second launch for the same package logged "already running" and exited while the first, running as shell, kept the abstract name.

On the Android 16 emulator on 2026-11-06, `ro.serialno` was `EMULATOR37X2X13X0` and adbd's record was `adb-EMULATOR37X1X11X0-VVtpcr`. The emulator is 37.2.13, so the record's name likely dates from an older emulator; the shell can't read the stored name to confirm it. With the port changed and the helper down, the search skips that record as another phone's, and the restart loop turned the setting off and on every 32 s with adbd listening. So on that image a helper lost after the port changes does not come back alone, and one started by hand lost its first race after an app update: the new launch said `already running`, and the old helper left 2 s later with `APK is gone`. A real phone's serial does not change this way.

A wrong code sent once on the Android 16 emulator that day failed once in 0.2 s, with `Could not exchange peer info`. The dialog stayed open with the same code, and the app's pairing kept working. The broadcast's result code was 0 with the failure in its text.

On the Android 11 test phone on 2026-11-06, `am start` of `.SubSettings` was refused for the shell, so the way in is `android.settings.APPLICATION_DEVELOPMENT_SETTINGS` and the Wireless debugging row. `uiautomator dump` can't run beside the helper, so FingertipsMCP's own `ui/snapshot` read the code and port for `PAIR_ADB`. A tap on a list still moving only stops the list, so wait for `idle` first.

On the Android 11 test phone on 2026-10-22, the release build's helper restarted during cleanup, when the USB connection dropped, and its app relaunched it within 30 seconds.

### Still to check

- That a browser call reconnects after DevTools' idle disconnect on the Android 12 test phone, using the helper's port lookup, and that a helper restart with no mDNS record recovers by turning the setting off and on. Both worked on the Android 13 emulator. Reaching the Android 11 and 12 phones needs a release build, since a dev build beside a running release helper doesn't start its own.
- That the restart loop recovers a lost mDNS record in one start on a phone, as it did on the Android 13 emulator. On the Android 11 test phone the record was present, so the search found it at once and the off/on cycle was not exercised with the serial filter in place. The one-prompt refusal on an unapproved network was checked only on the Android 16 emulator, since clearing a network's trust needs root. Giving up with the setting left as it was found was checked on the Android 11 test phone.
- What happens when mDNS misses the pairing dialog, and `pair()` then looks for the port itself. mDNS found the dialog on Android 11, 12, and 13.
- That the shared mDNS walk finds the pairing dialog and the connect port on the Android 11 and 12 phones, below API 34, where a hung resolve can't be cancelled and `FAILURE_ALREADY_ACTIVE` is what a search waits out. Only the Android 17 emulator has run it. With another device's record on the LAN and the serial known, its record should never be resolved. With the serial not known yet, as on a first pairing, every record is, so a hung foreign one still blocks that search. Also a bare connect to the pairing port leaving the dialog working there, which `pairingPort()` does.
- The `_adb-tls-pairing` record's name on Android 11. With the serial known, the pairing search now resolves only records named `adb-<serial>-…`, so a different name there means it finds no dialog.

## The listener's socket

`LocalServerSocket.close()` does not wake a thread in `accept()`, on the Android 13 and 16 emulators on 2026-11-06. After `ListenerService` stopped, the next connection was accepted and never answered, and each cycle left one more connected socket in `/proc/net/unix`. The abstract name stays bound until that happens. So a stop and a start with no client between failed: `Failed to open the abstract socket`, `java.io.IOException: Address already in use`, with the service still foreground and nothing serving. A later start worked. With a client in between, three of three cycles came back.

`Os.shutdown` with `SHUT_RDWR` on the listening socket's descriptor does wake that `accept()`, on the Android 10 (kernel 4.14), 13, and 16 emulators on 2026-11-06. Each ran three stops and starts with no client between, where `/proc/net/unix` showed the name gone within a second of `am stopservice` returning, and three more back to back. The host was overloaded, so that time is only an upper bound. The API answered every time, nothing failed to bind, and on Android 16 a client connecting after a stop failed at once rather than hanging. These ran on throwaway AVDs of the same system images, since other work held the named ones.

On the Android 17 emulator on 2026-11-07 the API stopped answering, `GET /v1/device` included, with the app running. 63 of the 64 IO threads were blocked in `listJsonTargets`, reading `/json/list` over wireless debugging from the Google app's two `webview_devtools_remote_*` sockets, which accepted and never answered. Closing such a socket does not wake a read in Kadb's `MessageQueue.take`, so each listing kept a thread. Force-stopping the Google app and restarting FingertipsMCP cleared it. [Android's app freezer](#androids-app-freezer) has why those sockets took connections and said nothing, and why the listings multiplied.

Once that day on the same emulator, 500 `internal` came from every open for the 13 tries left in a run: `ArrayIndexOutOfBoundsException` from `okio.Buffer.read`, with `srcPos=317`, under Kadb's `AdbWriter`, first in `AdbStream.close` and then in `Kadb.open`, its length moving by the 4 bytes each failed write added. So a segment of the buffer shared by the wireless debugging connection had been corrupted, and every later write hit it. It came 63 opens into that app's run, about 45 s after the first. An app restart cleared it, and about 700 more opens did not bring it back, 498 of them from four sessions at once for 150 s. So parallel load alone does not bring it on. The error shows as `src.length=8192 srcPos=` under `FingertipsServer` in logcat, with 500 `internal` from each call that writes to the connection.

On the Android 11 phone on 2026-11-07, at Chrome 156, the same corruption killed the app in an open and close soak, from Ktor's WebSocket writer flushing into a stream's own sink (`AdbStream$sink$1.writeToBuffer`, `srcPos=492`, length -406). Four minutes later the restarted app died too, with a fresh pool, from a `NullPointerException` in `okio.Segment.pop` under `AdbDevtoolsSocket`'s read while Ktor's reader was cancelling. All three came from the read side, found in Ktor 3.6.1, okio 3.18.2, and Kadb 2.1.4's bytecode. When a DevTools session closes, Ktor's read channel (`RawSourceChannel`) closes its `InputStream` on another thread to end a blocked read. Kadb's input is okio's `RealBufferedSource`, whose close clears its buffer while the reading thread may be copying out of it. Both threads then pop and return the same segment to okio's process-wide `SegmentPool`, and the second pop finds its links gone, or the reader moves `pos` on a segment already in the pool. `SegmentPool.take` resets `limit` but not `pos`, so the next buffer to take it, whichever stream or connection that is, gets a segment with `pos` past `limit`. A JVM run of 12 million reads raced with closes, through `AdbDevtoolsSocket` and a real `RealBufferedSource`, gave 448 such buffers and 174 `NullPointerException`s from `Segment.pop`. With the fix it gave none. When the poisoned buffer is the shared connection's `AdbWriter` sink, every later write fails, as on the emulator. FingertipsMCP now ends the read by closing the adb stream, and never closes okio's source.

### Still to check

- That turning off the listener's notification leaves the service foreground on Android 10, and whether Android posts its own notification in its place. Android 11, 12, and 13 were checked. The listener's text says it can be turned off.
- That closing the input on Android 10, where it now closes the USB tunnel's descriptor rather than nothing, still ends a blocked read there. Only the Android 17 emulator ran the fix.

## Tokens minted over ADB

On the Android 16 emulator on 2026-11-08, `system_server` logged the result of every `am broadcast` at info, under `ActivityManager`: `Broadcast completed: result=0, data="..."`. `fingertips pair` then sent a `CREATE_TOKEN` broadcast whose result was the pairing JSON, so the token was in the system log, and `logcat_tail`, which needs only `observe`, returned it. The Android 17 emulator logged the same line that day. The Android 10 and 13 emulators did not.

So `fingertips pair` now reads `content://<package>.pairing/token` with `adb shell content read`, which copies what the provider returns to adb's stdout. On the Android 10, 13, 16, and 17 emulators on 2026-11-08 it paired with the app force-stopped and again with it running, the token answered `GET /v1/device`, and neither `adb logcat -d` nor `logcat_tail` held the token or the pairing JSON. A bad `--scopes` came back as the provider's message, and a package not installed as "Could not find provider". `content` exits 0 even when the provider throws, and prints the exception to stderr. Android 10's `content call` takes only `--arg`, with no extras, which is one reason pairing reads a URI instead. A `content read` from an app's uid, through `run-as`, was refused by Android before the provider ran, for want of `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`.

The Android 17 emulator's `adbd` logged every shell command it ran, under `adbd`: `adbd service requested 'shell,v2,raw:<command line>'`. So anything on an `adb shell` command line reaches the log there. The pairing URI holds only the token's name and scopes. A `SET_PIN` broadcast's `--es pin` and a `PAIR_ADB` broadcast's `--es code` did reach it there, though their broadcast results ("stored", "cleared", and the pairing message) hold neither. On the Android 13 and 16 emulators no `adbd` line was logged, and the PIN and code appeared nowhere: `ActivityManager` writes only "(has extras)" for a broadcast's extras.

### Still to check

- Whether Android 14 and 15 log broadcast results or `adbd` command lines, and whether the Android 11 and 12 test phones do.

## Unlocking

On the Android 16 emulator on 2026-11-06, with the lock screen turned on, a swipe keyguard went away through `wm dismiss-keyguard` in 3.6 s with no swipe. A PIN unlock worked three of three times in 4.2 to 5.2 s, and the PIN did not reach the log. With the helper down, `POST /v1/device/wake` failed `wake_failed`, since Android dropped `WakeActivity`'s start from the background.

The PIN field on the Android 10 and 17 emulators on 2026-11-06 was `com.android.systemui:id/pinEntry`, a visible, non-clickable node with the password flag. Its text holds one character per digit, as `•` on Android 17, and is empty when the field is. Delete is `delete_button`, labelled Delete. The Android 17 count followed each digit and delete tap, one at a time, though a read can come before the tap shows. On both, with two digits left in the field, `POST /v1/device/unlock` cleared them and unlocked, in 6.2 s on Android 17 and 10.6 s on Android 10. On Android 17 the PIN reached no FingertipsMCP log. With three emulators sharing the machine, at a load average near 30, a cold unlock often failed with no keypad: on Android 17 the keys took 1.9 to 4.1 s to appear after `wm dismiss-keyguard`, past the 1.5 s the bouncer gets. That failure types nothing, and it is the same wait as before the field check.

On the Android 11 test phone on 2026-11-06, with no keyguard set, `device_lock` turned the screen off and the state stayed `locked` false. `device_unlock` said `The device was not locked`.

### Still to check

- That the PIN field and delete key read the same on the Android 11 and 12 phones and any OEM keyguard: a visible node with the password flag whose text length is the digit count. An unlock refuses when no such field is found, so a keyguard that hides it, as a PIN auto-confirm or PIN-shape setting may, can't be unlocked with a stored PIN. Also a real cancelled entry cleared on the phone, and whether 1.5 s covers its bouncer coming up.
- That PIN unlock works on the phone, which has only run it on emulators with these waits. Also how long a keyboard read takes there with `refresh` on each node, and whether Android 11's Gboard goes stale as Android 16's does.
- `wm dismiss-keyguard` on Android 10 and 11.

## App launch and copy

On the Android 16 emulator on 2026-11-06, `app_launch` through the helper brought Settings forward in 1.5 s. Without the helper Android dropped the start, and the call failed 503 `helper_unavailable` after 5 s. A copy with `KEYCODE_COPY` in Chrome returned the selected text. An `open_intent` tab was claimed within 2.2 s, under the URL match main has since replaced. Chrome 133 returned `loaderId` from `Page.getFrameTree`, sent the `load` lifecycle event, and sent `requestWillBeSent` again with the same id for a redirect. A debug start logged a StrictMode `DiskReadViolation` from `getFilesDir` in `AdbEndpointFinder`'s constructor, on the main thread. A helper registering while the app starts threw `lateinit property graph has not been initialized` across binder, and it got in on the app's next try.

Run again on the Android 16 emulator on 2026-11-06, once `app_launch` decided from whether FingertipsMCP is the top app, with the helper stopped by FingertipsMCP's own switch and its foreground service running:

| FingertipsMCP | Screen | Result |
| --- | --- | --- |
| In front | On | `ok` true, `backend` `activity`, in 0.25 to 0.44 s, with `wait` true and false, and Settings came forward |
| Behind the launcher | On | 503 `helper_unavailable` in 0.25 to 0.36 s, and Settings did not start |
| In front | Off | 503 `helper_unavailable`, and the screen stayed off |

So the foreground service does not count as in front, and a process that is on top with the screen off does not either. Without the helper nothing reports the foreground app, which is why such a start returns without waiting for it. Through the helper, a cold Settings took 10 s on the loaded host, and `ok` true.

`getFilesDir` stats the directory on every call, not only the first, so a second call on the main thread was what StrictMode reported. With one call left, the app logged no StrictMode line on the Android 10, 13, and 16 emulators on 2026-11-06, from a clean install through pairing and six listener restarts, and none on an Android 16 cold start. The same logs held up to 502 StrictMode lines from other processes, such as the Google app, the dialer, and `system_server`, so filter by the app's pid.

On the Android 11 test phone on 2026-11-07, `app_launch` without the helper, with FingertipsMCP not in front, failed 503 `helper_unavailable` in 0.03 s.

### Still to check

- `app_launch` without the helper on Android 10, and with FingertipsMCP in front on a phone, and `ActivityManager.getMyMemoryState`'s importance there: it was read only on the Android 16 emulator.

## Browser waits

On the Android 10 emulator (Chrome 91) on 2026-11-06, with a wait's events read through `CdpSession.listen`:

- `browser.lifecycle`, `browser.request`, and `browser.url` together matched across a navigation to a page with 300 images, in six runs of six.
- `browser.newTarget` matched a page that was already open, in about 70 ms. The build before the change did too. So Chrome's `Target.targetCreated` events after `Target.setDiscoverTargets` include open pages, and FingertipsMCP counts them as new.
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

Selector lookups across a load, on 2026-11-07. Each page held its parser on a script the test server kept back, with the element already parsed, and the test let the script go at a random moment.

- A node id or search taken before `DOMContentLoaded` was refused after it, and one taken before a commit was refused after that, 10 of 10 on Chrome 109 and 20 of 20 on Chrome 133. `DOM.querySelector`, `DOM.scrollIntoViewIfNeeded`, `DOM.getContentQuads`, and `DOM.focus` said `Could not find node with given id`. `DOM.resolveNode` said `No node with given id found`, and `DOM.getSearchResults` said `No search session with given id found`. All three have code -32000, the code of any refusal, so the code doesn't name this one.
- `DOM.documentUpdated` reached the session before the first refusal, with `DOM.querySelector` sent over and over across the change. That held for every try: 30 at `DOMContentLoaded` and 30 at a commit on Chrome 91, and 20 of each on Chrome 109, 133, and 145. It held without `DOM.enable`, since `DOM.getDocument` enables the domain. At a commit it follows `Page.frameNavigated`. A commit to another site on Chrome 133 and 145 did the same, 20 of 20 each. On Chrome 109 one such try did, and the rest did not run, because the test lost the live tab among that emulator's 53 old ones.
- Chrome 156 on the Android 11 test phone did the same on 2026-11-07: `DOM.documentUpdated` came first in 20 of 20 tries at `DOMContentLoaded` and 20 of 20 at a commit. The refusal read `Could not find node with given id`.
- A second `DOM.getDocument` sends no `DOM.documentUpdated`, so a lookup does not set off its own retry.

Through FingertipsMCP, on the build before `withNodeIds` and on the build with it. A tap on a selector that appears only as the parser finishes polls until it matches. A wait of 0 ms, sent over and over, looks once each time and counts per `DOMContentLoaded`. The third wait was woken by a page fetching every 15 ms, with a `browser.request` condition beside it.

| Check | Chrome | Before | After |
|---|---|---|---|
| Tap, polling | 91 | 5 of 205 failed | 0 of 160 |
| Tap, polling | 133 | 6 of 68 failed | 0 of 100 |
| Tap, polling | 145 | 4 of 60 failed | 0 of 100 |
| `browser.element` css, repeated | 91 | 3 of 15 loads failed | 0 of 20 |
| `browser.element` css, repeated | 133 | 4 of 20 loads failed | 0 of 40 |
| `browser.element` css, repeated | 145 | 1 of 20 loads failed | 0 of 30 |
| `browser.element` text, repeated | 145 | 3 of 20 loads failed, and 5 of 386 looks found nothing | 0 of 30 |
| `browser.element` visible, woken by events | 133 | 1 of 40 failed | 0 of 60 |
| `browser.element` visible, woken by events | 145 | 0 of 40 failed | not run |

The failures were `cdp_error` with Chrome's words above. A single wait that only sat through the load did not fail: 60 of 60 on Chrome 133. Nothing woke it until `DOM.documentUpdated` had already told it to take new ids. With the change, a tap across a commit worked 25 of 25 times on Chrome 133, and text waits matched 459 of 459 there. A wait spanning a navigation matched 25 of 25 before the change on Chrome 133 and 20 of 20 on Chrome 91, so the failure listed above for Chrome 91 was gone by then.

Seen while checking, and not changed:

- On the Android 10 emulator, 4 of 205 taps before the change and 4 of 160 after it failed `503` `cdp_error`, saying Android 10 reaches DevTools over USB, though the tunnel's forward and reverse were in place. The next tap worked. The table leaves these out.
- On the Android 16 emulator, the first run after a boot stalled twice, once before the change and once after it. A tap got no reply within 12 s, and the first time adb stopped answering too, until a reboot. It did not happen again in later runs, and the cause is not known.

Nodes with no box, on 2026-11-07, on Chrome 91, 109, 133, and 145, with each command sent straight over DevTools. All refusals have code -32000.

| Node | `DOM.scrollIntoViewIfNeeded` | `DOM.getContentQuads` | `DOM.describeNode` |
|---|---|---|---|
| `display: none`, or inside one | `Node does not have a layout object` | Chrome 91: `Could not compute content quads.` Chrome 109, 133, 145: an empty list | Answers |
| `display: contents` with a child | Chrome 91: `Node does not have a layout object`. Chrome 109 on: works | Chrome 91: `Could not compute content quads.` Chrome 109 on: one quad | Answers |
| `display: contents` with no child, `<option>` in a closed `<select>`, `<template>`, `<head>` | As for `display: none` | As for `display: none` | Answers |
| Removed by script, by node id | `Could not find node with given id` | The same | The same |
| Removed by script, by backend node id | `Node is detached from document` | As for `display: none` | Answers |
| Backend node id Chrome never gave | `No node found for given backend id` | Not run | Not run |

- So neither the scroll nor the quads tells a hidden node from a removed one by anything but text, and only a node id gets a refusal for a removed node. A removed node with no reference left, after `HeapProfiler.collectGarbage`, still answered by its backend node id on all four.
- `Accessibility.getPartialAXTree` gives both nodes `ignored` with `notRendered`. Only `parentId`, on Chrome 145, differs, and on Chrome 91 neither has one. `DOM.pushNodesByBackendIdsToFrontend` gives a removed node a new node id.
- `DOM.resolveNode` with an isolated world's `executionContextId`, then `Runtime.callFunctionOn` reading `isConnected`, tells them apart. It was true for a hidden node, in the page or in a same-origin frame, and false for a removed one, on Chrome 91 and 109 over DevTools. Through FingertipsMCP it held for nodes in the page on Chrome 91 and 145. A backend id Chrome never gave is refused with `No node with given id found`.
- `DOM.focus` says `Element is not focusable` for a hidden node and for a visible `<div>` alike, on Chrome 91.
- An element in a closed `<details>` has a box on Chrome 109, 133, and 145, though it can't be seen, and none on Chrome 91. An element in `content-visibility: hidden` or in `<defs>` has a box on all four. Empty, zero-size, and `visibility: hidden` elements have one too.

A tab whose server took 14, 18, or 20 s, opened through FingertipsMCP at 4a18edd on the Android 11 test phone on 2026-11-07, returned `loading` true at 12.5 to 12.6 s. A `browser.settled` wait then matched within 0.4 s, 4 of 4, and `browser.loaded` within 0.1 s, 1 of 1, though the server had not answered. Until it did, the tab read as `about:blank`, with `document.readyState` `complete`, and `read` returned 200 with no text. Once it had, `read` returned the page.

At 9e047f7 on the same phone, with a server that took 16 s, each open returned `loading` true at 12.3 to 12.4 s. `read`, `find`, `evaluate`, and `snapshot` failed 409 `content_unavailable` in 0.02 to 0.1 s, every time. A wait with an 800 ms limit timed out at 1.05 s. `browser.settled` then matched at 16.9 and 17.0 s, and `browser.loaded` at 16.4 and 16.5 s, and `read` returned the page. A `capture` open went the same way, with `loading` at 12.6 s and `browser.settled` at 17.3 s, and an incognito open with `loading` at 13.6 s and `browser.settled` at 18.2 s.

Element waits on a page that has loaded, on 2026-11-07, on Chrome 133 and 145. A wait with `timeoutMs` 4000 was on a tab behind another (`document.visibilityState` hidden) or on screen, and the page changed 1 s in. Nothing held UiAutomation. The table counts matches. Runs after the change matched 40 to 540 ms after the page changed, and the failures before it timed out at 4 s.

| Condition | Page change | Tab | Before, 133 | Before, 145 | After, 133 | After, 145 |
|---|---|---|---|---|---|---|
| `present` css | Element added | Hidden | 0 of 3 | 0 of 3 | 5 of 5 | 7 of 7 |
| `present` css | Element added | Shown | 0 of 3 | 0 of 3 | 5 of 5 | 7 of 7 |
| `present` text | Element added | Hidden | 0 of 3 | 0 of 3 | 4 of 5 | 7 of 7 |
| `present` text | Element added | Shown | 0 of 3 | 0 of 3 | 5 of 5 | 7 of 7 |
| `visible` | `display: none` removed | Hidden | 0 of 3 | 0 of 3 | 5 of 5 | 7 of 7 |
| `visible` | `display: none` removed | Shown | 3 of 3 | 3 of 3 | 5 of 5 | 7 of 7 |
| `absent` | Element removed | Hidden | 3 of 3 | 3 of 3 | 5 of 5 | 7 of 7 |
| `absent` | Element removed | Shown | 3 of 3 | 3 of 3 | 5 of 5 | 7 of 7 |

A wait that spanned a navigation on a hidden tab matched 10 of 10 on each Chrome after the change, with its looks running through the load.

The "before" builds above held a change to touch input as well, which was on the branch by accident. The waits were run again on Chrome 133 on builds without the fix, and on one with it merged with main. The first two rows started 45 s after an install.

| Build | `present` css | `present` text | `visible`, hidden | `visible`, shown | `absent` |
|---|---|---|---|---|---|
| 4a18edd, without the touch change | 4 of 10 | 1 of 10 | 0 of 5 | 5 of 5 | Not run |
| Main at a8ed3f8, without the fix | 1 of 10 | 1 of 10 | 0 of 5 | 5 of 5 | Not run |
| With the fix, on main at 87cae01 | 6 of 6 | 4 of 6 | 3 of 3 | 3 of 3 | 6 of 6 |

So a wait without the fix could match by chance, when something else woke it, 0.9 to 2.3 s after the change at 4a18edd. What woke it was not looked into. `visible` on a hidden tab never matched, since a look there could not see the box. The two misses with the fix were `502` at set-up, as below.

What Chrome did, the same on 133 and 145:

- A session that took the document at depth 0, and whose `DOM.querySelector` found nothing, heard no event for a node added to the page.
- A look that found a node drew `DOM.setChildNodes` for each parent on its path, on every look, because each `DOM.getDocument` forgets what the session was told. Those woke the wait, so a `visible` or `absent` wait on a found node looked about 19 times a second. After the change it looks about 4 times.
- A change under one of those parents drew an event until the next `DOM.getDocument`, and none after it.
- After `DOM.getDocument` at depth -1, adding a node, setting an attribute, and setting text each drew an event. `insertRule` on a style sheet drew none, though it can change what has a box.
- `DOM.getContentQuads` gave no quads for a node added to a hidden tab since its last layout, and none 500 ms later. `DOM.getBoxModel` laid the page out first, even when it refused the node, and so did `DOM.scrollIntoViewIfNeeded`.
- `DOM.getBoxModel` refused `display: contents` and an `option` in a closed `select`. `DOM.getContentQuads` gave a quad for the first and none for the second. Both gave a box for zero-size, off-screen, and `visibility: hidden` elements.

What each way of hearing about new nodes cost, on a page of `div` rows. The page's cost is 3,000 adds and removes of a `div`, by script, with a session in each state. Rows marked 133 were not run on 145. The host's load average was 5 to 35, so times are loose.

| Measure | 9,005 nodes | 60,005 nodes |
|---|---|---|
| `DOM.getDocument` at depth -1 | 2.3 MB in about 80 ms | 15.7 MB in about 510 ms |
| The page's changes, no session | 6 to 8 ms | 6 to 8 ms |
| The page's changes, depth 0 | 5 ms | 4 to 5 ms |
| The page's changes, depth -1 | 20 to 24 ms | 20 to 27 ms |
| Events at depth -1, with 20 rows changing every 16 ms (133) | 4,700 a second, 680 KB/s | 1,300 a second, 270 KB/s |
| A look that finds nothing, round trip (133) | 6 to 7 ms | 5 to 9 ms |
| A look that finds a node under a parent with every row (133) | 21 ms | 108 ms |

A DevTools call that never reaches the page took about 3 ms, so most of a look that finds nothing is the trip. At the 99th percentile, a 4 ms timer in the page ran no later with looks every 250 ms than with none. With looks that found a node every 50 ms on the larger page, its ticks fell from 1,233 to 884 in 5 s. A depth -1 session has to take the document again at each `DOM.documentUpdated`, and a look's `DOM.getDocument` would end its events.

Through FingertipsMCP on the larger page, on screen with rows changing, a wait for a node that never came looked 3.6 times a second. `absent` and `visible` looks that found a node ran 1.8 to 2 times a second, against 3 on Chrome 133 before the change, since each takes 250 to 300 ms there.

Seen while checking, and not changed:

- On Chrome 133, 2 of about 90 waits failed `502` `cdp_error` `Could not open Chrome's DevTools socket`, one on the build before the change and one after it, the `present` text miss in the first table. The next wait worked. Chrome 145 had none in about 90. In the runs after them, 4 of about 45 failed the same way or with `DevTools closed the WebSocket`, two on a build with the fix and two without, all within about a minute of an install. The two runs that started 45 s after an install had none in 60. The cause is not known.
- On Chrome 145, `PUT /json/new` fails with HTTP 500 `Could not create new page`. FingertipsMCP's own tab calls work.
- On Chrome 145, a tab opened while Chrome was behind the home screen reported `visible`, though Chrome wasn't drawing it, and showed no quads for a new node, as a hidden tab does.

The phone's wait loop is the one the desktop uses since 2026-11-08. Checked that day on the Android 16 emulator with Chrome 133 and the Android 17 emulator with Chrome 145, through MCP `wait_for` with `screenshot` and through `POST /v1/wait`, on a hidden tab:

- `browser.element` for a node a page adds 3 s after it loads matched with `elapsedMs` 3,180 and 3,040, as the node appeared. `screenshot` added 0.7 to 1.5 s to the call after the match.
- `browser.settled` with `quietMs` 500 matched at 506 to 551 ms on a loaded page, and at 507 and 515 ms on a tab just opened.
- A node that never comes timed out with `matched` false and `timedOut` true at 2,007 to 2,008 ms for a 2,000 ms limit and 513 to 534 ms for 500 ms over MCP, and at 1,506 and 1,511 ms for 1,500 ms over REST. Each still returned 200, with a screenshot when asked.
- `all` with `browser.element` and `browser.settled` on the same tab matched both, `matchedAll` 0 and 1, at 528 ms.

### Still to check

- The hidden-node taps through FingertipsMCP on Chrome 109 and 133, whose commands were read only over DevTools, and on the phone's Chrome 156.
- A browser wait's limit on the Android 10 USB path.
- That `DOM.getBoxModel` lays out a hidden tab on the phone's Chrome 156, and on Chrome 91 and 109, as it does on 133 and 145. Without it, a `visible` wait on a hidden tab doesn't match a new node, as it didn't before.
- What a look costs on a phone. The times above are from an emulator on a faster CPU.
- That a DevTools socket which opens after a wait reached its limit is closed, on the adbd path (Android 11 and later) and on the Android 10 USB path. It's a race, so it hasn't been made to happen on an emulator. On adbd, a socket left open kept wireless debugging's connection from ever going idle. Unit tests cover both opens with fakes.

## Page reads and events

On 2026-11-07, in desktop Chrome 156 made to look like a phone, MDN's HTTP page drew its main content through a `<main>` with `display: contents`. `checkVisibility()` said that element was not visible, with or without its CSS options, while each child said it was. So `page-read.js` treated it as hidden and read 333 of the page's 11,251 characters, in both scopes. Article scope at v0.3.8 got 9,635, since Readability was then given the whole page. The phone's read of the same page got 347.

A release build with R8, on the Android 16 emulator on 2026-11-08, read the page scripts, Readability, and pdf.js from the `browser` module's jar resources: an article read came back `article`, and the arXiv PDF read `pdf`, 38,965 characters. Its slf4j lines reached logcat under `FingertipsServer`, `FingertipsMcp`, and `FingertipsStream`. The `browser` module logs only on failures, so none of its tags showed.

### Still to check
- How long a large page takes to read past the cache on a phone. The emulators took about 900 ms for 3,000 elements.
- That a card number in a toast or a window title reaches `WS /v1/events` as `[card number]`, and the rest of the text is unchanged.

## Injected input

`chrome-input-check` on Chrome 156 on the Android 11 test phone on 2026-11-06: all ten checks gave what the script expects, in 28 s.

On the same phone that day, the system cancelled one swipe sent right after `BACK`, as the window changed.

`browser_key` with Shift held over `cdp`, on the Android 16 emulator with Chrome 133 and the Android 17 emulator with Chrome 145 on 2026-11-08, into a focused field with Chrome behind the home screen, on its selected tab and on a tab behind another, read back over `evaluate`:

- Shift and a letter typed the capital, with `key` the capital and `shiftKey` true. So did `metaState` 65, Shift with its left-key bit. A letter without Shift typed it in lower case.
- Before the change below, on Chrome 133, Shift and 1 typed `1`, with `key` `1` and `shiftKey` true. The same request over `touch`, an injected key with Chrome in front, typed `!`, which is what Android's virtual keyboard map gives, and Shift with `COMMA` typed `<`. FingertipsMCP now sends a key with Shift held as a US keyboard types it, so `cdp` gives `!` with `code` `Digit1`, as checked on both Chromes after the change.
- Shift with an arrow moved the selection's end and typed nothing. Shift with Tab moved focus to the field before, and typed nothing.
- `COMMA` and `PERIOD` over `cdp` got 400 `Unknown key`, with or without Shift, since the `cdp` key names have no punctuation.

The same on the Android 11 test phone on 2026-11-08, with Chrome 156.0.8078.79, into a focused field on a tab a session without the screen opened, with Chrome behind the home screen, read back over `evaluate` with a `keydown` listener in the isolated world. The tab read `visible` throughout.

- Shift and `A` typed `A`, with `key` `A`, `code` `KeyA`, and `shiftKey` true. So did `metaState` 65 with `B`. `c` and `Z` without Shift typed `c` and `z`.
- Shift and `1` typed `!` with `code` `Digit1`, and Shift and `3` typed `#`. `2` without Shift typed `2`.
- Shift with `DPAD_LEFT` twice after typing moved the selection from 7..7 to 5..7, `backward`, and Shift with `DPAD_RIGHT` then made it 6..7. None of them typed anything. After `text` over `cdp`, Shift with `DPAD_LEFT` selected the last character. `DPAD_LEFT` without Shift collapsed the selection.
- Shift with `TAB` moved focus to the field before, and Tab without Shift moved it back. Neither typed anything.
- `COMMA` with Shift got 400 `Unknown key`.

### Pages Chrome has not drawn

On Chrome 109, 133, and 145 on the emulators on 2026-11-07, Chrome dropped mouse clicks and key events on a page it had not drawn, and answered `ok`. Chrome 156 on the Android 11 test phone did the same that day for clicks, 0 of 10. Chrome 91 delivered them.

- Over raw DevTools, with Chrome behind HOME, a click on a tab opened with `Target.createTarget` never reached the page. `visibilityState` read `visible`. On the phone, a tab that had never been shown read `hidden` with Chrome in front on another tab, and dropped clicks too, so `visibilityState` does not tell.
- Waiting does not help: not after 8 s on Chrome 133, nor 6 s on the phone.
- `Input.dispatchKeyEvent` is dropped the same way, on all three emulator Chromes. `Input.insertText` lands.
- `Input.dispatchTouchEvent`, `Input.synthesizeTapGesture`, `mouseMoved`, and `mouseWheel` got no answer on Chrome 133 and 145, so none of them is a way in.
- A tab that has been drawn takes clicks after Chrome goes behind HOME, until it loads another document. That one drops them again, on Chrome 109, 133, and 145. So whether a tab was ever shown does not say what it will do.
- `about:blank` takes clicks, and a `data:` page did on Chrome 133, so Chrome does not hold those. `about:blank` then `Page.navigate`, a same-site navigation, and `Page.reload` all dropped clicks on Chrome 133, and on the phone.

Chromium's source gives the cause. `WidgetInputHandlerManager::DispatchEvent` drops everything but mouse moves while main frame updates or commits are deferred for a first paint, and acknowledges the event. `ProxyMain::BeginMainFrame` is what ends that deferral when its timeout passes, and a page Chrome is not drawing gets no main frames. With `--allow-pre-commit-input`, which skips the drop, the same click and key landed on Chrome 133, one try each.

`Page.captureScreenshot` ends the hold, and Chrome never answers it on such a page. Measured with raw DevTools, on a tab Chrome had not drawn:

- A click after it landed in 12 of 12 tries on each of Chrome 109, 133, and 145, on a new tab each time. There is no signal for the end of the hold, so the first press can be too soon, and the page saw only the press that got through. A second press was needed in 1, 7, and 1 of the 12, and the press landed within 60 ms.
- The hold stays off for that document. It does not need the request to stay open: closing the socket 100 ms after sending it, then clicking up to 3 s later, landed 4 of 4 on Chrome 133 and 145. A new document needs a new request.
- It worked with the screen off, and on a page reading `hidden`, on Chrome 133 and 145.
- The page saw nothing on Chrome 133 and 145: no animation frame, paint entry, `visibilitychange`, `resize`, focus event, or intersection or resize observer callback. Chrome 109 showed no animation frame or paint either. Why the deferral ends with no frame is not known. The request holds a capture on the tab that asks to keep the device awake, and `dumpsys power` showed no lock from Chrome while it was open, on Chrome 133 and 145.
- The tab drew normally when it came forward later.
- On the Android 11 test phone's Chrome 156.0.8078.79 on 2026-11-09, over raw DevTools with Chrome behind HOME and the screen off: a tab opened with `Target.createTarget` read `visible` and ran its 100 ms timer at full rate, and went `hidden` when an `about:blank` tab opened over it, still at full rate 28 s later. A `Page.captureScreenshot` of it, a 1 by 1 JPEG clip, got no answer in 20 s, and the page saw no animation frame, no intersection observer callback, and no load of an image with `loading="lazy"` 3,000 px down, though `innerWidth` and `innerHeight` went from 980 by 1728 to 412 by 726 after the request. A screenshot of the blank tab got no answer either. So on Android a screenshot does not draw a hidden tab, where on desktop it does, as [Focus and background tabs on macOS](#focus-and-background-tabs-on-macos) says; only bringing the tab forward loads what loads when drawn. Whether that request ended the hold on input was not tried.
- `Page.startScreencast` sent no frames and left clicks dropped, on Chrome 133 and 145. So did `LayerTree.enable` and the FPS overlay on Chrome 133. On the phone, `Page.bringToFront` with Chrome staying hidden, `Page.reload`, and `Page.setWebLifecycleState` did nothing, nor did `Emulation` overrides, which FingertipsMCP does not use. `Page.setWebLifecycleState` to `frozen` and back to `active` also turned the page `hidden` on Chrome 133, which a page can see.

`Input.synthesizeTapGesture` opens the way too on the phone, 15 of 15, on Chrome 156. Its own tap got no answer and never showed on the page. FingertipsMCP does not use it, since that leaves a gesture pending that could still play.

User activation does not say the hold is over. `Input.insertText` gives a held page activation, and the next click is dropped, on Chrome 133. On Chrome 145 `hasBeenActive` carried over a same-origin link or script navigation to a new document, which was held and dropped the next click. It did not carry over `Page.navigate` or to another origin, or at all on Chrome 109 or 133. On Chrome 109 a click after a same-origin script navigation landed with no paint, in the one try, so a press can land while nothing says the hold is over, which is why each press is checked. So only a paint says the hold is over. New activation does show a press landed, and it comes up from a frame inside the page. Escape and modifier keys give none, on Chrome 133 and 145, though a listener hears them. A listener in the top frame does not hear a press in an iframe: an earlier build that relied on one repeated a press there 17 times on Chrome 109, so each frame gets one. A frame in another process does not, so a press there on a page with activation from before cannot show it landed. FingertipsMCP repeats it for 1 s and fails with `not_visible`. This was not seen, since Chrome keeps most frames in one process on Android.

Chromium has a check, behind the `CDPScreenshotNewSurface` feature, that crashes the browser on a screenshot of a hidden page. The feature is off by default in Chrome 145 and in Chromium's main branch.

### Still to check

- That a swipe the system cancels on a phone fails 409 `gesture_cancelled`, as the unit tests show. It came up once in ordinary use on the Android 11 test phone, right after `BACK`.
- `chrome-input-check` on a phone, since it now tests a page from a server. It passed on the Android 13, 16, and 17 emulators on 2026-11-07, with Chrome on screen too. On Chrome 91 six checks differ, since that Chrome has no hold.
- That `Page.captureScreenshot` ends the hold on Chrome 156 on the Android 11 test phone. It was not tried there. If it does not, a tap on a page Chrome has not drawn fails with `not_visible`.
- Whether Chrome ever turns on `CDPScreenshotNewSurface`. FingertipsMCP sends a screenshot request to a `hidden` page that dropped a press, which that check would crash on.

## Desktop Chrome (FingertipsMCP for Chrome)

Checked with Chrome 155 on macOS while the desktop build was first written, and again on 2026-11-04 with Chrome 156.0.8078.80 on macOS, headful, with a scratch profile:

- Any remote-debugging switch, `--remote-debugging-pipe` included, makes `navigator.webdriver` true even without `--enable-automation`. `--disable-blink-features=AutomationControlled` puts it back to false. On Chrome 155 the property's descriptor, getter source, name, length, and place in `Navigator.prototype` then matched an uncontrolled Chrome's, and on 156 it read false.
- That flag puts a warning bar on the tab Chrome opens at launch, 56 CSS pixels tall at a 1200x900 window on Chrome 155. With `--no-startup-window` and the first window opened through `Target.createTarget` with `newWindow`, no tab showed it: the first window, later tabs, and later windows had the same content height as an uncontrolled Chrome's.
- `--no-startup-window` skips session restore: a profile saved with two tabs, and one after `kill -9`, both came back with one blank tab.
- On macOS, Chrome with no window keeps running, and `Target.createTarget` without `newWindow` opens a window.
- Mouse clicks, wheel scrolls, and keys over `Input.dispatchMouseEvent` and `Input.dispatchKeyEvent` reach the page with `isTrusted` true, and a click on an element 3000 px down the page landed after wheel scrolling (2026-11-04).

- Chrome shows a PDF at the file's own URL, not at a viewer address as on Android. On 2026-11-06, with Chrome 156, `document.contentType` and the main frame's `mimeType` in `Page.getFrameTree` were `application/pdf`, and the document held only an empty body, so page-read.js found 0 characters. Taking the tab over as on Android worked: `read`, a second `read`, `find`, and `url` reads of a local PDF, and the 38,965 characters of the arXiv PDF of "Attention Is All You Need" over https. The window then shows FingertipsMCP's empty page rather than Chrome's viewer.
- `Target.createTarget` with `background` true opened a tab that reported `hidden` from the start, and the tab showing stayed `visible`. Measured on 2026-11-06 with Chrome 156, with `document.hasFocus()` standing in for which app is in front: with Finder in front, `Target.activateTarget` switched tabs and left focus alone, while `Page.bringToFront` and a plain `Target.createTarget` both took focus. `document.hasFocus()` turned out not to say which app is in front, as [Focus and background tabs on macOS](#focus-and-background-tabs-on-macos) found on 2026-11-09, and `Target.activateTarget` did take focus when the front app was read from Launch Services.
- The ported build on 2026-11-08, with Chrome 156.0.8078.80 headless on macOS: a click on a button 1,600 px down a page landed after 36 wheel ticks scrolled it 1,168 px; typing `Hi there` sent Shift around the H; an arXiv PDF read as `pdf`, 38,965 characters, the same as on Android; a tab a session without the screen opened read `shown` false, true after `show`, and false again once `screen_release` put the placeholder up; and `/mcp` answered 403 to a request with an `Origin`.
- The same build windowed on macOS that day: `navigator.webdriver` read false; the first window's tab and a new tab had the same content height, 1998 of 2085 px, so neither showed the flag's bar; a tab a session without the screen opened was `hidden`, and with no screen holder the placeholder replaced the tab that had been showing; a click and typing `Hello, World! 42` landed, and the first input on the hidden tab gave Chrome the focus, as `Page.bringToFront` does; an arXiv PDF read as `pdf` in the window too.
- A PDF sent with `Content-Disposition: attachment` saved to the download folder and closed the new tab, as on Android. Two `url` reads of it returned its text and left one file (2026-11-06).
- On 2026-11-08, with Chrome 156.0.8078.80 headless on macOS and a scratch config, data folder, and `--profile`: after Chrome was killed, a `GET /v1/browser/targets` that curl gave up on after 0.3 s started it again, the next two calls got that Chrome, one Chrome ran on the profile, and stopping the server left none. The same held with a `--chrome` wrapper that starts Chrome 2 s late and a `POST /v1/wait` with `timeoutMs` 0 as the first call. Neither the hang-up nor the wait cancelled the start, on the build before the fix either, which also started Chrome once and kept it.
- Scrolling and tab switches for desktop input, on 2026-11-08 with Chrome 156.0.8078.80 windowed on macOS, a scratch config, data folder, and `--profile`, and a 1998 px tall viewport. A CDP `mouseWheel` scrolled the page within a frame: each notch gave one `scroll` event, the last no more than 16 ms after the last `wheel` event, with no smooth-scrolling tail, so the wait for the element to hold still ended at its first check. Taps on a button 2,400 px down landed after 32 to 45 notches of 40 px, with no fallback, three times. On a page that put 600 px of content above the button 120 ms after each of the first three 800 px of scroll, the tap still landed with the wheel. With `overflow: hidden` on the body, the wheel moved nothing, and Chrome's `DOM.scrollIntoViewIfNeeded` scrolled the button in, which the result's message said; so did a button 3,000 px to the right. A tap on a `hidden` tab behind another brought it forward, the click landed there, and the other tab read `hidden`; a key into that other tab then brought it forward too.
- Chrome 125, the oldest FingertipsMCP supports, on 2026-11-08: Chrome for Testing 125.0.6422.141, the last 125 build, windowed on macOS, with a scratch config, data folder, and `--profile`. Started as is, Chrome never answered over the pipe, and over a port it never wrote `DevToolsActivePort` either. Its main thread sat in `SecKeychainFindGenericPassword`, waiting on a macOS prompt for its Safe Storage key, and it ignored SIGTERM until the prompt went. The launch gave up after 15 s and sent SIGTERM, but that Chrome kept running, and kept the profile, after the server had stopped too. So a launch that gives up now kills a Chrome still running 5 s after SIGTERM. With `--chrome-arg --use-mock-keychain`, which only Chrome for Testing needed, it started at once. `GET /v1/device` named `Chrome/125.0.6422.141`. Over REST and over MCP, these worked as on Chrome 156: a session, a tab opened hidden, `read`, `find` of text and of links, `snapshot`, `evaluate`, a `cdp` click and typing `Hello, World! 42` with Enter, `navigate`, `browser.settled` and `browser.element` waits, a screenshot, a second session's tab opened `hidden` while the holder's stayed `visible`, closing the last tab with the placeholder taking its place and Chrome still running, and ending the sessions. An arXiv PDF opened with `POST /v1/browser/tabs` read as `pdf`, 38,965 characters, as on 156. [The viewer on older Chrome](#the-viewer-on-older-chrome) has what the viewer did.

### Focus and background tabs on macOS

Measured on 2026-11-09 with Chrome 156.0.8078.80 on macOS 27.0.1, windowed and headless, on scratch profiles over `--remote-debugging-pipe`, with no FingertipsMCP involved. A Node script started Chrome as `ChromeLauncher` does, read the front app from Launch Services (`lsappinfo front`) every 150 ms, and gave the front app back to the app that had it before each step. A probe page in each tab recorded `document.visibilityState` and `document.hasFocus()` at load and on each change, `requestAnimationFrame` and timer rates, when an `IntersectionObserver` fired, and when an image with `loading="lazy"` 3,000 px down loaded. A visible tab ran 60 frames and 10 ticks of a 100 ms timer a second; a hidden one ran no frames.

What took the front app from another app, 5 of 5 times each unless said otherwise:

- Starting Chrome without `--no-startup-window`, which opens its own window, tried once. With the flag, the start took nothing, and so did a window opened later with `Target.createTarget` with `newWindow` and `background` true.
- `Target.createTarget` with `newWindow` and without `background`, and a plain `Target.createTarget` into a window Chrome already had. With `background` true, neither did.
- `Page.bringToFront` and `Target.activateTarget`, each time. The 2026-11-06 note above that `activateTarget` left focus alone read `document.hasFocus()`, which this run found true in a tab Chrome had just selected while another app was in front, so it was not the right measure.
- `chrome.windows.update` with `focused` true from an extension, tried once. `chrome.tabs.update` with `active` true from an extension, also tried once, selected the tab and took nothing.
- `Browser.setWindowBounds` with `windowState` `minimized` took nothing and minimized the window, but the next plain `Target.createTarget` into it put the window back to `normal` and took the front app, in the one run that tried it. `--window-position=-32000,-32000` placed the window at 0,31 on screen, so it hides nothing. Headless Chrome took nothing at any step.

What each state looked like to the page:

- A tab opened with `background` true read `hidden` and unfocused from its first script, with `outerWidth`, `outerHeight`, `screenX`, and `screenY` all 0. It ran no frames, its observers never fired, and the lazy image did not load. Its timers ran once a second from the first tick, not after a grace period. That is also what a tab a person opens behind the current one gets, and nothing in it names DevTools.
- One `Page.captureScreenshot` of that hidden tab ran a few frames, fired the observer for the element in the viewport, and loaded the lazy image, with the tab still `hidden`. Its timers ran twice a second for a moment and then once a second again.
- A window opened with `newWindow` and `background` true read `visible` and unfocused, ran 60 frames a second and full timers, fired its observers, and loaded the lazy image, with no `blur` event, since it had never had focus. Minimizing it gave `visibilitychange` to `hidden`, no frames, and once-a-second timers; restoring it gave `visible` and 60 frames again.
- A window opened with `background` true went behind the windows Chrome already had, and one moved to the same bounds as another read `hidden` within a second. Later that day a 400 by 300 window placed wholly behind another app's window (the Claude app's, found with `CGWindowListCopyWindowInfo`) read `hidden` with no frames for 5 s, `visible` at 60 frames a second once moved where part of it showed, and `hidden` again when moved back, 2 of 2 times. So a window any other window fully covers counts as hidden. In that covered window, a tab opened with `background` true and never painted dropped a DevTools click after `chrome.tabs.update` had made it the active tab, and took one after a 1 by 1 `Page.captureScreenshot`, which ran 4 frames; a tab that had painted before the window was covered took a click and a key while covered, `isTrusted` true. `Page.bringToFront` on a tab in the covered window brought Chrome in front and the tab read `visible`; on a tab in a minimized window it restored the window to `normal` and brought Chrome in front too. Any part showing is enough: with another Chrome window over all of a 400 by 300 window but a 20 px strip, a 2 px strip, or a 1 px row at the bottom, its page read `visible`, and `hidden` again once fully covered. `Browser.setWindowBounds` also placed the window with only 40 px, then 4 px, of its width on the 3,840 px wide screen, and the page read `visible` there; a top past the screen's bottom was clamped so 41 px of the window stayed on screen.
- A tab in the front window read `visible` and focused with Chrome in front. When another app came in front, the tab got one `blur`, read `hasFocus()` false, and kept `visible`, 60 frames a second, and full timers.
- A hidden tab selected with `chrome.tabs.update` from an extension, while another app stayed in front, got `focus` and then `visibilitychange` to `visible`, and read `hasFocus()` true from then on, with 60 frames a second. The tab it replaced got `focus`, `visibilitychange` to `hidden`, and `blur` in that order. A person's Chrome never has a focused tab while another app is in front, so this is a state only automation produces, though a page would have to compare `hasFocus()` with something else to notice.
- `Emulation.setFocusEmulationEnabled` on a `visible` unfocused tab gave it `focus` events and `hasFocus()` true, with the front app unchanged.
- Headless Chrome's own tab read `visible` and `hasFocus()` false from its first script and stayed so, where a windowed tab in front read true. Its viewport was 756 by 413, and the lazy image 3,000 px down did not load in 5 minutes; in the 1,998 px tall window it loaded at once, within Chrome's distance for lazy images.

Timers in a tab that was hidden from the moment it opened, windowed and headless alike: once a second for the first 60 s, then once a minute, with the first wake of each minute 90 s, 150 s, 210 s, and so on after the open. Frames never ran. The page had not paused by 6.5 minutes in the window or 5 minutes headless, where the Android 16 emulator paused at about 5 minutes and the phone stopped timers entirely after a minute, as [Throttling and pausing](#throttling-and-pausing) says. Memory Saver, which can discard a desktop tab, was at its default and did not act.

What a screenshot does to a hidden tab, measured the same day on Chrome 156.0.8078.80 windowed, on tabs opened with `background` true into a window opened with `background` true, with timer ticks and frames counted per second:

- One `Page.captureScreenshot` ran 4 to 6 frames and 2 or 3 timer ticks in that second, fired the observer for the element in the viewport, and left the tab `hidden` at once-a-second timers from the next second on. A 1 by 1 JPEG clip at quality 1, 40 ms and about 1 KB, did the same as a full PNG at 64 to 71 ms. Ten of them 2 s apart gave the same few frames each time and nothing in between, so screenshots do not lift the throttle between them.
- On a tab hidden for 2 minutes, in the once-a-minute phase, one screenshot put its timers back to once a second for the 30 s watched. So a screenshot each minute would keep a long-hidden tab at once a second, and no more.
- `Page.startScreencast` on a hidden tab, at 1 by 1 and acknowledged or not, produced no `screencastFrame` and no frames in the page over 20 s. A screencast does not make Chrome draw a hidden tab.
- Android differs: there a screenshot of a hidden tab is never answered and draws nothing, as [Pages Chrome has not drawn](#pages-chrome-has-not-drawn) says.

Whether a visible page can run once-a-second timers on a normal Chrome, read from Chromium's source and Google's docs on 2026-11-09 rather than measured. Chrome's own throttling rules put a visible page under minimal throttling, 4 ms for nested timers and nothing else; once a second and once a minute are for hidden pages only. Energy Saver (`BatterySaverModeManager`, in `chrome/browser/performance_manager/user_tuning/`) throttles every frame sink to 30 Hz, aligns wake-ups to 32 ms when `kBatterySaverModeAlignWakeUps` is on, tells each renderer, which passes it to V8, and lets hidden pages be frozen after 5 minutes. None of that slows a visible page's timers to once a second. macOS Low Power Mode reaches Chrome only through Energy Saver, when `kBatterySaverModeFollowsSystemPowerSaver` makes Chrome follow it, and through the display's refresh rate. Firefox clamps timers to 1 s only in inactive tabs. So a page that reads `visible` with timers at once a second is a state no setting produces, and FingertipsMCP never shows one: its hidden tabs read `hidden`.

The build that selects tabs through the extension, checked on 2026-11-09 with Chrome 156.0.8078.80 windowed on macOS 27.0.1, through the jar on a scratch config and profile, with the front app read from Launch Services every 150 ms and Slack or Claude in front: Chrome starting with its first window, a session without the screen opening a tab, `read` and `snapshot` with `show`, a tap on the showing tab, opening a tab while holding the screen, a tap on a hidden tab behind it, a `cdp` text into the tab then hidden, `screen_release` putting the placeholder up, closing the tabs, and ending the session. The front app changed 0 times in two runs, the extension loaded at the first `show` with no viewer, and every `show` read `shown` true. The build before it, the same steps the same way, took the front app 5 times: at the first window, each `show`, the tab opened while holding the screen, and the placeholder.

The build that raises a covered window, checked the same way the same day with Chrome's window placed wholly behind the Claude app's window (`--window-position=2200,800 --window-size=400,300`), with the front app given back to the Claude app before each step so the window was covered again each time: a session without the screen opening a tab, opening a tab while holding the screen, `screen_release` putting the placeholder up, closing tabs, and ending the session changed the front app 0 times; `read` and `snapshot` with `show`, a tap on the showing tab, a tap on a hidden tab, and a `cdp` text each brought Chrome in front once, and each landed, with `shown` true. The build before it, on the same covered window, took the front app 0 times, but every input failed with `not_visible` after 2 s and each `show` read `shown` false. In the uncovered run of the same build nothing changed the front app. `chrome/docker/smoke-test.mjs` against the same build on this Mac passed every check, the video viewer's header in 206 ms and its first frame in 215 ms included, with the extension loaded by the first `show` before the viewer connected, and nothing changed the front app.

### Covered and off-Space windows on macOS

On 2026-11-09, with Chrome 157.0.8103.39 on macOS 27 and the Claude app in full screen in front, a scratch-profile Chrome's window opened on the desktop's Space, off screen. Over raw DevTools, without `--disable-backgrounding-occluded-windows` its tab read `hidden` and no `requestAnimationFrame` callback ran in 3 s, though a click and keys from `Input.dispatchMouseEvent` and `Input.dispatchKeyEvent` still reached a field and `Page.captureScreenshot` answered. With the switch it read `visible` and ran 62 callbacks in 1 s. Through the jar, which passes the switch, `show` on a tab there returned `shown` true in 1.9 s, and a tap, `text` with `clear`, a swipe to a `scrollY` of 920, and `GET /v1/screenshot` of the scrolled page worked. Claude stayed the front app throughout.

With the Claude app zoomed to fill the screen on the desktop's Space instead, 0,31 to 3840,2160 in points, later the same day:

- A scratch Chrome's window at 22,53, 1200 by 2085, under the left part of Claude's window, read `visible` and ran 61 frames a second even without the switch. Claude's window is probably translucent there, so macOS did not count Chrome's as covered; that was not checked further.
- A window at 2200,800, 500 by 375 (`--window-position` and `--window-size`), under the opaque part, read `hidden` and ran no frames in 3 s without the switch, though a click and keys still reached a field and `Page.captureScreenshot` answered. With the switch it read `visible` and ran 62 frames a second. Claude stayed in front both times.
- Through the jar, with Chrome's window placed there by `--chrome-arg`: a tab opened in the background read `hidden` until `show`, which returned `shown` true in 1.9 s. A tap, `text` with `clear`, a swipe of three 40 px notches to a `scrollY` of 120, and `GET /v1/screenshot` worked, and Claude stayed in front.
- With the switch, minimizing that window with `Browser.setWindowBounds` and sending `Page.bringToFront` on its tab restored it at 2200,800 and made Chrome the front app, with the tab `visible` at 62 frames a second, 1 of 1 times.
- Restoring that minimized window with `Browser.setWindowBounds` and `windowState` `normal`, with no `Page.bringToFront`, put it back at 2200,800 above every other window, Claude's included, but Claude stayed the front app, 4 of 4 times, each from a fresh window under Claude's. The tab read `visible` at 61 frames a second and took a click and keys into a field.
- Through the jar with that restore in place of `Page.bringToFront`, attached with `--attach` to a Chrome started with the switch and `--enable-unsafe-extension-debugging`, so a second DevTools client could minimize its window, with Slack in front and the window under Claude's: after each minimize, `read` with `show` returned `shown` true in about 1.5 s, and a tap and `text` with `clear` landed, in about 1.3 s and 1.8 s. The window came back at 2200,800 over every other window, and Slack stayed the front app, 4 of 4 times, read every 100 ms through the last run.
- A window under another of Chrome's own windows of the same bounds read `visible` and drew with or without the switch.

### Linux in the Docker image

Checked on 2026-11-08 in CI, with the image built from `docker-bake.hcl`: Google Chrome 157.0.8103.38 from Google's apt repository on Debian trixie, under Xvfb at `1920x1080x24`, on GitHub's Ubuntu 24.04 runners for amd64 and arm64, both with kernel 7.0.0-1017-azure, Docker 28.0.4, and `kernel.apparmor_restrict_unprivileged_userns` set to 1. The image was 1.25 GB on amd64 and 1.26 GB on arm64, and each built in about a minute with no cache. Both architectures behaved the same.

- The first build had no directories at its volumes' mount points, so Docker made them, and `.config` and `.local/share` above them, owned by root. `token create` then failed with `cannot create /home/fingertips/.config/fingertips-chrome/settings.json: Permission denied`, and `google-chrome --version` could not make `~/.local/share/applications`. The image now makes them owned by its user, and a new named volume takes the owner of the directory it is mounted on.
- Under Docker's default seccomp profile, Chrome exited as it started, with `Failed to move to new namespace: PID namespaces supported, Network namespace supported, but failed: errno = Operation not permitted` and then `Zygote process exited prematurely with exit code 82 (setuid sandbox: failed to move to new namespaces)`. That profile allows `clone` with namespace flags, `unshare`, and `setns` only with `CAP_SYS_ADMIN`. A headless Chrome with `--dump-dom` started with a profile that also allowed `clone`, `clone3`, `unshare`, and `setns`, with `--cap-add SYS_ADMIN`, and with `seccomp=unconfined`. It failed the same way with `apparmor=unconfined` alone, so the host's AppArmor limit on unprivileged user namespaces was not what stopped it. `chrome/docker/seccomp.json`, which adds only `clone` and `unshare`, ran the checks below. Which of Chrome's sandboxes ran was not seen, since `chrome://sandbox` under `--dump-dom` did not show its table.
- FingertipsMCP started Chrome as the server started, and the first `GET /v1/device` named `Chrome/157.0.8103.38` with a 1920 by 1080 screen. A session opened `https://example.com/`, and `read` with `show` returned its 917 characters with `shown` true.
- `GET /v1/screenshot` was a 945 by 973 PNG. With no window manager, Chrome's first window opened at that size and did not fill the screen.
- `GET /v1/browser/targets` listed the session's tab and the first window's `about:blank`, which no session owned. Closing both left Chrome running on the placeholder: the log had one `Started Chrome` for the whole run and no restart.
- A video viewer then got a header of 472 by 486, half the window's viewport, and a 786-byte frame of the placeholder 309 to 314 ms after its socket opened. The extension loaded and encoded with software H.264, OpenH264 by its warnings in Chrome's log, with no GPU.
- Chrome logged errors for the missing D-Bus system and session buses and one GPU `kTransientFailure`. None of them stopped anything.

### Still to check

- What Chrome on Linux does when its last window closes, which the placeholder keeps from happening; and what desktop Chrome answers when attaching to a tab that has just closed, so `not_found` covers that race as it does on Android.
- A Chrome stuck at start, such as on the keychain prompt, being killed by a launch that gave up on it, end to end. The unit test covers it with a process that ignores SIGTERM. The prompt asks for the Mac's login password, so it was not brought up again.
- A call cancelled while Chrome starts, end to end. The unit tests cover it, but neither a REST client hanging up nor a short wait cancelled the start on a real Chrome.
- Smooth scrolling on Linux, where a wheel notch may animate; the wait after a flick covers it.
- A viewer's mouse moves followed by an agent's tap on a real Chrome, to see the agent's path start where the person left the pointer. The unit tests cover it.
- Chrome 125 to 148 falling back to `Page.bringToFront` and `Target.activateTarget` for tab selection, end to end on macOS and Linux. On Windows, Chrome 125 ran it, as [Windows 10 in a VM](#windows-10-in-a-vm) says.
- The restore of a minimized window through the jar on Linux with a window manager. Windows is under [Windows 10 in a VM](#windows-10-in-a-vm).
- Whether `--headless=new` loads the viewer's extension. FingertipsMCP for Chrome gives headless Chrome no extension selector, since nothing there takes focus.

### Windows on GitHub's runner

On GitHub's Windows Server 2025 runner on 2026-11-09, with Chrome 156.0.8078.80 and Temurin 21.0.13, started from the job's Git Bash:

- Java's `System.getenv()` map held `PROGRAMFILES` and `PATH` in capitals, as Git Bash passes them, and looks names up by case. A lookup of `ProgramFiles` missed, so the first run found no Chrome. `LOCALAPPDATA` matched only because Windows spells it in capitals. FingertipsMCP for Chrome now looks names up without regard to case on Windows.
- Chrome was found in Program Files. `CreateProcessW` with a handle list and `--remote-debugging-io-pipes` started it, and DevTools answered over the pipe. The smoke test passed: it read a page, took a 973x605 screenshot on a 1024x768 screen, and closed every tab with Chrome still running. The viewer got an H.264 header and a frame 384 ms after connecting.
- Ending the server with `Stop-Process -Force`, which terminates that process alone, made Chrome log "Connection terminated while reading from pipe" and "Could not write into pipe". Every Chrome process with the profile on its command line was gone within the 30 s check, and the runner found none left at the job's end.
- Git Bash's `kill` on the server is no way to end it alone. Git for Windows' `kill` injects `ExitProcess` and falls back to ending the process tree. At the kill, Chrome's network service and GPU process died, the GPU process with exit code -1073741819 (0xC0000005), and a Chrome process other than the browser was still running 30 s later.

### Windows 10 in a VM

Checked on 2026-11-09 on Windows 10 Pro N 22H2 (19045) in a Proxmox VM with no GPU and no audio device, at 1280x800, with Temurin 21.0.13 and Defender's real-time protection on. The server ran in the signed-in desktop session, started by a scheduled task through a hidden `wscript`, so no console window took focus. Notepad stood in for the app the person uses. It was brought in front with a mouse click on its title bar, injected with `mouse_event`. Pressing Alt first, as some tools do to get the foreground, made Windows let Chrome take the foreground afterwards, so no result below used it.

- Chrome 157.0.8103.51, installed for all users from the enterprise MSI, started over `--remote-debugging-io-pipes`. The smoke test passed: a 1019x645 screenshot, every tab closed with Chrome still running, and a viewer header and frame 355 ms after connecting.
- Chrome for Testing 125.0.6422.141, unzipped to `%LOCALAPPDATA%\Chromium\Application`, with `ProgramW6432`, `ProgramFiles`, and `ProgramFiles(x86)` pointed at an empty folder, was found there and started over the pipe. Windows sets a new 64-bit process's `ProgramFiles` from `ProgramW6432`, so changing `ProgramFiles` alone did nothing. The smoke test passed up to the viewer, which refused as it does below Chrome 149. Its sandboxed network service crashed once at start, logging "Sandbox cannot access executable", and restarted. An installer sets the folder's access list that an unzipped copy lacks, so this is from how it was installed.
- Focus, on both versions: the first window opened at 10,10, 1050x740, behind Notepad, and Notepad stayed in front. Opening tabs, `show` on a partly covered window, screenshots, closing every tab, the placeholder, and the viewer all left Notepad in front. On Chrome 125, which selects tabs with `Page.bringToFront` and `Target.activateTarget`, Notepad stayed in front too.
- With Notepad covering Chrome's window wholly, the selected tab read `hidden` within a second. `show` on it sent `Page.bringToFront`, and Windows refused Chrome the foreground: Notepad stayed in front, Chrome's taskbar button flashed orange, and the tab still read `hidden` with `document.hasFocus()` true. The read returned `shown` false after about 6.3 s, 4 of 4 times. A tap on a field there failed with 409 `not_visible` after about 2.1 s, and nothing was typed anywhere. Raising Chrome's window with `SetWindowPos`, `HWND_TOP`, and `SWP_NOACTIVATE` from another background process returned success and changed nothing.
- With Chrome's only window minimized and Notepad in front, `show` restored the window and Chrome took the foreground, and the tab read `visible` within about 1 s, 2 of 2 times.
- On a Chrome 157 started by hand with `--remote-debugging-port` and a scratch profile, driven over raw DevTools with Notepad in front of every window:
  - `Target.createTarget` with `newWindow` and no `background` opened a window at 20,20, behind Notepad. Its tab read `hidden` with `document.hasFocus()` true, and Notepad stayed in front. A new tab with no `background` in a covered window stayed behind Notepad too, though it read `visible`.
  - `Page.bringToFront` on a minimized window took the foreground only while every Chrome window was minimized: 4 of 4 times, with the window minimized by `SC_MINIMIZE`. With another Chrome window open behind Notepad, the window restored behind Notepad, 3 of 3 times, whether `SC_MINIMIZE` or `Browser.setWindowBounds` had minimized it. Notepad's size made no difference.
  - Minimizing every Chrome window with `Browser.setWindowBounds` and then sending `Page.bringToFront` on a tab brought its window in front with the foreground, and the tab read `visible`, 3 of 3 times, from two windows behind Notepad. The other window stayed minimized.
  - With `--disable-backgrounding-occluded-windows`, on 157.0.8103.51, restoring a window minimized by `Browser.setWindowBounds` with `windowState` `normal` and no `Page.bringToFront` did what `Page.bringToFront` did. As Chrome's only window, it came back in front and took the foreground, 3 of 3 times. With another Chrome window open behind Notepad, it came back behind Notepad and Notepad kept the foreground, 3 of 3 times, and its tab read `visible`, ran 66 frames a second, and took a click and keys into a field.
  - Started with `--disable-backgrounding-occluded-windows`, and again with `--disable-features=CalculateNativeWinOcclusion` instead, a window wholly behind Notepad read `visible`, ran 66 `requestAnimationFrame` callbacks in 1 s, and took a click from `Input.dispatchMouseEvent` and three characters from `Input.dispatchKeyEvent` into a field, and `Page.captureScreenshot` answered, with each. Notepad stayed in front. Through the jar, which now starts Chrome with the switch, on a tab opened in a window wholly behind Notepad: `show` returned `shown` true in 1.2 s, a tap, `text` with `clear`, and a swipe worked, ending at a `scrollY` of 300, and `GET /v1/screenshot` showed the page scrolled. Notepad stayed in front throughout, and the tab read `visible`. Without either, the same window read `hidden` and a `requestAnimationFrame` callback never ran.
- The restore of a minimized window through the jar, on 2026-11-09, with Chrome started the usual way over its pipe, the window minimized with `SC_MINIMIZE`, and Notepad, at 0,0 1280x760, given the foreground with a click on its title bar before each call:
  - On Chrome 157.0.8103.51 with a second Chrome window open behind Notepad, `read` with `show` restored the window behind Notepad, above the other Chrome window, and returned `shown` true in 1.0 to 1.8 s. A tap and `text` with `clear` then landed, and the tab read `visible`. `text` with no `show` before it restored the window too, in about 1.8 s. Notepad kept the foreground, 6 of 6 times.
  - With Chrome's only window minimized, `read` with `show` restored it in front, Chrome took the foreground, and `shown` was true in about 1 s, 3 of 3 times, as `Page.bringToFront` did before.
  - On Chrome for Testing 125.0.6422.141, where the extension can't select tabs, the code restores the window and then sends `Page.bringToFront`. Which of the two moved the window was not seen apart. As Chrome's only window it came in front with the foreground, 3 of 3 times. With a second window open it came back behind Notepad, which kept the foreground, 2 of 2 times. Each time `shown` was true in 1.0 to 1.4 s and `text` landed.
- `text` with `clear` replaced the text of an input and a textarea through Ctrl+A and Backspace, with Chrome partly covered and Notepad in front.
- A swipe over 0.6 of the page sent 3 wheel events of `deltaY` 100 in pixel mode, and each scrolled exactly 100 px, ending at 300. No scroll event came between notches, so Chrome's smooth scrolling did not animate them, and the position read right after the swipe returned was already final.
- Closing Chrome's only window with `SC_CLOSE`, as its close button does, left Chrome running with no window on 157 and on 125, with and without the viewer's extension loaded. The next `POST /v1/browser/tabs` opened a window in the same Chrome process, behind Notepad. Chrome started the usual way exits when its last window closes, so this comes from how FingertipsMCP starts it.
- Stopping the server with Ctrl+C in its console ended it and Chrome within 1 s, and Chrome's profile recorded `exit_type` `Normal`.
- Stopping it with Chrome's browser process suspended with `NtSuspendProcess`: the thread reading the pipe was in `ReadFile` 2 s in and gone 4 s in, after `Browser.close` timed out at 3 s and the connection closed, while Chrome was still suspended. So `CancelIoEx` ended the read. `TerminateProcess` killed Chrome with exit code 1 at 13.2 s, and the server exited at 13.3 s. The 13 s is two 5 s waits: `LaunchedChrome.stop` waits for the exit after closing the connection, then `terminate` closes the already closed pipe and waits again.
- `token create` ran 20 of 20 times while the server ran, and the server took a token made while it ran at once.
- With `pairing.json` held open by another process, `token create` minted the token, then failed to replace the file with an uncaught `AccessDeniedException` from `Files.move`, leaving `pairing.json.new` beside it. It failed the same with the holder sharing read, write, and delete. Windows does not rename over a file another process has open.
- Chrome 157 and 125 wrote their error-level lines to `chrome.log`, 126 lines over the runs, most of them "Could not write into pipe" as Chrome shut down.
- The viewer's audio socket sent its Opus config and no packets while a page played a 2 s tone. The page's `AudioContext` ran at 44.1 kHz with its clock advancing, and `enumerateDevices` listed no audio output, so whether capture works with a device was not shown.

### Still to check on Windows

- The viewer's sound, on a computer with an audio output.
- The viewer's capture on a desktop with a GPU.
- Chrome found in a per-user install that Chrome's own installer made, and Windows 11.
- Covering and raising with a person's real input, rather than injected clicks.

### Tab capture for a viewer

Measured on 2026-11-08 with Chrome 156.0.8078.80 on macOS, headful and headless, on scratch profiles over `--remote-debugging-pipe`, to see how a remote person could watch and use a tab with H.264 and Opus rather than a stream of JPEGs. The viewer built on it is under [The viewer](#the-viewer).

- An extension's `chrome.tabCapture` captured a tab with no picker and no sharing banner, and the page's size did not change, headful or headless. A person watching the screen saw no capture indicator in the tab strip or toolbar. `chrome.tabCapture.getMediaStreamId({targetTabId})` refused with "Extension has not been invoked for the current page" until the extension was invoked on that tab. `Extensions.triggerAction` invoked it, given the tab's target of type `tab` from `Target.getTargets` with `filter: [{}]`; given the page target, it answered "Action can only be triggered on a tab target." The extension's `action.onClicked` then got a stream id, and a page of the extension opened it with `getUserMedia` and `chromeMediaSource: 'tab'`, as a background tab, with tab audio.
- `Extensions.loadUnpacked` and `Extensions.triggerAction` worked on a plain launch over the pipe, with no extra switch. A loaded extension was gone after a relaunch of the same profile, so it would be loaded at every start.
- `getDisplayMedia` captured a tab without the picker only with `--auto-select-tab-capture-source-by-title`, and only from a page with focus: from a background tab it failed with `InvalidStateError: Invalid state`. Chrome then put a "sharing this page" banner with a Stop sharing button above the captured page, which shrank it by 56 px (1998 to 1942 headful, 469 to 413 headless) with a `resize` event the page could see. The banner was still there 4 s after the tracks stopped.
- With either kind of capture, a captured tab moved behind another stayed `visible`, with no `visibilitychange` and `requestAnimationFrame` still running, while `document.hasFocus()` went false. Capture kept giving 30 fps from it. Without capture, the same switch made the tab `hidden`.
- A tab that does not repaint gives the capture no frames, so a read of the track can wait indefinitely.
- `Page.startScreencast` gave about 55 fps of JPEG at 600x1000 and quality 60, about 5 Mbps, while the tab showed, and nothing while it was hidden. Started on a hidden tab, it gave no frames even once the tab came forward.
- WebCodecs `VideoEncoder` in the extension's page, in realtime mode, encoded the capture. At half the tab's size, 600x998, with a 4 Mbps target and 30 fps, software H.264 took about 7 ms a frame and kept every frame, at about 130 kbps for a mostly still page. Hardware H.264 (VideoToolbox) took about 60 ms a frame and fell behind, encoding about two frames in three. H.264 level 4.0 (`avc1.640028`) was unsupported at the full 1200x1998, which has more macroblocks than 4.0 allows; level 5.1 (`avc1.640033`) worked. VP8 and software VP9 also worked, at larger output; hardware VP9 was unsupported. `AudioEncoder` encoded the tab's audio as 48 kHz stereo Opus at about 98 kbps.

So a viewer for Chrome could load a small extension at launch, invoke it on the screen holder's tab or the placeholder, capture and encode in the extension's page, take the encoded chunks out through a DevTools binding on that page, and send them on the viewer WebSocket that already decodes H.264 and plays Opus. A stream id is for one tab, so a change of tab means invoking the extension again.

Still to check for capture: whether an indicator shows anywhere not watched, such as Chrome's menu, the tab's hover card, or the macOS menu bar; how long an invocation lasts across navigations; what 30 fps costs in CPU in the Docker image, where there is no GPU and the encoder is OpenH264; and whether the extension's targets need hiding from listings, as the placeholder is.

### The viewer

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, through the viewer page in another browser:

- `Extensions.triggerAction` takes a `tab` target, which `Target.getTargets` lists apart from the tab's `page` target with nothing that links them: no `parentId`, only the same URL. Attaching to the tab target and turning on `Target.setAutoAttach` reports exactly one page, its own, which links them.
- The extension's offscreen document is listed twice: as a `background_page` target, which is the one to attach to, and as a `tab` target with the same URL.
- An action run right after `Extensions.loadUnpacked` was lost, since the new service worker had not yet registered its listener. The worker now opens its offscreen document as it starts, after its listeners, and FingertipsMCP waits for that document before running the action.
- Chrome kept running the service worker it had stored for an unpacked extension's path, from an earlier load in the same profile, after the files changed. A higher `version` in the manifest changed nothing, and `Storage.clearDataForOrigin` for the extension's origin failed with Internal error. A new path, and so a new id, ran the new worker.
- At 1200 by 1998 CSS pixels at a pixel ratio of 1, the viewer got 600 by 998 H.264 at about 28 fps, 31 to 40 kbps for a moving box. A switch to another tab moved capture there, with a new header and key frame. The tab no longer shown read `hidden` about 1.7 s after its capture's tracks stopped, not at once.
- The audio socket sent the Opus config and then 100 packets for a 2 s tone, and none while the tab was silent before and after, so a silent tab captures samples within 8 of zero.
- A page fetching `chrome-extension://<id>/` for the extension's manifest, worker, and document, with its real id, got the same `TypeError: Failed to fetch` at once as for an id no extension has, and `chrome.runtime` was absent from the page.
- From the viewer page: a click, mouse moves, a wheel turn, and keys reached the tab. The click landed on the field it was aimed at, and the field held what was typed.

Checked again on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on scratch profiles, after the viewer moved to the phone's frame delivery. A WebSocket client stood in for the viewer page, to log headers, frame types, and close reasons:

- `Target.setDiscoverTargets` with `filter` `[{type: "tab"}, {type: "background_page"}]` never reported the offscreen document. Chrome creates it as a target of type `other` with no URL, then names it `background_page`, with its URL, in `Target.targetInfoChanged`, which it sends only for a target it reported created. With `filter` `[{}]` both events came.
- `Target.setAutoAttach` on the browser refuses a filter that allows both `tab` and `page`: "Filter should not simultaneously allow "tab" and "page", page targets are attached via tab targets". A session on a tab target with `setAutoAttach` and `filter` `[{type: "page"}]` reported its page at once, and a new tab's page as it opened. Closing the tab detached both sessions. A popup opened while every tab had such a session, on a Chrome with no extension, opened as usual.
- A joining viewer got the header, then a key frame. On a still page it got the settle redraws in its first second and then a frame every 4.00 s (gaps of 3,997 to 4,008 ms). A switch to another tab gave a new header and key frame within 0.1 s.
- A viewer that completed the WebSocket handshake and never read, with a 4 KB receive buffer, sat beside one that read for 25 s, on a canvas of fresh noise every frame at 4.1 Mbps. The reading viewer kept 30 fps throughout, and got about 2 key frames a second before, during, and after, the same rate. On a moving box at 150 kbps it got key frames only as viewers joined and at the 10 s backstop.
- A capture keeps its frame size. Captured at 1300 by 813 CSS pixels, 650 by 406 frames, the window set to 800 by 600 with `Browser.setWindowBounds` left the track's settings and frames at 650 by 406. `applyConstraints` on the track changed its settings, but a still page sent no frame at the new size. Capturing the tab again failed with "Cannot capture a tab with an active stream." from `tabCapture.getMediaStreamId`, until its capture had stopped. Stopping it first worked: 900 by 613 CSS pixels gave a header of 450 by 306 naming 900 by 613, and 1300 by 813 gave 650 by 406. Moving the window changed nothing. The pipe has room for one DevTools client, and a person's own Chrome was running, so no window was scripted by app name; these resizes ran with `--attach` to a Chrome started with `--remote-debugging-port` and `--enable-unsafe-extension-debugging`, and a second DevTools client on that port.
- Chrome quit under a viewer (SIGTERM, as a person quitting does): the video socket closed with 1011 "Chrome stopped" within a second, the audio socket with 1000, and no Chrome started again until the next call. The build before this change left both sockets open with no frames, and a tab switch the extension reported as Chrome quit made it start a new Chrome.
- The audio socket sent its header, the Opus config, and 101 packets for a 2 s tone.
- Chrome exited, with a line from Crashpad in its log, about 2 s after capture moved to a tab in a popup window that a page opened with `window.open(url, name, "popup,width=700,height=500")`. It did so three times, on the build before this change too. The popup alone, with no viewer, did not. [The popup crash](#the-popup-crash) has the cause.
- Each frame crosses the pipe as base64 in JSON. With a canvas of noise at 4.1 Mbps and 30 fps, 600 by 998 frames, and one viewer, the JVM used 0.51 s of CPU in 40 s on an M2 Pro, about 1.3% of one core, against 0.01 s in 20 s with no viewer. Making one 17.5 KB frame's base64 and JSON in Node 26 took 0.08 ms, 0.25% of a core at 30 fps.

### The popup crash

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on scratch profiles. Each step ran on its own over raw DevTools, on a Chrome started with `--remote-debugging-port` and `--enable-unsafe-extension-debugging`, with the viewer's extension loaded and no capture running:

- `Extensions.triggerAction` on the tab target of a popup that a page opened with `window.open(url, name, "popup,width=700,height=500")` killed Chrome about 0.35 s later, before it answered, 2 of 2 times. No `getMediaStreamId` or `getUserMedia` had run. macOS's crash report had a SIGSEGV at address 0 on the browser's main thread. The same call on a tab that `window.open(url, "_blank")` opened in the normal window worked 3 of 3 times, and a capture after it did too.
- It killed Chrome the same way on a tab in a window of another browser context, made with `Target.createBrowserContext` and `Target.createTarget` with `newWindow`.
- Chromium's `ExtensionsHandler::TriggerAction` at the 156.0.8078.80 tag, in `chrome/browser/devtools/protocol/extensions_handler.cc`, takes the window's `ExtensionsContainer` and the extension's action in it without checking either for null. A popup window has no toolbar, and so no container. A window of another profile has no action for this extension. On the main branch, read the same day, the function checks both and answers "Browser window does not support extension actions." and "No action found for the provided extension id." instead. The history that would say which release has the checks needs a sign-in.
- `Browser.getWindowForTarget` gives the same window id as `chrome.windows`. The extension read the popup's window as type `popup` and the other's as `normal`, and `chrome.windows.get` for the other context's window failed with "No window with id".

So FingertipsMCP runs the action only on a tab in a window the extension reads as `normal`. With that, on the same Chrome, through the jar and a WebSocket viewer:

- A popup opened from the captured tab left capture on that tab, with no crash, 4 of 4 times. Frames kept coming at the still-page rate, and the log said the popup was passed over.
- A tab opened with `_blank` was followed, with a new header and key frame within 0.1 s.
- Closing the captured tab with the popup still open moved capture to the placeholder. 1 of 3 times it sent a header within 0.1 s. 2 of 3 times it sent no frame at all in the 5 s that followed, so the viewer page would show no picture and reconnect after 12 s. Closing the captured tab with no popup gave a header within 0.1 s, 1 of 1. Before this change the same sequence crashed Chrome, so this was not seen before. [The first frame](#the-first-frame) has the cause, which has nothing to do with the popup.

Windows of type `app`, checked the same day with Chrome 156.0.8078.80 on macOS, windowed, on scratch profiles, over raw DevTools with the viewer's extension loaded and no capture running:

- A window opened with `--app=URL`, given to the running Chrome on the same profile: `chrome.windows.get` and `getLastFocused`, with their default filter, read it as type `app`. `Extensions.triggerAction` on its tab killed Chrome about 0.35 s later, before it answered, with a segmentation fault, 2 of 2 times, as on a popup's tab.
- An installed web app's window: `PWA.install` and `PWA.launch`, which Chrome answered only over `--remote-debugging-pipe` ("'PWA.install' wasn't found" over the port), with the app's display mode set to `standalone` through `PWA.changeAppUserSettings`, since the first launch opened it as a tab in the normal window. The extension read this window as type `app` too. `Extensions.triggerAction` on its tab worked 3 of 3 times, the worker got a stream id, and a capture of it gave frames.
- `chrome.windows.get` with `populate` gave the two windows the same fields, and with no `tabs` permission their tabs carry no URL. Nothing the extension reads tells them apart.

So FingertipsMCP passes over every `app` window, as it does a popup, though an installed web app's window would work.

### The first frame

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on scratch profiles, through the jar and a WebSocket viewer. Some runs used `--attach` to a Chrome started with `--remote-debugging-port` and `--enable-unsafe-extension-debugging`, so a second DevTools client could probe the offscreen document and record a trace of the `gpu.capture` and `media` categories.

- A new capture of a still tab sometimes sent no frame at all, whether the tab was the placeholder or a page just brought forward, and with or without a popup open. The track was `live` and not `muted`, and the page read `visible`. Left alone, it stayed without a frame until the tab repainted: 0 of 2 recovered in 4 s, and one got its first frame 10.6 s in, when another tab opened.
- Chrome's first frames of a capture reach the track before `getUserMedia` answers, so before the offscreen document can read them. The reader's `MediaStreamTrackProcessor` asks for a frame as it attaches, which Chrome turned down (`FpsRateLimited`, trigger `demand`) since it came within one frame period of the last one. Chrome then retried it as a refresh every 67 ms. In a capture that worked, a retry 77 to 99 ms in was taken and became the reader's first frame, 24 of 24 times.
- In each capture that got no frame, Chrome's first copy of the tab had failed (`Capture` ended with `result` 2, `kI420ReadbackFailed`), and every retry after it was turned down (`FpsRateLimited`, trigger `refresh`) for as long as the capture ran. In Chromium's code at the 156.0.8078.80 tag, `FrameSinkVideoCapturerImpl::MaybeDeliverFrame` skips `VideoCaptureOracle::CompleteCapture` for a copy that failed, so the oracle counts that frame as pending for good, and `ObserveEventAndDecideCapture` takes a refresh request only while none is pending. A refresh demand and a repaint of the tab skip that check.
- A new sink on the track demands a frame. `ImageCapture.grabFrame()` on the stuck track, a `clone()` of it read by a second processor, and `Page.captureScreenshot` on the tab each brought a frame within 1 s, 2 of 2 times each, and the reader got it too.

So the offscreen document now asks for a frame with `grabFrame` 100, 300, and 700 ms after a capture starts, and then every 4 s, until its first frame comes. Since [The last repaint](#the-last-repaint), every redraw asks this way, and a capture's start counts as its last frame. Over 45 rounds of bringing a static page forward and closing it, 90 tab switches and 89 captures on each build, with the trace on:

- Before: 5 captures had a failed first copy. 4 never got a frame, and 1 got one 6.5 s in. 5 switches left the viewer with no picture.
- After: 4 captures had a failed first copy. All 4 got their first frame 106 to 108 ms in, from the request at 100 ms. Every capture got a frame within 108 ms.

Through the jar on its own Chrome over the pipe, with the fix: capturing a page, opening a popup from it, waiting 5 s, and closing the page gave a header and a frame within 0.17 s, 15 of 15 times. Without the popup, 11 of 12. The other one was the first round after the viewer connected, where capture never moved to the page brought forward, as also happened before the fix; see the list below. Before the fix, with the popup, 1 of 4 rounds gave no picture after the close, and without it, 2 of the 12 captures in 6 rounds got no frame.

### The last repaint

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on a scratch profile started with `--remote-debugging-port` and `--enable-unsafe-extension-debugging`. A raw DevTools client loaded the viewer's extension and drove its offscreen document as FingertipsMCP does, at 30 fps and 600 by 500. Each round opened a still page, captured it, waited, and then changed the page's color twice in back-to-back `requestAnimationFrame` callbacks, one display frame (16.7 ms) apart. A `gpu.capture` trace showed what the tab's capturer did with each paint, the track's `stats` counted the frames it discarded, and a wrapper on the document's encoder read the color of each frame it took, the last of which is what a viewer keeps showing.

- The capturer limits the rate with a token bucket of about 50 ms. After a still spell the bucket is full, so whether it takes the second paint 16.7 ms after the first depends on a fraction of a millisecond. When it turned the second paint down (`FpsRateLimited`, trigger `compositor`), it sent it 67 ms later as a refresh, and the viewer got it.
- When it took both, the track dropped the second (`discardedFrames` up by 1), and nothing sent it again, so the extension's last frame was the first paint. In Blink's `VideoTrackAdapter` at the 156.0.8078.80 tag, `MaybeDropFrame` drops a frame that comes sooner than 80% of the period `maxFrameRate` gives after the last, less a drift it builds up from late frames and resets after a gap of 1 s. So the drop needs the frame before the burst to be more than about 1 s old.
- Before this change, with the extension encoding its last frame again while the tab was still, the final paint was lost in 24 of 45 rounds that burst 1.5 s after the capture started, and 7 of 30 that burst after 3 s. Each loss had both paints taken by the capturer and one discarded by the track. The viewer showed the first paint until the page painted again.
- Without `maxFrameRate` in the capture's constraints, the track dropped nothing, 12 of 12, and the capturer still ran at 30 fps, its default. But the capturer takes its rate from that constraint: with `fps` 10 its minimum period was 100 ms with it and 33 ms without, so it can't go.
- No capture in the 162 traced rounds had a failed first copy, so the other way of losing a repaint, a capture that takes no refresh for good (see [The first frame](#the-first-frame)), did not come up. A demanded frame gets past it, as measured there.
- `ImageCapture.grabFrame()` resolved 1 to 4 ms before the reader got the frame it brought, 40 of 40 times. `VideoFrame.metadata()` was empty, so nothing on a frame says what asked for it.

So the extension now asks for the tab's picture with `grabFrame` at each redraw, 100, 300, and 700 ms after the last frame and then every 4 s, and encodes the frame that brings rather than the last one again. It counts the first frame after an ask as the redraw's. After the change, 30 of 30 rounds that burst after 3 s ended on the final paint. In the 10 where the track discarded it, the redraw's frame brought it 112 to 135 ms after the burst. Through the jar, with a WebSocket viewer, 15 of 15 bursts ended on the final paint, with 4 or 5 frames reaching the viewer for each. On a still tab for 40 s, 10 frames went out on either build, and Chrome's GPU process used 0.03 to 0.05 s against 0.01 to 0.02 s before, about 2 ms for each ask.

### The popup notice

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on a scratch profile, through the jar, with the viewer page open in a second browser. The page's own pane was hidden, so a Node WebSocket client held a video viewer open for the first part:

- Clicking a button in the captured tab through the viewer, which ran `window.open(url, name, "popup,width=500,height=400")`, showed the notice within 3 s, and capture stayed on the opener with no crash. A page loaded while the popup was open got the notice on connect, both when another viewer kept capture running and when it was the only viewer and capture started again, 1 of 1 each.
- Closing the popup with `POST /v1/browser/targets/{id}/close`, while Chrome was not the active app, fired neither `tabs.onActivated` nor `windows.onFocusChanged`, so the notice stayed, 1 of 1. With the worker also listening to `windows.onRemoved`, the same close cleared it, 1 of 1.
- A `cdp` tap on the opener's tab with the popup open ran `follow`, but `chrome.windows.getLastFocused` still gave the popup, and the notice stayed. Input over DevTools doesn't move window focus.
- Bringing the opener's window forward with the popup open, the same day, through the jar with `--attach` to a Chrome started with `--remote-debugging-port` and `--enable-unsafe-extension-debugging`, and a WebSocket client on the video and input sockets. `Target.activateTarget` on the opener's page and `chrome.windows.update` with `focused` true on its window, from the extension's worker, each brought it forward: `getLastFocused` gave the normal window, and `document.hasFocus()` was true in the opener and false in the popup. Each time the worker's `windows.onFocusChanged` told the document, and the notice cleared within 0.06 s, 2 of 2 times each way. Bringing the popup forward the same two ways set it again within 0.06 s, 2 of 2 each, and closing the popup cleared it. A person's click on the opener's window was not tried, since a person's own Chrome was running and no window was scripted by app name, but the click moves the same window focus.
- The first viewer after a new extension version loaded failed once, 74 ms after the load, with "The viewer extension did not say what type the window is: no answer", which the window check before this change could hit too. The page's reconnect 10 s later worked. [The offscreen document's script](#the-offscreen-documents-script) has the cause.

### The offscreen document's script

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on scratch profiles, through the jar, with a WebSocket client as the viewer. Each try started a server on a profile that held the last try's version, with a jar whose `offscreen.js` differed by a comment, so the first viewer loaded a new version:

- Chrome names the offscreen document, in the `Target.targetInfoChanged` that gives its URL, while the document is still loading. A probe that attached when the URL came, 53 to 63 ms after `Extensions.loadUnpacked` answered, read `document.readyState` as `loading` 5 of 5 times, with no `fingertipsViewer` 4 of 5 times. A `fingertipsViewer.window(1)` sent next threw `ReferenceError: fingertipsViewer is not defined` 1 of 5 times. That reply has `exceptionDetails` and no `result.value`, which the viewer read as "no answer".
- The script ran 1 to 3 ms after Chrome named the document. The viewer attached and waited for `Runtime.addBinding` before its first call, and that was long enough: with the window check moved to just after the load, the document was complete by the check 27 of 27 times, and 6 of 6 on a fresh profile. With `addBinding` sent without waiting for its answer, the first window check failed with "no answer" 1 of 10 times, the failure under [The popup notice](#the-popup-notice).
- With the viewer waiting for the document's `DOMContentLoaded` before its first call, and `addBinding` still sent without waiting, 15 of 15 first viewers captured. 9 attached while the document was loading, 1 of those before `fingertipsViewer` existed, and each wait took 1 to 2 ms. Built as it ships, 8 of 8 first viewers after a new version captured at once.

### The viewer on older Chrome

Checked on 2026-11-08 on macOS with Chrome for Testing builds, each with `--use-mock-keychain` and a scratch profile, and with Chrome 156.0.8078.80:

- Chrome 125.0.6422.141 has no `Extensions` domain: its `/json/protocol` lists none, and `Extensions.loadUnpacked` and `Extensions.triggerAction` answer `-32601`, "wasn't found". Before this change the video socket closed with 1011 and that message, after a stack trace in the log.
- In the `devtools-protocol` package, which follows Chromium's protocol by commit position, `Extensions.loadUnpacked` first appears at 1289136, just after Chrome 125's branch point (1287751), and `Extensions.triggerAction` at 1577676, between the branch points of Chrome 145 (1568190) and 146 (1582197). Chrome 145.0.7632.117 had `loadUnpacked` and answered `-32601` for `triggerAction`; 146.0.7680.165 had both.
- Sent with no parameters, a command Chrome has answers `-32602`, "Invalid parameters", and does nothing; one it lacks answers `-32601`. That held on 125, 145, 146, and 156.
- Over the pipe, Chrome 145.0.7632.117, 146.0.7680.165, and 148.0.7778.178 refused `Extensions.loadUnpacked` with `-32000` "Method not available." unless Chrome was started with `--enable-unsafe-extension-debugging`. With the flag, 146 loaded the extension and ran its action. Chrome 149.0.7827.155, 150.0.7871.124, 151.0.7922.138, and 156.0.8078.80 loaded it and ran the action with no flag. So the viewer works from Chrome 149.
- Through the jar on Chrome 149.0.7827.155, windowed, with a WebSocket client as the viewer: the header and a key frame came 0.1 to 0.8 s after the video socket opened; a click and a key sent on the input socket after `take` landed in the page; capture followed a tab brought forward, with a new header and key frame; a popup opened from the captured tab left capture there and sent the notice; and closing the captured tab, with only the popup left, gave the placeholder's header and first frame 50 ms after the close. So tab targets, a tab session's `setAutoAttach`, and the offscreen document's target events work there as on 156. The same run on 156 gave the same results.
- With nothing captured, as on Chrome 125, a click sent on the input socket ended the socket with 1006, since the failure to find a tab escaped the socket's handler. A key got an `error` reply and left the socket open.

So FingertipsMCP refuses the viewer with 422 `unsupported` on a Chrome without either command, before it writes or loads the extension, and on one that refuses the load. Through the jar, Chrome 125 and 148 closed the video and audio sockets at once with that reason. A click with nothing captured is now dropped, and the input socket stays open.

### A tab brought forward as the first viewer connects

Checked on 2026-11-08 with Chrome 156.0.8078.80 on macOS, windowed, on a scratch profile, through the jar and a WebSocket viewer, from a fresh server start each round: a session without the screen opened a hidden tab, a video viewer connected, and the session sent a `cdp` Shift+A into its tab 0 to 150 ms after the socket opened, which brought the tab forward. Debug logging in the extension's worker and in `ChromeViewer` gave the order of events. Some steps ran again over raw DevTools, on a Chrome started with `--remote-debugging-port` and `--enable-unsafe-extension-debugging`, with the viewer's extension loaded.

- `Extensions.loadUnpacked` answered 30 to 70 ms before the new worker registered its listeners. A switch in that gap fired no `tabs.onActivated` the worker saw, so no news of it came.
- A tab brought forward still read `hidden` 55 to 160 ms after the worker's `tabs.onActivated` for it, while the tab Chrome had shown read `visible`; `Page.bringToFront`'s caller saw it `visible` a little later. So `ChromeViewer`, which took the tab Chrome shows to be the visible one, read the tab Chrome had shown, both when it started capture and when it followed news that came during that start.
- `Extensions.triggerAction` on a tab that was not its window's active tab ran the action on the active tab: `action.onClicked` got the active tab's id, 1 of 1 over raw DevTools. Through the jar, a capture `ChromeViewer` asked of one tab then failed with "Cannot capture a tab with an active stream." because the action ran on the tab already captured, 1 of 28 rounds with the change below, which closed the viewer.
- Starting a capture with `getUserMedia` on a tab that was no longer active made Chrome activate it again, 2 to 25 ms before `getUserMedia` answered. Over raw DevTools, with the action run on tab A and tab B brought forward before the capture of A: A was active again in the end 3 of 3 times when B came forward right after the action, and 1 of 2 when it came 0.8 s after it; the other time Chrome had lost focus to another app first. Through the jar, a tab brought forward between the action on the placeholder and that capture's start was switched away from again, so Chrome showed the placeholder, and so did the viewer.

Before the change, a tab brought forward 20 to 150 ms after the viewer connected was never followed: 18 of 18 rounds kept the placeholder. At 0 ms the switch came before capture started, and 3 of 3 were followed. `ChromeViewer` now asks the extension's worker for the tab Chrome shows: the active tab of the normal window Chrome focused last, from `chrome.windows`, linked to its DevTools target by `chrome.debugger.getTargets`. Its ids matched the targets DevTools lists. After the change, with the switch 0 to 40 ms after the viewer connected, 10 of 10 rounds followed the tab. With it 60 to 150 ms after, 6 of 18 did. Of the other 12, the 4 with the worker's log each had the action run on the placeholder, then the tab brought forward, then the placeholder activated again as its capture started, and the worker read the placeholder as Chrome's active tab to the end, so the viewer showed what Chrome showed. One of the 12 is the closed viewer above; the other 7 were not logged in full.
With the change, a popup that a `cdp-mouse` click on the captured tab opened with `window.open(url, name, "popup,width=500,height=400")` left capture on the opener, with no crash and frames still coming, 1 of 1.

### Still to check for the viewer

- Headless Chrome on Linux with the viewer, and input and tab switches through the viewer in the Docker image, where only the placeholder's first frame has been seen.
- A capture whose first copy failed, with a burst whose last paint the capturer puts off, to see the redraw's frame bring it as it does after a track drop. No such capture came up in 162 rounds on 2026-11-08.
- A capture's start activates its tab again, as under [A tab brought forward as the first viewer connects](#a-tab-brought-forward-as-the-first-viewer-connects), which undoes a tab switch in the 200 ms before it. Which Chromium code does it, whether it happens with every capture start or only while Chrome has focus, and whether the steady-state follow can hit it when a second switch comes as the first is captured.
- A switch between `ChromeViewer` reading the tab Chrome shows and running the action makes the action run on the other tab, and Chrome refuses it when that tab is the one captured, which closes the viewers.
- A viewport that changes while the window does not, as with page zoom, the bookmarks bar, or docked DevTools: no window event comes, so the capture keeps the old viewport.
- The resize with the pipe rather than `--attach`, on a Chrome a script may resize.
- Whether the tab's sound played back on the computer while it was captured, which a person has to listen for.
- Whether the encoding's CPU load shows in a page's own timing.
