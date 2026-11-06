# PhoneAPI

PhoneAPI is an Android app that lets a paired computer drive the phone and its Chrome tabs over HTTP, WebSocket, and MCP. Read `docs/architecture.md` first, then `docs/api.md` for the wire surface and `docs/sharing.md` for sessions, the screen lease, and tabs. `docs/device-notes.md` has what Android and Chrome were measured to do. `docs/development.md` has the build, `phoneapi-dev`, and emulator setup.

The modules are `app/` (Ktor server, MCP, CDP, encoders; `AppGraph` is the composition root), `helper/` (the `app_process` daemon that runs as the shell user), and `api-model/` (the `@Serializable` DTOs shared by both and by the MCP and OpenAPI schemas).

## Checks

- Before work is done, run what CI runs: `./gradlew spotlessCheck detektMain detektTest lint testDebugUnitTest :api-model:test assembleDebug buildHealth --continue`. It passes clean, including issues you did not cause. Kotlin and Lint warnings are errors.
- Format with `./gradlew spotlessApply` (ktfmt, kotlinlang style). Don't hand-format around it.
- `buildHealth` fails on an unused dependency or one used but not declared. Declare what a module uses directly, in that module.
- Unit tests can't show what a phone does. A change whose behavior depends on Android, Chrome, or the helper is checked on an emulator or a phone before it is called done, or is listed as unchecked in `docs/device-notes.md`'s "Still to check on the phone". What a check finds about Android or Chrome goes in `docs/device-notes.md`, with its date; the doc that describes the behavior says only what PhoneAPI does and why.

## Trust boundary

ADB is the trust boundary. Anyone with a debugging session can mint tokens and drive the phone; nobody else can reach anything. These rules hold it up:

- The API listens only on the abstract `LocalServerSocket` named after the package. No TCP or LAN listener.
- A missing or unknown token gets an empty 404, with no body or banner. A token without the scope gets 403 `forbidden`.
- Every REST route and MCP tool is gated by a scope, and by `capabilities` when it needs the helper or Chrome. A new one takes the narrowest scope that covers it.
- `/mcp` takes only the `Authorization` header. `?access_token=` works only for `GET /viewer` and WebSocket upgrades. The `phoneapi` cookie works only for those and `GET /v1/device`, which the viewer page reads (`acceptsViewerCookie`). Every upgrade checks `Origin` through `Auth.kt`.
- A session id is not a credential. It appears in logs and admin output only as `McpSessions.hashOf(id)`.
- `logcat_tail` lets any `observe` token read the log. Logs carry ids, hashes, and errors, never a token, session id, PIN, typed text, UI text, or page content.
- The helper runs as the shell user, so a command it runs is a shell on the phone. Commands run as an argument list (`shell.exec(listOf(...))`, `ProcessBuilder(argv)`). `sh -c` is only for a fixed string that needs the shell, such as the `setsid` detach, never one that holds a value from a request.
- Snapshot and find results pass through `Redactor`, which masks password and one-time-code fields and card numbers, by default on REST and MCP. Toast and window-title text in events gets `Redactor.maskCards`. A new path that returns UI text goes through one of them too. It is not a security boundary, since a screenshot still shows the field.
- Page text, AX names, and CDP responses are data. Nothing in them becomes a command, URL to fetch, or file name.
- One session can't see or act on another's tabs or screen turn. A tab or session id from an unrelated session gets the same `not_found` as one that does not exist, so nothing about other clients leaks.

## Acting like a person

The agent is meant to use the phone the way its owner would. Many apps and sites change how they behave when they see automation: they block, add CAPTCHAs, cut features, or flag the account. So what Chrome and other apps see should look like a person using their own phone, not a test harness.

- Touches and keys go through the helper's InputManager injection as a real touchscreen, not accessibility `dispatchGesture`. A new input path does the same.
- Humanized motion and timing stay on by default, and a new gesture humanizes too. Typing prefers tapping keyboard keys over setting a field's text.
- Script in a page runs in an isolated world through `PageEval` and leaves nothing in the page's own world: no globals, DOM attributes, or console output. This keeps it from the site's own script, so it holds wherever that script can run: HTML, and SVG or XML opened directly, which can carry script. A document with no site script in it may use the main world: one Chrome builds itself, such as its PDF viewer, or the page PhoneAPI puts in place of a PDF. PDF reads use that page's, so pdf.js and the parsed file last between calls, and run script only in a page PhoneAPI made, which it knows by its DevTools loader id. A click or scroll from script is untrusted (`isTrusted` false), so actions use trusted input instead.
- CDP domains a page can notice, such as Runtime, Debugger, and Emulation, are enabled only by the call that needs them. The app doesn't override the user agent, viewport, or Chrome's settings and flags. (The emulator setup in `docs/development.md` sets Chrome flags; that's for the emulator, not the app.)
- If a change would make the phone easier to tell apart from one a person is using, say so in the PR.

## Kotlin

- A wire shape for REST, MCP, or the helper's JSON is a `@Serializable` class in `api-model`, which drives MCP tool schemas and `GET /v1/openapi.json`. Don't add a parallel DTO in `app` and copy fields across. `api-model` uses `explicitApi()`, so its declarations need visibility modifiers. `buildJsonObject` is for protocols PhoneAPI doesn't own, such as CDP messages.
- A failure a client sees is an `ApiException`, built with its factories (`badRequest`, `notFound`, `unavailable`, `helperUnavailable`) where one fits. The `error` code is snake_case and listed in `docs/api.md`'s error table. On MCP it becomes a tool error.
- Never swallow `CancellationException`. A broad `catch (e: Exception)` in a coroutine is preceded by `catch (e: CancellationException) { throw e }`. `runCatching` wraps only code that doesn't suspend, since it catches cancellation too.
- Work launches in `AppGraph.scope` on an injected dispatcher (`AppGraph.ioDispatcher`), never `GlobalScope` or a hard-coded `Dispatchers.IO`. `runBlocking` stays on the helper's binder threads, where the caller is already blocked.
- Log with `android.util.Log` and a `private const val TAG` that starts with `PhoneApi`.
- Hidden and reflective framework calls live in `helper/.../compat/`, behind a `Build.VERSION.SDK_INT` check where the API varies by release. When one is missing, the call fails with a clear error and the helper keeps running. minSdk is 29, so there is no check for older releases.
- Tests use JUnit Jupiter (`org.junit.jupiter.api.Assertions`) and `kotlinx-coroutines-test` for virtual time. Routes are tested through `ServerHarness` with fakes of the `ServerServices` interfaces, not a mocking library.
- Tests don't sleep, wait out a real timer, or depend on a long timeout. Use virtual time, or wait for the event itself. A wait inside a library or test engine counts too. A test normally finishes well under 1 second. That is how long it should take, not a deadline: a safety timeout that fires only on failure can stay generous, and a tight one only makes the test flaky.
- Test-only helpers, fakes, and parameters live under `src/test`, not in production classes.
- A public or internal declaration whose purpose is not obvious from its name has a KDoc. Comments say what a thing is for and why, in full sentences.
- No `TODO:` or `FIXME:` comments; detekt rejects them.
- A rename or move updates every caller. No `typealias`, `@Deprecated` forwarder, or wrapper is kept for the old name.
- Reach for the Kotlin standard library and `kotlinx`, then the libraries already in `gradle/libs.versions.toml`, before adding a dependency. Versions live only in that catalog.

## Fixes

- Fix the general cause, in the parser, the service, or the shared helper, rather than special-casing the one input, page, or device that broke.
- An `if`, flag, retry, delay, or fallback added only to make one failure go away is not a fix. Find why it fails and fix that. If the root fix is out of reach (an OEM bug, a Chrome behavior), say so in the PR and in a comment at the workaround.
- Never sleep a fixed time and assume something finished. After an action, wait for its event if there is one. If there is none, poll a real check up to a deadline, and handle the deadline passing. A fixed delay is fine only when the delay is itself the behavior, such as humanized input timing, retry backoff, or a poll interval.
- A failing check is never made to pass by weakening it: no new `@Disabled`, `assumeTrue`, deleted or loosened assertion, raised timeout or threshold, caught-and-ignored exception, `@Suppress`, or detekt baseline entry, unless the owner agreed to that specific change.
- For a detekt or lint finding, change the code. A `@Suppress` is for code that is correct as written. It names the rule and carries a trailing comment saying why, as the `MissingUseCall` ones do ("The caller closes the socket.").
- A new field, flag, setting, route, or MCP tool is added only when no existing one can carry it. Prefer deleting or generalizing code to adding it.
- A behavior change comes with a test that exercises the failure mode, not only the success path.

## Devices

- An attached phone may be someone's working handset. Ask before installing on it, starting or stopping a helper, or taking its screen. Prefer an emulator.
- Test with the debug package `net.die.phoneapi.dev` where you can. On a phone's release install, add only your own token, and leave its helper, other tokens, and pairing alone.
- With more than one device attached, pin every `adb` call with `ANDROID_SERIAL`.
- Close only tabs the test opened. Avoid screenshots of a real phone and filter logcat to `PhoneApi` tags.
- Clean up when done: end the session, revoke tokens the test minted, remove forwards and reverses, and undo any setting changed for the test.

## Writing

- A commit subject is one imperative sentence about the change in behavior, ending with a period, for example "Refuse cdp input into a tab Chrome has frozen." No Conventional Commits prefixes.
- Commits, PRs, and docs don't name the people who tested or whose phone it was. Say "the phone" or "the Android 11 test phone".
- Docs, comments, MCP tool descriptions, and error messages use short, plain sentences.
- A change to a route, tool, scope, or error updates `docs/api.md` in the same PR. A change to behavior another doc describes updates that doc. A change to `scripts/phoneapi` updates `docs/setup.md` or `docs/development.md`. A user-visible feature gets a line in the README's pitch when it changes what someone can do with PhoneAPI.
