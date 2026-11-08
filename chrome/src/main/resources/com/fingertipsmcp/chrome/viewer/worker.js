// FingertipsMCP for Chrome's viewer extension. FingertipsMCP invokes the action on the tab its
// viewer shows (Extensions.triggerAction), which is what lets tabCapture capture that tab. The
// worker gets a stream id for it and hands the id to the offscreen document, which captures and
// encodes when FingertipsMCP tells it to. The worker also tells the document when the active tab
// changes, so FingertipsMCP can follow it, and when a window changes size. The extension has no content scripts and no
// web-accessible resources, so no page can see it.
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

chrome.tabs.onActivated.addListener(() => tell({ op: "activated" }));
chrome.windows.onFocusChanged.addListener(() => tell({ op: "activated" }));
// A capture keeps the frame size it started with, so a window that changes size has to be
// captured again at its new size, which FingertipsMCP does when the tab's viewport changed.
chrome.windows.onBoundsChanged.addListener(() => tell({ op: "resized" }));

// The document opens as the worker starts, after its listeners are in place, so FingertipsMCP waits
// for it before running the action: an action run while a just-loaded worker starts is lost.
offscreen();
