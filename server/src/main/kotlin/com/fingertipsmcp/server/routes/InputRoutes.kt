package com.fingertipsmcp.server.routes

import com.fingertipsmcp.core.ClipImage
import com.fingertipsmcp.model.ApiException
import com.fingertipsmcp.model.ClipboardReadRequest
import com.fingertipsmcp.model.ClipboardWriteRequest
import com.fingertipsmcp.model.GestureRequest
import com.fingertipsmcp.model.ImeShowRequest
import com.fingertipsmcp.model.KeyRequest
import com.fingertipsmcp.model.Scope
import com.fingertipsmcp.model.SwipeRequest
import com.fingertipsmcp.model.TapRequest
import com.fingertipsmcp.model.TextRequest
import com.fingertipsmcp.server.PhoneServices
import com.fingertipsmcp.server.ServerServices
import com.fingertipsmcp.server.scoped
import com.fingertipsmcp.server.withScreen
import io.ktor.http.ContentType
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * `POST /v1/input/tap|swipe|gesture|key|text`, `POST /v1/ime/hide|show`, `POST
 * /v1/clipboard/read|write`, and `GET|POST /v1/clipboard/image`.
 */
fun Route.inputRoutes(services: ServerServices, phone: PhoneServices) {
    scoped(Scope.CONTROL) {
        post("/v1/input/tap") {
            val request = call.receive<TapRequest>()
            call.respond(withScreen(call, services) { phone.input.tap(request) })
        }
        post("/v1/input/swipe") {
            val request = call.receive<SwipeRequest>()
            call.respond(withScreen(call, services) { phone.input.swipe(request) })
        }
        post("/v1/input/gesture") {
            val request = call.receive<GestureRequest>()
            call.respond(withScreen(call, services) { phone.input.gesture(request) })
        }
        post("/v1/input/key") {
            val request = call.receive<KeyRequest>()
            call.respond(withScreen(call, services) { phone.input.key(request) })
        }
        post("/v1/input/text") {
            val request = call.receive<TextRequest>()
            call.respond(withScreen(call, services) { phone.input.text(request) })
        }
        post("/v1/ime/hide") {
            call.respond(withScreen(call, services) { phone.input.hideIme() })
        }
        post("/v1/ime/show") {
            val request = call.receive<ImeShowRequest>()
            call.respond(withScreen(call, services) { phone.input.showIme(request) })
        }
        post("/v1/clipboard/read") {
            val request = call.receive<ClipboardReadRequest>()
            call.respond(withScreen(call, services) { phone.clipboard.read(request) })
        }
        post("/v1/clipboard/write") {
            val request = call.receive<ClipboardWriteRequest>()
            call.respond(withScreen(call, services) { phone.clipboard.write(request) })
        }
        get("/v1/clipboard/image") {
            val image =
                withScreen(call, services) { phone.clipboard.image() }
                    ?: throw ApiException.notFound("The clipboard holds no image")
            call.respondBytes(image.bytes, ContentType.parse(image.mimeType))
        }
        post("/v1/clipboard/image") {
            val limit = ClipImage.MAX_BYTES.toLong()
            if ((call.request.contentLength() ?: 0) > limit) throw imageTooLarge()
            val bytes = call.receiveChannel().readBuffer(limit + 1).readByteArray()
            if (bytes.size > limit) throw imageTooLarge()
            val paste = call.request.queryParameters["paste"] == "true"
            call.respond(withScreen(call, services) { phone.clipboard.writeImage(bytes, paste) })
        }
    }
}

private fun imageTooLarge() =
    ApiException(413, "too_large", "The image is over ${ClipImage.MAX_BYTES} bytes")
