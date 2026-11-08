package com.fingertipsmcp.server

import com.fingertipsmcp.model.BrowserInput
import com.fingertipsmcp.model.BrowserInputKind

/**
 * How a product resolves browser input, and which input takes the screen. REST and MCP both ask it,
 * so a call holds the screen when, and only when, its input needs it.
 */
interface BrowserInputPolicy {
    /** What [asked] means for [kind] of input, for a caller that [holdsScreen] or not. */
    fun resolve(asked: BrowserInput, kind: BrowserInputKind, holdsScreen: Boolean): BrowserInput

    /** Whether [input], already resolved, takes the screen for [kind] of input. */
    fun takesScreen(input: BrowserInput, kind: BrowserInputKind): Boolean

    companion object {
        /**
         * Android: touch brings the tab forward and takes the screen. Chrome takes cdp pointer
         * input only on the tab it shows, so that takes the screen too. cdp keys and text and
         * cdp-mouse taps reach a background tab.
         */
        val PHONE: BrowserInputPolicy =
            object : BrowserInputPolicy {
                override fun resolve(
                    asked: BrowserInput,
                    kind: BrowserInputKind,
                    holdsScreen: Boolean,
                ): BrowserInput = asked.resolved(kind, holdsScreen)

                override fun takesScreen(input: BrowserInput, kind: BrowserInputKind): Boolean =
                    when (kind) {
                        BrowserInputKind.TAP,
                        BrowserInputKind.POINTER -> input != BrowserInput.CDP_MOUSE
                        BrowserInputKind.KEY,
                        BrowserInputKind.TEXT ->
                            input == BrowserInput.TOUCH || input == BrowserInput.AUTO
                    }
            }

        /**
         * A desktop browser: every input is the mouse and keyboard over DevTools, which bring the
         * tab to the front of its window, so every input takes the screen. A desktop has no touch
         * screen, so touch is refused when the call runs, without waiting for the screen.
         */
        val DESKTOP: BrowserInputPolicy =
            object : BrowserInputPolicy {
                override fun resolve(
                    asked: BrowserInput,
                    kind: BrowserInputKind,
                    holdsScreen: Boolean,
                ): BrowserInput = if (asked == BrowserInput.TOUCH) asked else BrowserInput.CDP

                override fun takesScreen(input: BrowserInput, kind: BrowserInputKind): Boolean =
                    input != BrowserInput.TOUCH
            }
    }
}
