# Sharing the phone

Several clients can drive one phone at once: two agent conversations, an agent and a person in `/viewer`, or a script on REST. Without rules, one taps while the other types, one navigates a tab the other is reading, or one sees what the other left on screen. These rules let them share the phone without that, and let background Chrome tabs be used freely while someone else has the screen.

PhoneAPI owns the rules, because only it knows which calls take the screen and which tabs exist. A client only decides how long its session lives, and a model needs no lock tools. [api.md](api.md) lists the routes and tools. [device-notes.md](device-notes.md) has the Android and Chrome behavior these rules rest on, as measured, and what is still to check on a phone.

## Goals and limits

- No client drives the screen while another holds it, and none reads, navigates, or closes another's tabs.
- Background tabs need no lock. Reading and cdp input on a client's own tabs run while someone else holds the screen.
- Background means background. Chrome never shows a tab whose owner lacks the screen, so a tab behaves the same whether one conversation is running or several. A workflow that only works while its tab is on screen fails every time, not only under load.
- Waiting is the tool call blocking, not the model polling.
- The next holder starts from a known screen: home, keyboard hidden, and locked if it was locked.
- Every client gets the same rules: MCP clients, a person in `/viewer`, and REST scripts.
- Not a privacy boundary. Every client uses the same Google and app accounts on the phone, so Chrome history, Maps searches, and app state are shared whatever cleanup does. `device_info` and the `state` on errors still show the foreground package. Cleanup keeps one conversation's screen from greeting the next.
- Not isolation between sites. Tab ownership separates tabs, not cookies, logins, or what a site keeps on its servers. Two conversations on one site can log each other out, share a shopping cart, or trip a site that refuses a second open tab. That is the same problem as two threads editing one Google Doc, and a tab lock cannot fix it. Incognito separates the state but drops the logged-in accounts that are the point of using the phone, so it is a choice per tab, as in [Incognito tabs](#incognito-tabs).
- Not a security boundary either. Any token with the right scope can act as a viewer or open a session. ADB stays the trust boundary.

## Sessions

The screen and tabs belong to holders. These are the kinds:

| Holder | Starts | Ends |
| --- | --- | --- |
| MCP session | `initialize` on `/mcp` | `DELETE`, or idle expiry |
| Started session | `session_start`, from an MCP session | `session_end`, idle expiry, or the session that started it ending |
| Viewer | Its input socket opening | The socket closing |
| REST session | `POST /v1/sessions` | `DELETE /v1/sessions/current`, or idle expiry |

The screen lease and tab ownership are keyed by holder, so the viewer and every session follow the same queue. A session's holder is its `Mcp-Session-Id`, whether MCP or REST made it.

### MCP sessions

`initialize` assigns an `Mcp-Session-Id`, which is unguessable, so it needs no separate owner field. Every later request carries it.

- A request must carry the token that started the session. A session's tools and scopes are fixed at `initialize`, so another token could otherwise use the first one's scopes. A different token gets the empty 404 an unknown token gets.
- An unknown or expired session id gets 404, as the Streamable HTTP spec says, and the client starts a new session.
- `DELETE` ends the session. That releases the screen, runs cleanup, and closes the session's tabs.
- A session with no requests for 20 minutes expires, and expiry does what `DELETE` does. The timeout is a backstop, not the normal release. It is longer than a client waiting on a person's approval (up to 15 minutes), so PhoneAPI never ends a live session someone is waiting on. Its screen is another matter, as in [Waiting](#waiting). The timeout is the same for every client, and a client cannot ask for another.
- The app keeps sessions in memory, so restarting it ends them all. Clients see 404 and start again. PhoneAPI saves the ids of tabs that sessions opened and closes any that are left when it starts, so a restart does not strand them. It does this once per start, when the helper first connects; a helper that restarts later leaves live sessions' tabs alone. Tabs a session only claimed are not saved, since they were not its to close.
- `/mcp` answers POSTs with JSON and refuses the GET stream with 405, which the spec allows. It sends no progress, since the wait for the screen is capped at 60 seconds and nothing depends on it.

`initialize` returns `instructions`, a short overview for the model: the phone is shared, which calls wait for the screen and for how long, that reading tabs and `cdp` input need no screen, `screen_release`, `session_start` for parallel work, and how to keep calls few. Clients decide whether the model sees them. Claude Code does. Where a client doesn't, the tool descriptions carry the details.

### Sessions for subagents

One MCP client is usually one session, so its subagents share a turn at the screen and a set of tabs, and parallel ones tap over each other. `session_start` gives a subagent or parallel task a session of its own:

- It returns a new session id, under the session that called it and bound to the same token. Every other tool takes an optional `session` argument naming it, and a call with it acts as that session: its own place in the screen queue, its own tabs, its own grace period and notes. Without the argument, a call acts as the caller's own session.
- A session can name only itself or sessions it started. Another session's id, or one that has ended, fails with `not_found` and a message to call `session_start`.
- A session and one it started never wait for each other's screen. A screen call while the other one holds the screen fails at once with `session_conflict`, saying which way round it is: a session from `session_start` called while its starter held the screen, or the starter called while one of its sessions held it. Waiting would queue the caller's own work behind itself, and after 30 seconds the idle-holder rule would hand the screen over with a cleanup the caller never asked for. Two sessions started by the same one still wait for each other, since that is parallel work.
- Tabs are not shared between them. A tab that belongs to another session in the same family still fails with `not_found`, but the message says it belongs to the caller's own session or one from `session_start`, and how to use it. A tab of an unrelated session gets the plain `not_found`, so nothing about other clients leaks.
- `session_end` ends one, as `DELETE` would: cleanup if it held the screen, and the tabs it opened close. It also ends after 20 minutes unused, and when the session that started it ends.
- A started session has no transport, so `/mcp` answers its id in `Mcp-Session-Id` with 404. It counts toward the session caps and shows in `GET /v1/sessions` with `parent`, the hash of the session that started it.

The model carries the id, so it can forget it or pass a stale one. A forgotten id makes the call act as the caller's own session, and a stale one fails loudly. The id sits in the model's context and the client's logs, where the header kept it out of sight, but it is useless with any other token.

### REST sessions

Scripts run sequences such as unlock, launch an app, tap a field, type. Another holder must not take the screen between two of those steps, so REST has sessions too, the same ones MCP has.

- `POST /v1/sessions` creates a session and returns `{"id": "...", "idleMs": 1200000}`. It is bound to the calling token, counts toward the same caps as MCP sessions (16 per token, 32 in all), and fails with 429 `too_many_sessions` past them. Any token can create one; the admin scope is only for listing and ending other sessions.
- Every REST call that needs a session carries its id in `Mcp-Session-Id`, the header MCP uses, so one id works on both. An id another token created, or one that has ended, gets 404 `not_found`, as on `/mcp`.
- `DELETE /v1/sessions/current`, with the header, ends the caller's session the way an MCP `DELETE` does: cleanup if it held the screen, its opened tabs close, and its claimed tabs go back to being unowned. Idle expiry after 20 minutes without a request, revoking the token, and the admin `DELETE /v1/sessions/{hash}` end it too. So does removing a scope from the token once the session has an MCP transport, since its tools were built for the old scopes.
- `POST /v1/screen/release`, with the header, is the REST counterpart of `screen_release`: cleanup, then the next waiter gets the screen, and the session keeps its tabs. It takes the same optional `cleanup`, as in [Handing the screen to a person](#handing-the-screen-to-a-person).

A REST session gets the same rules as an MCP session. It keeps the screen and owns tabs as below. Notes reach it as `X-PhoneAPI-Note` headers.

Every route that acts on the phone, shows the screen, or touches a tab needs a session:

- `POST /v1/device/wake`, `/unlock`, `/lock`, `/orientation`
- `GET /v1/ui/snapshot`, `POST /v1/ui/find`, `POST /v1/ui/nodes/{ref}/action`, `GET /v1/screenshot`
- `POST /v1/input/tap`, `/swipe`, `/gesture`, `/key`, `/text`, the clipboard routes, and `POST /v1/ime/hide` and `/show`
- `POST /v1/apps/{pkg}/launch`, `/stop`, `/clear`, and `POST /v1/intents`
- `POST /v1/wait`
- Everything under `/v1/browser/`: listing tabs, opening, navigating, closing, reading, finding, snapshots, input, `evaluate`, `capture`, and `console`.

One without the header fails with 428 `session_required`: "Create a session with POST /v1/sessions and send its id in Mcp-Session-Id." 428 Precondition Required says the request is fine but a precondition is missing, which a client can tell from a bad request.

These need no session:

- `POST /v1/sessions`, `DELETE /v1/sessions/current` (it names the session in the header), and the admin `GET /v1/sessions` and `DELETE /v1/sessions/{hash}`.
- Token admin, `/v1/tokens`.
- What neither shows the screen nor touches tabs: `GET /v1/device`, `GET /v1/apps`, setting and clearing the stored PIN (`PUT` and `DELETE /v1/device/pin`), `GET /v1/openapi.json`, and the events socket.
- The viewer: `/viewer`, its input socket, which is its own holder, and the video and audio streams, which are the viewer's view of the screen.

### Sharing ids between MCP and REST

- **MCP to REST.** The id `initialize` returns works on REST calls as the same holder: same place in the screen queue, same tabs, same notes. An agent's client can make a REST call mid-turn without losing its screen.
- **REST to MCP.** MCP requires `initialize` before anything else, so PhoneAPI accepts an `initialize` that carries an existing session's id in `Mcp-Session-Id`, from the same token, and attaches the MCP transport to that session instead of making a new one. A standard client never sends one, so it cannot affect them. A session that already has a transport answers a second `initialize` as any MCP session does, with the SDK's 400 that it is already initialized. A session from `session_start` cannot take an `initialize`; its id stays a tool argument.
- **Started sessions.** An id from `session_start` works in the header on REST too, for the token that owns it. On REST there is no parent session in the request, so any of the token's live sessions can be named.
- **Ending.** A session is one session whichever side ends it. A REST `DELETE /v1/sessions/current` on a session with an MCP transport closes the transport, and the MCP client gets 404 on its next request. An MCP `DELETE` ends it for REST callers too.

`phoneapi papi` creates a session when the server first answers 428, and keeps the id next to the pairing file, so a sequence of `papi` calls is one holder, as a script expects. [development.md](development.md#calling-the-api-with-phoneapi-dev-papi) describes it.

## Screen lease

One exclusive lease per phone. The first call that needs the screen takes it. A session keeps it until the session ends, it calls `screen_release`, or it makes no screen call for 30 seconds while another client waits. These calls need it:

- Device and app tools: `tap`, `swipe`, `type_text`, `press_key`, `clipboard_read`, `clipboard_write`, `ui_act`, `keyboard_*`, `app_*`, `open_intent`, `device_wake`, `device_unlock`, `device_lock`, `device_orientation`.
- Observing the screen: `ui_snapshot`, `ui_find`, `screenshot`. These would show another holder's screen.
- `wait_for` with any condition that is not `browser.*`, or with `snapshot` or `screenshot`. A `screen` condition with only `person` is the exception: it reads the lease, not the screen.
- Browser input with `input` `touch`, which brings the tab forward. Pointer input with `input` `cdp` too: Chrome only takes cdp touch on the tab it is showing, so it already needs Chrome in front.
- `browser_read` and `browser_snapshot` with `show`.
- `browser_open` with `incognito`, since Chrome's incognito shortcut brings Chrome forward, and a call that opens a tab while Chrome is not running (`browser_open`, or `browser_read` or `browser_find` with `url`), since starting Chrome does too. It needs the screen only while the tab opens, so a session that did not have the screen gives it back after, with cleanup. One that had it keeps it, as does one with another screen call still running.
- Anything else that brings Chrome or a tab to the front.

Calls that need no lease: `device_info`, `logcat_tail`, and browser tools on the caller's own tabs with `input` `cdp` (keys and text) or `cdp-mouse`, or no input at all. The REST routes for the same operations follow the same lists.

Browser input defaults to `auto`: `touch` when the caller holds the screen, and otherwise `cdp-mouse` for a tap and `cdp` for keys and text. A swipe or gesture with `auto` still needs the screen, since cdp touch only reaches a tab on screen. Without this, a tap that left `input` unset would take a free screen and keep it for the rest of the session. Passing `touch` still takes the screen on purpose. A token without the control scope gets `cdp` for `auto` keys even while it holds the screen. A touch key goes to whatever window has focus, so it could press HOME or answer a system dialog, which is control's job.

### Waiting

A call that needs the screen while another holder has it waits in a FIFO queue for up to 60 seconds. A session has one place in the queue, so calls it makes in parallel while it waits get the screen together. When the time passes, the call fails with `screen_busy` and how long the lease has been held. It never says who holds it, except that a person in the viewer is reported as a person.

Only `notifications/cancelled`, or the session ending, takes a call out of the queue. A client dropping its connection does not, since the spec says not to treat a disconnect as a cancellation. So a waiting call whose client has gone stays queued until its 60 seconds pass or its session ends, and clients have to send `notifications/cancelled` to stop a wait. A session that ends fails its waiting calls with `not_found`.

A session that holds the screen and has made no screen call for 30 seconds, while another client waits, loses the screen to that client. Cleanup runs as for any release, and the session keeps its tabs. Its next screen call waits like anyone else's and carries a note that the screen changed hands, so it checks the screen before acting. With nobody waiting, a session keeps the screen however long it is idle, so a lone client keeps its screen state between turns. There is no limit on how long a session holds the screen while it keeps using it.

That grace period is what lets clients such as Claude Code and Cursor share the phone. They keep one session open for as long as they run, and any request, a `ping` included, keeps a session from expiring. Without the grace period, one screen call from such a client would keep everyone else off the screen until it was idle for 20 minutes or quit.

The cost is that an idle holder can come back to a home screen, and a locked phone if the screen went free meanwhile. That includes an agent waiting on a person to approve something: if another conversation wants the screen meanwhile, the agent resumes on a cleaned-up screen and has to find its place again.

A session can also let the screen go itself with `screen_release`, which does the same as the grace period at once: cleanup, then the next waiter. Idle expiry, ending the session from the admin routes, and **Take over** in the viewer still cover a stuck client.

### Handing the screen to a person

A person helps an agent in one of two ways. An agent whose session stays open, such as Claude Code, keeps it while the person uses **Take over**, and gets the screen back without cleanup. An agent whose session ends with its turn is different: ending a session cleans the screen and closes its tabs, so by the time the person opens the viewer the captcha they were asked to solve would be gone.

So `screen_release` takes `cleanup`, default true. With `cleanup` false, the screen goes as it is, with no cleanup, and the session's tab that Chrome is showing counts as claimed rather than opened, so when the session ends it goes back to being unowned instead of closing. Its other tabs close as usual, and if Chrome is not in front showing one of its tabs, all of them do; the result says which. The person finds the page on screen, and the next turn finds the tab in `browser_targets` and claims it by using it. The next holder's first result says the last holder let the screen go without cleanup. An agent that did its browser work in the background brings the tab on screen first, for example with `browser_snapshot` and `show`, and unlocks the phone, since `show` wakes it but leaves the keyguard up.

An agent that waits for the person uses `wait_for` with `{type: screen, person: true}` to see them take the screen, then `person: false` to see them let go. Neither takes the screen, so the wait is not cancelled by the person's take and does not hold anyone off. These read the state now, so `person: true` does not match a person who already finished, such as before an agent's next turn. To know the person is done, the agent waits for the page after the captcha with a `browser.*` condition, and the descriptions say so. A `wait_for` lasts at most 300 s, so a longer wait takes several calls.

A client already waiting for the screen gets it as soon as the agent lets it go with `cleanup` false, before the person takes it. It gets the note that the screen was left as it was, but nothing holds the screen for the person.

### Cleanup

When a session releases the screen or expires, PhoneAPI runs cleanup before granting the next waiter. A viewer letting go, and `screen_release` with `cleanup` false, run none, as in [Viewer](#viewer).

1. Hide the keyboard.
2. Show the [placeholder tab](#background-tabs-stay-in-the-background), then HOME, so Chrome is in the background and shows a blank page the next time it comes forward.
3. Lock, but only when no one is waiting and the phone was locked when sessions started using it. Locking for a waiter would only make it unlock again with the same stored PIN, since every client that can take the screen can unlock it, and it costs the waiter a PIN entry and often a call. A phone its owner or a person in the viewer left unlocked is left to Android's own timeout. The lease remembers whether the phone was locked when a session got it, and a session that gets it unlocked from a hand-off inherits that, so the lock still runs once the screen goes free. If a waiter gives up during cleanup and the screen goes free unlocked, cleanup runs again to lock it.
4. Clear the clipboard, if its clip is one PhoneAPI wrote or copied (`clipboard_write`, `clipboard_read` with `copy`, or the viewer's paste and copy) and nothing has replaced it since. PhoneAPI notes the timestamp Android gives each clip it puts there, and clears the clipboard only if the clip still has that timestamp, so a clip the phone's owner or an app copied later is left alone.

Cleanup cannot clear what a person copies after it runs, on the phone or in the viewer, and that clip can be a password. So a session that gets the screen empties the clipboard before its first screen call does anything else, whoever copied the clip. That covers a free screen, one handed on by another holder, and one handed back after a person took over. A viewer that gets the screen clears nothing, since the person is the phone's operator. Other screen calls by the same session wait for the clear. If it fails, as when the helper is down, the call goes on, the session gets a note, and the next screen call tries again. An empty clipboard is left alone. The phone's owner loses a clip they left on the clipboard while a session used the phone, the same as they find the phone on its home screen. Agents are trusted not to misuse what they see, but they make mistakes, and a password they never read cannot end up in the wrong field or a message.

The next holder's first result that needs the screen starts with a note saying that cleanup ran and whether it locked, or which step failed, so a home screen, locked or not, is no surprise and it checks the state before it acts.

Notes waiting for a session appear on its next call of any kind, not only one that needs the screen, so a session that lost the screen while idle hears about it on its next read or `screen_release`. A screen call that waited more than 2 seconds for another holder says so in a note, so a slow call is not mistaken for a slow phone.

Chrome stays in the background after cleanup. When it comes forward for the new holder, it shows one of the holder's tabs or the placeholder, never another holder's page.

## Viewer

Watching needs no lease. The video and audio sockets show whatever is on screen, because the person at the viewer is the phone's operator.

Input does. The viewer sends touches, keys, text, orientation, copy, and paste on its input socket, `WS /v1/input/pointer`, and that socket is its holder. The page opens the socket on load and reconnects when it drops, after 500 ms and then backing off to every 10 s while the server is away, whether the page is visible or not, so an open socket does not hold the screen. Input does:

- The viewer's first input takes the screen at once if no one holds it. That input is delivered.
- If someone else holds it, the input is dropped, not queued, and the socket tells the page. The page shows that the phone is in use, by an agent, a script, or another viewer, and for how long, with a **Take over** button.
- **Done** lets the screen go at once, as 30 s without input or closing the page does. The page shows it whenever the viewer holds the screen.
- **Take over** takes the screen at once, and turns it on if it was off, leaving the keyguard up. The holder's call in progress is canceled, and so is a cleanup in progress, so it neither locks the phone nor hands the screen on under the person. Until the screen comes back, the holder's screen calls fail at once with `screen_taken`, rather than waiting 60 seconds, so the model stops and says so instead of retrying. No cleanup runs, so the person sees what the agent left, such as a half-filled form or a captcha.
- If the viewer took the screen over and that holder is still alive, the screen goes straight back to it when the viewer lets go, without cleanup, since the person may have fixed something for it. Its first result that needs the screen says a person used the screen and for how long.
- Otherwise the screen goes to the next waiter as the person left it, also without cleanup, and that waiter's first result says so. A person helping an agent whose session ended, as after `cleanup` false, leaves their result for the agent's next turn. A person using the phone for themselves, such as to change a setting, leaves it unlocked, and Android's own timeout locks it once no viewer page is showing it.
- The socket sends lease changes to the page, so it can show who has the screen without polling.

Agents never take the screen over. They queue behind a viewer like anyone else.

## Tabs

A tab belongs to the holder that opened it, or that claimed it. A tab Chrome opens for an `open_intent` or `app_launch` belongs to the session that made that call too: PhoneAPI notes Chrome's tabs before the call and claims the first new ones to appear in the next 2 seconds, since Chrome handles the intent's URL after the activity starts. A session that ends meanwhile claims none.

A tab id that no longer works says why: the tab closed, as a session's tabs do when it ends, or it belongs to another session.

- `browser_targets` lists the caller's tabs and unowned ones, marked as unowned. Other holders' tabs are left out.
- `browser_navigate`, `browser_close`, `browser_read`, `browser_find`, `browser_snapshot`, `browser_eval`, `browser_console`, `browser_capture`, browser input, and `browser.*` wait conditions refuse a tab another holder owns, the same way they refuse a tab that does not exist. `browser.newTarget` only matches tabs the caller owns.
- A tab that a page opens, through `window.open` or a `target=_blank` link, belongs to the owner of the tab that opened it. PhoneAPI reads the opener from Chrome's `Target` domain.
- Tabs that existed before PhoneAPI started and tabs left by a session that died belong to no holder. On a phone an agent uses every day they are mostly its own pages from earlier conversations, such as a signed-in shopping tab, so they stay usable. A session claims an unowned tab by using it, and the first session to do so gets it. When that session ends, the tab goes back to being unowned instead of closing.
- WebViews belong to the app that shows them. The screen holder can use the WebViews of the app in front, which it can already see with `ui_snapshot`. Other sessions cannot.
- Each session can have 4 tabs open, and all sessions 8, not counting the placeholder, unowned tabs, or tabs a session claimed. Opening past either fails with `tab_limit` and a message to close one. Under memory pressure Chrome unloads hidden tabs, as in [Tabs that stop answering](#tabs-that-stop-answering). The caps keep that rare, and a few tabs at a time is what a turn needs.
- The tabs a session opened close when it ends. Tabs it claimed go back to being unowned.
- A tab's [recorded responses](api.md#recorded-responses) stay with its owner. Recording stops when a session claims a tab and when it gives one back, so a tab never changes hands with someone else's responses in it.

Whoever holds the screen can open Chrome's tab switcher and see other holders' tab titles. Given that the accounts are shared anyway, that is accepted.

### Background tabs stay in the background

Android 10 and later block an app in the background from starting activities, unless it was on screen in the last few seconds, and Chrome does not hand a navigation in a hidden tab to another app. Neither protects a tab Chrome is showing, and Chrome switches to a new tab while it is in front, whatever DevTools asks. So:

- PhoneAPI keeps one `about:blank` placeholder tab that no holder owns, and opens it again if it is closed.
- Whenever Chrome is in front, it shows the screen holder's own tab or the placeholder. When Chrome selects another holder's tab, such as one just opened or a popup, PhoneAPI selects the holder's tab or the placeholder again with `Target.activateTarget`, between the holder's calls, never during one. The holder's tab is the one it last opened or brought forward with touch input or `show`. If Chrome will not switch, PhoneAPI closes the new tab and fails the call with `took_screen`.
- A popup from a tab that is not on screen becomes the tab Chrome shows while Chrome is in front. After each browser input, `browser_eval`, and `browser_navigate` from a session without the screen, PhoneAPI gives new popups to the opener's owner and puts Chrome back as for a tab that session opened. A popup a page opens later, on a timer, is caught before the holder's next screen call.
- Selecting a tab can bring Chrome forward on some builds, so PhoneAPI never selects one for a session without the screen.
- Chrome stays in the background when nobody holds the screen. Cleanup shows the placeholder before HOME.
- While Chrome is in the background, PhoneAPI leaves its selected tab alone, since selecting one can bring Chrome forward. It fixes the selection before each of the holder's screen calls while Chrome is in front, and after one that brought Chrome forward, such as `app_launch` of Chrome, before that call returns. It checks after selecting, since Chrome can change its selection as it comes forward. While only one session owns tabs, none of this runs.
- `tookScreen` is always false for a caller without the screen.

### Tabs that stop answering

Three things stop a hidden tab:

- **Chrome throttles and then pauses it.** A tab behind another one runs its timers less and less often, then stops them, and some minutes later pauses the page. A paused tab still answers DevTools, so reads, script evaluation, mouse input, and navigation work, but the page's own JavaScript does not run, so content it would load or poll for never arrives. On the phone, timers stop about a minute after the tab is hidden.
- **Chrome unloads it under memory pressure.** The tab stays in the list, but its renderer is gone, so script never answers until it reloads.
- **Android freezes all of Chrome.** On Android 12 and later, Android freezes apps in the background that nothing depends on, and then nothing in Chrome answers. See [Android's app freezer](#androids-app-freezer).

So PhoneAPI:

- Asks Chrome whether the tab has a renderer before a call that needs it, rather than waiting out the 15-second DevTools timeout.
- When it has none, it was unloaded. PhoneAPI reloads it in the background and says so in the result, since anything typed or scrolled is gone, rather than acting on the fresh page as if nothing happened. Input calls fail instead with `tab_reloaded`, so a session does not type into a page it has not looked at again.
- When the browser does not answer either, Android has frozen Chrome, and the call fails with `browser_frozen`.
- Refuses input into a paused tab, `cdp` and `cdp-mouse` taps, swipes, gestures, keys, and text, with `tab_frozen`. Input still reaches a paused page, but its scripts do not run, so a click or keystroke seems to do nothing, and an agent loses more time working out why than bringing the tab forward or reloading it would cost. The error says how to do either. Touch input brings the tab on screen, which wakes it, so it is let through. Reads and `eval` still work on a paused tab.
- Checks timers too, since a page that reacts through them, such as a debounced search box, does nothing once they stop. The check runs in the background alongside each `cdp` or `cdp-mouse` input, and the next call on that tab reads the result. The next input is refused with `tab_frozen`, saying the last input may not have taken effect, after a fresh check confirms the timers are still stopped. A `wait_for` on the tab fails at once, or as soon as the check comes due, rather than running out its timeout; a wait starts its own check when no input has. A read notes `timersStopped`.
- Bringing the tab forward, reloading it, or navigating clears the check.

Both checks run in an isolated world, so the page can neither see them nor fool them. [device-notes.md](device-notes.md#chrome-tabs-in-the-background) has the timings and how the checks work.

### Android's app freezer

Android only freezes an app it counts as cached, one that nothing in front of the user depends on. While the shell helper is running, PhoneAPI's foreground service binds Chrome's Custom Tabs service (`android.support.customtabs.action.CustomTabsService`), which any app may bind, so Chrome counts as bound by a foreground service, not cached. It holds the binding whether or not any client is connected, and drops it when the helper stops. The manifest declares the Custom Tabs action under `<queries>`, which Android 11 and later require before an app can see another app's service.

This needs no system setting and touches only Chrome. Chrome still pauses hidden tabs itself, as above. Turning the freezer off for the whole phone, with `settings put global cached_apps_freezer disabled` or the "Suspend execution for cached apps" developer option, stays a manual fallback for a device where the binding falls short. `browser_frozen` says what happened, and names the developer option.

`GET /v1/device` reports whether the freezer is on as `appFreezer`, read when the helper starts, and whether PhoneAPI holds Chrome's binding as `chromeKept`.

### Content that loads when drawn

A hidden tab is not drawn. Images that load lazily do not load, which saves work, since PhoneAPI reads text and only a screen capture shows pixels. A capture needs the screen, and the images load once the tab comes forward. But some pages also build their text only when drawn or scrolled into view: infinite scroll, "load more" sections, skeleton placeholders. In a hidden tab that text never appears, and a read looks complete.

So reads say when that may have happened:

- `browser_read` reports `shown`: whether Chrome is drawing the tab, meaning Chrome is in front with this tab selected. `document.visibilityState` alone is not enough, since a tab opened while Chrome was in the background reports `visible` while Chrome is still hidden.
- `page-pending.js` reports `pending`, next to `blocked` and `sparse`: signs the page is waiting to load content, which are a laid-out `aria-busy` region, empty skeleton, shimmer, spinner, or loading boxes, and a "Load more" or "Show more" control.
- When the tab is not shown, the result says so. When the page is also `sparse` or `pending`, it says content may not have loaded while the tab was hidden, and that reading with `show` may load it. `browser_snapshot` says the same in lines after its header.
- `browser_read` and `browser_snapshot` take `show`. It brings the tab forward, launching Chrome if it is behind, and waits until the page's text holds still for 750 ms, at most 5 seconds, then reads. It takes the screen, waiting like any screen call. It only draws what is on screen, so content further down may still need scrolling.

### When something comes forward anyway

If a call from a session without the screen still brings an app or Chrome forward, a workflow is relying on something it should not, and PhoneAPI makes that loud.

- PhoneAPI watches the top task, not what is visible: between holders the phone is often locked, and an app launched then waits behind the lock screen for the next holder.
- A change of top task during a call by a session without the screen, or within 2 seconds after, while the holder has no call in progress, is that session's doing. A change during the holder's call is the holder's. A change with no call in progress anywhere, such as an incoming call or an alarm, belongs to no session and is reported in the holder's next result that needs the screen.
- The session's call fails with `took_screen`, naming what came forward, even if the action itself worked. PhoneAPI also sends a `screen.taken` event on `/v1/events`, logs a warning, and counts it per session in `GET /v1/sessions`.
- If nobody held the screen, the session now holds it. It caused what is showing, so it is the one that can look and clean up, such as stopping navigation that Maps started. Cleanup runs when it releases.
- If another holder had the screen, PhoneAPI brings the holder's app back to the front, and the holder's next result that needs the screen says something came forward briefly. Whatever the launched app did is not undone.
- If two sessions without the screen had calls in progress, both fail with `took_screen` and neither gets the screen, and the app that was in front comes back.
- Chrome switching to another holder's tab, as a popup makes it do, does not change the foreground app, so it is handled as in [Background tabs stay in the background](#background-tabs-stay-in-the-background).

PhoneAPI checks the top task before and after `browser_open`, `browser_navigate`, `browser_eval`, browser input, and `browser_read` or `browser_find` with `url` from a session without the screen, the calls through which a page could launch something. Reads of an open tab cannot. It checks again 2 seconds after the call. A change found then cannot fail the call, so it reaches the session as a note on its next screen call, along with the event, log line, and count. Nothing is checked while a person in the viewer holds the screen, since the viewer's input makes no screen calls.

Android and Chrome blocked every such launch from a tab not on screen in testing, so `took_screen` is a tripwire for workflows to fix, not something agents handle in normal use.

### Incognito tabs

`browser_open` and `POST /v1/browser/tabs` take `incognito`, for a page that needs no account. The tab has none of the phone's logins, cookies, or history, so a mistaken click cannot post, buy, or subscribe as the phone's owner, and the site cannot tie the visit to the account.

Chrome has one incognito profile, shared by every incognito tab and kept until the last one closes. So before an incognito open, when no live session owns an incognito tab, PhoneAPI closes them all: a person's, one kept for a person to finish, or any other left open. The new tab then starts with no cookies or site data. A person's incognito tabs close without warning, and Chrome cannot bring them back. When another session owns an incognito tab, nothing can separate the two, so the tab shares that session's cookies, a site can tie their visits together, and the call gets a note saying so. Opens run one at a time, from listing the incognito tabs to the new tab getting its owner, so one session's open cannot close another's new tab.

DevTools cannot open an incognito tab on Android, so the helper starts Chrome's "New Incognito tab" shortcut, the intent action `org.chromium.chrome.browser.incognito.OPEN_PRIVATE_TAB`, which brings Chrome forward on a new incognito tab. That is why the call takes the screen. The tab needs no screen after it opens, so a session that did not have the screen gives it back, and cleanup sends Chrome home with the tab still open. PhoneAPI then looks for the new tab in Chrome's target list and checks that its browser context differs from a normal tab's. It learns the normal context once per Chrome process by opening a blank tab with `createTarget` and closing it. A new tab in the normal context, or none within 5 s, fails with `browser_unavailable` instead of handing back a tab with the owner's logins. Then the tab loads the page with `Page.navigate`, and it belongs to the session like any tab it opens.

Chrome marks incognito windows secure, so `screenshot`, the viewer, and the video show them black, with only the status bar drawn. A person who takes over in the viewer can tap but cannot see the page, so an incognito tab is a poor one to leave for a person to finish. The page itself still answers DevTools: `browser_read`, `browser_snapshot`, `browser_eval`, and `cdp-mouse` taps work.

For a page a person has to finish, such as a captcha, the phone's owner can let Chrome draw incognito tabs normally: open `chrome://flags/#incognito-screenshot` in Chrome, set **Incognito Screenshot** to Enabled, and tap **Relaunch**. Then `screenshot`, the viewer, and the video show incognito tabs like any other. The flag is the owner's choice, and PhoneAPI does not set it. It also lets any app that captures the screen, and Android's recent apps view, see incognito tabs.

## Security

The spec's main warning about sessions is not to treat a session id as authentication. PhoneAPI doesn't: every request still needs the bearer token, and the token that started the session. `/mcp`, including `GET` and `DELETE`, takes only the bearer header, never the `phoneapi` cookie or `access_token`, so a page in a browser cannot use a session even if it learns the id.

A client that runs every conversation on one token, though, gets no separation from the token check. A session id is the only thing that keeps one conversation out of another's session, and these keep it, and the phone, from being misused:

- **Session ids stay out of the log.** `logcat_tail` lets any session with `observe` read the log, so a full id there would let one conversation use or close another's tabs, or end its session. PhoneAPI logs a short hash of an id, never the id.
- **Sessions are capped.** Each session holds memory on the phone until it ends or expires. A token can have 16 sessions open and the phone 32 in all. Past either, `initialize` and `POST /v1/sessions` fail with `too_many_sessions`. The SDK's event store, which keeps past messages for resuming a stream, stays off.
- **A stuck session can be ended.** Any request resets the idle timer, so a client that keeps calling holds the screen as long as it likes, and only **Take over** in the viewer breaks it. `GET /v1/sessions` (admin) lists sessions by hash, with their age, idle time, tab count, and whether they hold the screen. `DELETE /v1/sessions/{hash}` (admin) ends one as its own `DELETE` would.
- **Revoking a token ends its sessions.** Requests with a revoked token already fail, but its sessions would otherwise keep the screen, their tabs, and any queued call until they expired. Revocation ends them, with cleanup.
- **WebSocket upgrades check `Origin`.** The `phoneapi` cookie is `SameSite=Strict`, but a page on `127.0.0.1` at any port is the same site, so another local web server's page in the same browser could otherwise open the viewer's socket with it, inject touches, and take the screen over. An upgrade is refused unless `Origin` is the API's own `http://127.0.0.1:<port>`.

Leaks between holders are limited by the rules above: `screen_busy` says how long the screen has been held but not by whom, and tab listings show only the caller's tabs.

## Errors

| Status | `error` | Cause |
| --- | --- | --- |
| 409 | `screen_busy` | Another holder had the screen for the whole wait. The message says how long it has held it |
| 409 | `screen_taken` | A person in the viewer took the screen from this session |
| 409 | `took_screen` | A call from a session without the screen brought an app or Chrome forward. The message names it |
| 409 | `tab_limit` | The session has 4 tabs open, or all sessions 8 |
| 409 | `tab_reloaded` | Chrome had unloaded the tab, so PhoneAPI reloaded it instead of sending input to a page the session has not seen |
| 409 | `tab_frozen` | Chrome has paused the hidden tab, or stopped its timers, so it would not respond to input. Bring it forward or reload it |
| 409 | `session_conflict` | A screen call while the session that started the caller, or one the caller started, holds the screen |
| 503 | `browser_frozen` | Android has frozen Chrome in the background, so its tabs cannot answer. The message names the developer option that turns the freezer off |
| 428 | `session_required` | A REST call that needs a session came without `Mcp-Session-Id` |
| 429 | `too_many_sessions` | The token has 16 sessions open, or the phone has 32 |

On MCP these are tool errors, like other `ApiException`s.
