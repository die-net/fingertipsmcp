package com.fingertipsmcp.server.routes

import com.fingertipsmcp.browser.requireTouchAccess
import com.fingertipsmcp.browser.touchAllowed
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.BrowserCaptureRequest
import com.fingertipsmcp.model.BrowserFindRequest
import com.fingertipsmcp.model.BrowserGestureRequest
import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserInputKind
import com.fingertipsmcp.model.BrowserKeyRequest
import com.fingertipsmcp.model.BrowserReadRequest
import com.fingertipsmcp.model.BrowserSnapshotOptions
import com.fingertipsmcp.model.BrowserSwipeRequest
import com.fingertipsmcp.model.BrowserTapRequest
import com.fingertipsmcp.model.BrowserTextRequest
import com.fingertipsmcp.model.ConsoleRequest
import com.fingertipsmcp.model.EvalRequest
import com.fingertipsmcp.model.NavigateRequest
import com.fingertipsmcp.model.OpenTabRequest
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.browser
import com.fingertipsmcp.server.scoped
import com.fingertipsmcp.server.session
import com.fingertipsmcp.server.token
import com.fingertipsmcp.server.withScreen
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Browser routes: targets, navigation, snapshot, reading, input, evaluate, and console. */
fun Route.browserRoutes(services: ServerServices) {
    scoped(Scope.BROWSER) {
        get("/v1/browser/targets") {
            call.respond(withScreen(call, services, false) { call.browser(services, it).targets() })
        }
        post("/v1/browser/tabs") {
            val request = call.receive<OpenTabRequest>()
            call.respond(
                // Chrome's incognito shortcut and starting Chrome bring it forward, so only those
                // take the screen, and only while the tab opens. The tab works in the background
                // after.
                withScreen(
                    call,
                    services,
                    needed = request.incognito || services.browser.chromeStopped(),
                    launching = true,
                    giveBack = true,
                ) {
                    call
                        .browser(services, it)
                        .openTab(request.url, request.capture, request.incognito)
                }
            )
        }
        post("/v1/browser/targets/{id}/navigate") {
            val url = call.receive<NavigateRequest>().url
            call.respond(
                withScreen(call, services, needed = false, launching = true) {
                    call.browser(services, it).navigate(call.targetId(), url)
                }
            )
        }
        post("/v1/browser/targets/{id}/close") {
            call.respond(
                withScreen(call, services, false) {
                    call.browser(services, it).closeTab(call.targetId())
                }
            )
        }
        browserReadRoutes(services)
        browserInputRoutes(services)
    }
}

/**
 * Snapshots, reading, finding, script, recording responses, and console logs. Only `show` takes the
 * screen.
 */
private fun Route.browserReadRoutes(services: ServerServices) {
    get("/v1/browser/targets/{id}/snapshot") {
        val q = call.request.queryParameters
        val options =
            BrowserSnapshotOptions(
                maxNodes = q.int("maxNodes") ?: BrowserSnapshotOptions().maxNodes,
                maxChars = q.int("maxChars"),
                show = q.flag("show", default = false),
            )
        call.respond(
            withScreen(call, services, options.show) {
                call.browser(services, it).snapshot(call.targetId(), options)
            }
        )
    }
    post("/v1/browser/targets/{id}/read") {
        val request = call.receive<BrowserReadRequest>()
        call.respond(
            withScreen(call, services, request.show) {
                call.browser(services, it).read(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/find") {
        val request = call.receive<BrowserFindRequest>()
        call.respond(
            withScreen(call, services, false) {
                call.browser(services, it).find(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/evaluate") {
        val request = call.receive<EvalRequest>()
        call.respond(
            withScreen(call, services, needed = false, launching = true) {
                call.browser(services, it).evaluate(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/capture") {
        val request = call.receive<BrowserCaptureRequest>()
        call.respond(
            withScreen(call, services, false) {
                call.browser(services, it).capture(call.targetId(), request.on)
            }
        )
    }
    post("/v1/browser/targets/{id}/console") {
        val request = call.receive<ConsoleRequest>()
        call.respond(
            withScreen(call, services, false) {
                call.browser(services, it).console(call.targetId(), request)
            }
        )
    }
}

/**
 * `auto` input as it resolves for the calling session, which holds the screen or not. A token that
 * may not send [kind] as touch resolves as if it did not hold the screen.
 */
private fun ApplicationCall.resolved(
    services: ServerServices,
    input: BrowserInput,
    kind: BrowserInputKind,
): BrowserInput =
    input.resolved(
        kind,
        services.lease.holds(session()) && touchAllowed(kind, token.scopes),
    )

/** Pointer, key, and text input. Touch, and cdp pointer input, take the screen. */
private fun Route.browserInputRoutes(services: ServerServices) {
    post("/v1/browser/targets/{id}/tap") {
        val asked = call.receive<BrowserTapRequest>()
        val request = asked.copy(input = call.resolved(services, asked.input, BrowserInputKind.TAP))
        val screen = request.input != BrowserInput.CDP_MOUSE
        call.respond(
            withScreen(call, services, screen, launching = true) {
                call.browser(services, it).tap(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/swipe") {
        val asked = call.receive<BrowserSwipeRequest>()
        val input = call.resolved(services, asked.input, BrowserInputKind.POINTER)
        val request = asked.copy(input = input)
        call.respond(
            withScreen(call, services, input != BrowserInput.CDP_MOUSE, launching = true) {
                call.browser(services, it).swipe(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/gesture") {
        val asked = call.receive<BrowserGestureRequest>()
        val input = call.resolved(services, asked.input, BrowserInputKind.POINTER)
        val request = asked.copy(input = input)
        call.respond(
            withScreen(call, services, input != BrowserInput.CDP_MOUSE, launching = true) {
                call.browser(services, it).gesture(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/key") {
        val asked = call.receive<BrowserKeyRequest>()
        val input = call.resolved(services, asked.input, BrowserInputKind.KEY)
        requireTouchAccess(BrowserInputKind.KEY, input, call.token.scopes)
        val request = asked.copy(input = input)
        call.respond(
            withScreen(call, services, input == BrowserInput.TOUCH, launching = true) {
                call.browser(services, it).key(call.targetId(), request)
            }
        )
    }
    post("/v1/browser/targets/{id}/text") {
        val asked = call.receive<BrowserTextRequest>()
        val input = call.resolved(services, asked.input, BrowserInputKind.TEXT)
        val request = asked.copy(input = input)
        call.respond(
            withScreen(call, services, input == BrowserInput.TOUCH, launching = true) {
                call.browser(services, it).text(call.targetId(), request)
            }
        )
    }
}

private fun ApplicationCall.targetId(): String =
    parameters["id"]?.takeIf { it.isNotBlank() }
        ?: throw ApiException.badRequest("A target id is required")
