// FingertipsMCP for Chrome's viewer extension. FingertipsMCP invokes the action on the tab its
// viewer shows (Extensions.triggerAction), which is what lets tabCapture capture that tab. The
// worker gets a stream id for it and hands the id to the offscreen document, which captures and
// encodes when FingertipsMCP tells it to. The worker also tells the document when the active tab
// changes, so FingertipsMCP can follow it, and when a window changes size, and answers which tab
// Chrome shows and what type the focused window is. The extension has no content scripts and no
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

// The DevTools target ids of the tab Chrome shows: the active tab of the normal window Chrome
// focused last. Chrome knows which tab that is as it switches, while a page's visibilityState
// changes only once its renderer hears, which can be 150 ms later, and a captured tab reads visible
// whatever Chrome shows. Only a normal window has this extension's action, and the action on a tab
// anywhere else crashes Chrome 156; the Viewer section of docs/chrome.md says why. A window of
// another profile is not one the extension sees. chrome.debugger links the tab to its target
// without attaching to it.
async function showing() {
  const normal = await chrome.windows.getAll({ windowTypes: ["normal"] });
  if (normal.length === 0) return { ids: [] };
  const window = await chrome.windows.getLastFocused({ populate: true, windowTypes: ["normal"] });
  const tab = window.tabs.find((t) => t.active);
  if (!tab) return { ids: [] };
  const targets = await chrome.debugger.getTargets();
  return { ids: targets.filter((t) => t.type === "page" && t.tabId === tab.id).map((t) => t.id) };
}

// Answers the offscreen document, which can't ask chrome.windows itself: which tab Chrome shows, or
// the type of the window Chrome focused last, null when there is none. The focused window's type
// tells the viewer page when a popup is in front.
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (message.op === "showing") {
    showing().then(reply, (e) => reply({ error: String((e && e.message) || e) }));
    return true;
  }
  if (message.op !== "window") return false;
  chrome.windows.getLastFocused().then(
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
