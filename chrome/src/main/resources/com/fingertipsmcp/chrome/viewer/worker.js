// FingertipsMCP for Chrome's viewer extension. FingertipsMCP invokes the action on the tab its
// viewer shows (Extensions.triggerAction), which is what lets tabCapture capture that tab. The
// worker gets a stream id for it and hands the id to the offscreen document, which captures and
// encodes when FingertipsMCP tells it to. The worker also tells the document when the active tab
// changes, so FingertipsMCP can follow it, and when a window changes size, and answers what type a
// window is. The extension has no content scripts and no web-accessible resources, so no page can
// see it.
"use strict";

const OFFSCREEN = "offscreen.html";

// Makes the offscreen document unless it is there already.
async function offscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({
    url: OFFSCREEN,
    reasons: ["USER_MEDIA"],
    justification: "Encodes the tab FingertipsMCP's viewer shows.",
  });
}

// Sends a message to the offscreen document, which may not exist yet.
function tell(message) {
  chrome.runtime.sendMessage(message).catch(() => {});
}

chrome.action.onClicked.addListener(async (tab) => {
  await offscreen();
  try {
    const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: tab.id });
    tell({ op: "stream", streamId: streamId });
  } catch (e) {
    tell({ op: "stream", error: String((e && e.message) || e) });
  }
});

// Answers the offscreen document with the type of the window it names, or of the window Chrome
// focused last when it names none, as chrome.windows gives it, or null when the extension can't see
// that window, as with one of another profile. Only a normal window has this extension's action,
// and FingertipsMCP runs the action only on a tab in one; the Viewer section of docs/chrome.md says
// why. The focused window's type tells the viewer page when a popup is in front. The document can't
// ask chrome.windows itself.
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (message.op !== "window") return false;
  const asked =
    message.id === undefined ? chrome.windows.getLastFocused() : chrome.windows.get(message.id);
  asked.then(
    (window) => reply({ type: window.type }),
    () => reply({ type: null }),
  );
  return true;
});

chrome.tabs.onActivated.addListener(() => tell({ op: "activated" }));
chrome.windows.onFocusChanged.addListener(() => tell({ op: "activated" }));
// A window that closes while Chrome is not the active app moves no focus, and a popup that closed
// is no longer in front.
chrome.windows.onRemoved.addListener(() => tell({ op: "activated" }));
// A capture keeps the frame size it started with, so a window that changes size has to be
// captured again at its new size, which FingertipsMCP does when the tab's viewport changed.
chrome.windows.onBoundsChanged.addListener(() => tell({ op: "resized" }));

// The document opens as the worker starts, after its listeners are in place, so FingertipsMCP waits
// for it before running the action: an action run while a just-loaded worker starts is lost.
offscreen();
