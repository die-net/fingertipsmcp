// Signs that a page is still waiting to load content, for browser_read and browser_snapshot. A
// hidden tab is not drawn, so content that loads when drawn or scrolled into view may never appear
// there. Defines pagePending() for page-read.js and the snapshot check, and changes nothing.

const PENDING_MEDIA = new Set(["IMG", "PICTURE", "SOURCE", "VIDEO", "AUDIO", "IFRAME", "SVG"]);
const PENDING_PLACEHOLDER =
  '[class*="skeleton" i], [class*="shimmer" i], [class*="spinner" i], [class*="loading" i]';
const PENDING_MORE = /^(load|show|see|view) more\b/i;
const PENDING_MORE_CHARS = 40;

function pagePending() {
  // Layout still runs in a hidden tab when asked, so boxes tell what the page would show.
  function laidOut(el) {
    return el.getClientRects().length > 0 && getComputedStyle(el).visibility !== "hidden";
  }

  const signs = [];
  if (Array.from(document.querySelectorAll('[aria-busy="true"]')).some(laidOut)) {
    signs.push("busy region");
  }
  // Placeholders are empty boxes. Images and spinners inside text are not counted.
  let placeholders = 0;
  for (const el of document.querySelectorAll(PENDING_PLACEHOLDER)) {
    if (PENDING_MEDIA.has(el.tagName.toUpperCase()) || el.textContent.trim()) continue;
    if (laidOut(el)) placeholders++;
  }
  if (placeholders === 1) signs.push("1 loading placeholder");
  if (placeholders > 1) signs.push(placeholders + " loading placeholders");
  for (const el of document.querySelectorAll('button, a, [role="button"]')) {
    const text = el.textContent.replace(/\s+/g, " ").trim();
    if (text.length <= PENDING_MORE_CHARS && PENDING_MORE.test(text) && laidOut(el)) {
      signs.push('"' + text + '" control');
      break;
    }
  }
  return signs;
}
