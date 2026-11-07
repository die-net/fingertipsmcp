// Turns a page into blocks for browser_read and browser_find. Runs in an isolated world after
// Readability.js, page-pending.js, and page-data.js, as the body of a function given `options` ({scope,
// selector}). Rendering, offsets, and searching happen in Kotlin, so this only walks the DOM.

const MAX_CHARS = 2000000;
const SKIP = new Set([
  "SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "SVG", "CANVAS", "IFRAME", "OBJECT", "EMBED",
  "BUTTON", "INPUT", "SELECT", "TEXTAREA", "IMG", "PICTURE", "VIDEO", "AUDIO", "MATH", "HEAD",
  "DIALOG",
]);
const BLOCK = new Set([
  "ADDRESS", "ARTICLE", "ASIDE", "BODY", "CAPTION", "CENTER", "DD", "DETAILS", "DIV", "DL", "DT",
  "FIELDSET", "FIGCAPTION", "FIGURE", "FOOTER", "FORM", "HEADER", "HGROUP", "LEGEND", "MAIN",
  "MENU", "NAV", "P", "SECTION", "SUMMARY", "TBODY", "TD", "TFOOT", "TH", "THEAD", "TR", "BR",
]);
const STYLE = { STRONG: "b", B: "b", EM: "i", I: "i", CODE: "code", KBD: "code", SAMP: "code" };
// Elements that, inside a table cell, set their text apart from what is around it.
const CELL_BREAK = new Set([
  "H1", "H2", "H3", "H4", "H5", "H6", "UL", "OL", "LI", "PRE", "BLOCKQUOTE", "TABLE", "HR",
]);

// The context for el's children: a link's target, or a style, on top of ctx.
function inner(el, tag, ctx) {
  if (tag === "A") {
    const href = /^(https?|mailto):/i.test(el.href || "") ? el.href : undefined;
    return Object.assign({}, ctx, { h: href || ctx.h });
  }
  if (STYLE[tag]) return Object.assign({}, ctx, { s: ctx.s || STYLE[tag] });
  return ctx;
}

// Walks roots in order, as one piece of content.
function walk(roots, live) {
  const blocks = [];
  let runs = [];
  let kind = { k: "p" };
  let chars = 0;
  let tables = 0;

  function push(text, href, style) {
    if (!text || chars > MAX_CHARS) return;
    chars += text.length;
    const last = runs[runs.length - 1];
    if (last && last.h === href && last.s === style) {
      last.t += text;
    } else {
      const run = { t: text };
      if (href) run.h = href;
      if (style) run.s = style;
      runs.push(run);
    }
  }

  function flush() {
    if (runs.some((r) => r.t.trim())) blocks.push(Object.assign({}, kind, { r: runs }));
    runs = [];
  }

  function hidden(el) {
    if (live) return hiddenNow(el);
    return el.getAttribute("aria-hidden") === "true" || el.hidden;
  }

  // A cell's text runs, with its links and styles. Blocks inside a cell, such as paragraphs or a
  // nested table, run together with a space between, since a row is one line.
  function cellRuns(cell, ctx) {
    const saved = runs;
    runs = [];
    inline(cell, ctx);
    const out = runs;
    runs = saved;
    return out;
  }

  function inline(node, ctx) {
    for (const child of node.childNodes) {
      if (chars > MAX_CHARS) return;
      if (child.nodeType === 3) {
        push(child.data.replace(/\s+/g, " "), ctx.h, ctx.s);
        continue;
      }
      if (child.nodeType !== 1) continue;
      const tag = child.tagName.toUpperCase();
      if (SKIP.has(tag) || hidden(child)) continue;
      const apart = BLOCK.has(tag) || CELL_BREAK.has(tag);
      if (apart) push(" ", ctx.h, ctx.s);
      inline(child, inner(child, tag, ctx));
      if (apart) push(" ", ctx.h, ctx.s);
    }
  }

  function table(el, ctx) {
    const rows = Array.from(el.rows || []);
    if (!rows.some((row) => row.cells.length > 1)) return false;
    flush();
    const id = tables++;
    for (const row of rows) {
      const cells = Array.from(row.cells);
      const th = cells.length > 0 && cells.every((c) => c.tagName === "TH");
      const c = cells.map((cell) => cellRuns(cell, ctx));
      blocks.push({ k: "tr", t: id, c: c, th: th, q: ctx.q || undefined });
      if (chars > MAX_CHARS) break;
    }
    return true;
  }

  function within(next, fn) {
    flush();
    const saved = kind;
    kind = next;
    fn();
    flush();
    kind = saved;
  }

  function visit(node, ctx) {
    if (chars > MAX_CHARS) return;
    if (node.nodeType === 3) {
      push(node.data.replace(/\s+/g, " "), ctx.h, ctx.s);
      return;
    }
    if (node.nodeType !== 1) return;
    const tag = node.tagName.toUpperCase();
    if (SKIP.has(tag) || hidden(node)) return;
    const children = () => node.childNodes.forEach((child) => visit(child, ctx));
    const quoted = (k) => (ctx.q ? Object.assign(k, { q: 1 }) : k);
    if (/^H[1-6]$/.test(tag)) {
      within(quoted({ k: "h", l: Number(tag[1]) }), children);
    } else if (tag === "UL" || tag === "OL") {
      flush();
      const list = { d: (ctx.list ? ctx.list.d : 0) + 1, n: tag === "OL" ? 1 : 0 };
      const inner = Object.assign({}, ctx, { list: list });
      node.childNodes.forEach((child) => visit(child, inner));
      flush();
    } else if (tag === "LI") {
      const list = ctx.list || { d: 1, n: 0 };
      const item = quoted({ k: "li", d: list.d });
      if (list.n) item.o = list.n++;
      within(item, children);
    } else if (tag === "BLOCKQUOTE") {
      flush();
      const inner = Object.assign({}, ctx, { q: 1 });
      within({ k: "p", q: 1 }, () => node.childNodes.forEach((child) => visit(child, inner)));
    } else if (tag === "PRE") {
      flush();
      const text = live ? node.innerText : node.textContent;
      if (text.trim()) blocks.push(quoted({ k: "pre", x: text.replace(/\n+$/, "") }));
      chars += text.length;
    } else if (tag === "HR") {
      flush();
      blocks.push({ k: "hr" });
    } else if (tag === "TABLE") {
      if (!table(node, ctx)) within(quoted({ k: "p" }), children);
    } else if (tag === "A" || STYLE[tag]) {
      const within = inner(node, tag, ctx);
      node.childNodes.forEach((child) => visit(child, within));
    } else if (BLOCK.has(tag)) {
      flush();
      children();
      flush();
    } else {
      children();
    }
  }

  for (const root of roots) {
    visit(root, {});
    flush();
  }
  return { blocks: blocks, clipped: chars > MAX_CHARS };
}

function published() {
  const metas = [
    'meta[property="article:published_time"]',
    'meta[name="article:published_time"]',
    'meta[itemprop="datePublished"]',
    'meta[name="date"]',
    'meta[name="pubdate"]',
    'meta[name="publish-date"]',
    'meta[name="dc.date"]',
    'meta[name="DC.date.issued"]',
    'meta[property="og:published_time"]',
  ];
  for (const selector of metas) {
    const value = document.querySelector(selector)?.getAttribute("content");
    if (value && value.trim()) return value.trim();
  }
  for (const value of ldJson) {
    const found = datePublished(value);
    if (found) return found;
  }
  return document.querySelector("time[datetime]")?.getAttribute("datetime") || null;
}

function datePublished(value) {
  if (!value || typeof value !== "object") return null;
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = datePublished(item);
      if (found) return found;
    }
    return null;
  }
  if (typeof value.datePublished === "string") return value.datePublished;
  return datePublished(value["@graph"]);
}

// Whether any JSON-LD object says isAccessibleForFree is false, as a paywalled article's does.
function paywalled(value) {
  if (!value || typeof value !== "object") return false;
  if (Array.isArray(value)) return value.some(paywalled);
  const free = value.isAccessibleForFree;
  if (free === false || (typeof free === "string" && free.trim().toLowerCase() === "false")) {
    return true;
  }
  return Object.values(value).some(paywalled);
}

const FEED_TYPES = {
  "application/rss+xml": "rss",
  "application/atom+xml": "atom",
  "application/feed+json": "json",
};
const MAX_FEEDS = 10;

// What the page's link elements, and anchors with a rel, say about it: its feeds, its canonical
// URL when that differs from its own, and the next page. An anchor's next counts only when no
// link element gave one.
function relations() {
  const out = { feeds: [], next: null, canonical: null };
  let nextFromLink = false;
  const web = (href) => /^https?:/i.test(href || "");
  for (const el of document.querySelectorAll("link[rel][href], a[rel][href]")) {
    const isLink = el.tagName === "LINK";
    const href = el.href;
    if (!web(href)) continue;
    for (const rel of el.rel.toLowerCase().split(/\s+/)) {
      if (rel === "alternate" && isLink && out.feeds.length < MAX_FEEDS) {
        const type = FEED_TYPES[(el.type || "").trim().toLowerCase()];
        const title = (el.title || "").replace(/\s+/g, " ").trim();
        const feed = { url: href, type: type };
        if (title) feed.title = title;
        if (type) out.feeds.push(feed);
      } else if (rel === "next" && (!out.next || (isLink && !nextFromLink))) {
        out.next = href;
        nextFromLink = isLink;
      } else if (rel === "canonical" && isLink && !out.canonical && !samePage(href)) {
        out.canonical = href;
      }
    }
  }
  return out;
}

// Whether href is this page, ignoring the fragment and a trailing slash.
function samePage(href) {
  const trim = (url) => url.replace(/#.*$/, "").replace(/\/$/, "");
  return trim(href) === trim(location.href);
}

function blocked(status, text) {
  const captcha =
    'iframe[src*="recaptcha"], iframe[src*="hcaptcha"], iframe[src*="challenges.cloudflare.com"],' +
    ' .g-recaptcha, .h-captcha, .cf-turnstile, #challenge-form, #challenge-running';
  if (document.querySelector(captcha)) return "captcha";
  const challenge =
    /just a moment|attention required|access denied|are you (a robot|human)|verify you are (a )?human|unusual traffic|request (was )?blocked|security check|ddos protection|pardon our interruption/i;
  if (text.length < 3000 && challenge.test(document.title + " " + text)) return "challenge page";
  if (status === 401 || status === 403 || status === 429) return "http " + status;
  // A block page is often an error status whose title says nothing, with the refusal in its text.
  if (status >= 400 && text.length < 1500 && BLOCK_TEXT.test(text)) return "http " + status;
  return null;
}

// Wording a page uses to refuse automated requests, shared across sites rather than one site's
// markup. It counts only on a short page with an error status, so an article about rate limits
// is not a block.
const BLOCK_TEXT =
  /automated (queries|requests|access|traffic)|unusual traffic|verify you are (a )?human|are you (a robot|human)|access (to this page )?has been denied|pardon our interruption/i;

// A block of prose counts toward an article once it has this many characters.
const PROSE_CHARS = 140;
// How much prose makes an article: the sum, over blocks of prose, of the square root of each one's
// characters past PROSE_CHARS, so a few long paragraphs count and many short items do not.
const ARTICLE_SCORE = 20;
// Class names and ids of parts of a page that are not its article, unless they also say they are.
const NOT_ARTICLE =
  /-ad-|banner|breadcrumbs|combx|comment|community|disqus|extra|footer|gdpr|header|menu|related|remark|replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|pagination|pager|popup/i;
const MAYBE_ARTICLE = /and|article|body|column|content|main|shadow/i;

// Whether the page reads as an article: it has paragraphs of prose, outside lists and the parts
// a page names as not its article. Readability picks some part of any page, and on a listing, such
// as search results, that part is a fragment with a few stray links and none of the results, so a
// page that is not an article is read whole. The test is modeled on Readability's own
// isProbablyReaderable.
function readsAsArticle() {
  let score = 0;
  for (const el of document.querySelectorAll("p, pre, article")) {
    if (el.closest("li") || namedNotArticle(el) || hiddenNow(el)) continue;
    const chars = el.textContent.trim().length;
    if (chars < PROSE_CHARS) continue;
    score += Math.sqrt(chars - PROSE_CHARS);
    if (score > ARTICLE_SCORE) return true;
  }
  return false;
}

// Whether el, or an element it is in, is named by its class or id as a part of the page that is not
// its article, such as its comments or sidebar.
function namedNotArticle(el) {
  for (let node = el; node && node.nodeType === 1 && node !== document.body; node = node.parentNode) {
    const named = (node.getAttribute("class") || "") + " " + node.id;
    if (NOT_ARTICLE.test(named) && !MAYBE_ARTICLE.test(named)) return true;
  }
  return false;
}

// Elements kept in the copy Readability reads even though they are not drawn, since Readability
// reads them: JSON-LD and other scripts, and images in noscript.
const KEEP_UNDRAWN = new Set(["SCRIPT", "NOSCRIPT", "TEMPLATE", "STYLE", "META", "LINK"]);
const MAX_ERROR_CHARS = 200;

// A copy of the page for Readability, without the elements the page hides. Readability reads a
// copy, which is not laid out, so it cannot tell what a stylesheet hides, and article scope would
// hold text that page scope leaves out.
function visibleCopy() {
  const copy = document.cloneNode(false);
  if (document.documentElement) copy.appendChild(visibleClone(document.documentElement, false));
  return copy;
}

function visibleClone(node, inBody) {
  const out = node.cloneNode(false);
  for (const child of node.childNodes) {
    if (child.nodeType === 1) {
      const tag = child.tagName.toUpperCase();
      const body = inBody || tag === "BODY";
      if (body && !KEEP_UNDRAWN.has(tag) && hiddenNow(child)) continue;
      out.appendChild(visibleClone(child, body));
    } else {
      out.appendChild(child.cloneNode(false));
    }
  }
  return out;
}

// Whether a live element is hidden from a reader of the page.
function hiddenNow(el) {
  if (el.getAttribute("aria-hidden") === "true" || el.hidden) return true;
  if (!el.checkVisibility) return false;
  return !el.checkVisibility({ checkVisibilityCSS: true, visibilityProperty: true });
}

const ldJson = [];
for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
  try {
    ldJson.push(JSON.parse(script.textContent));
  } catch (e) {
    // Malformed JSON-LD is common; skip it.
  }
}
const nav = performance.getEntriesByType("navigation")[0];
const status = (nav && nav.responseStatus) || 0;
const bodyText = document.body ? document.body.innerText : "";
let extracted = options.scope;
let walked = null;
let articlePublished = null;
let articleError = null;
let selected = null;
if (options.selector) {
  let matches;
  try {
    matches = document.querySelectorAll(options.selector);
  } catch (e) {
    // A selector that is not valid CSS is the caller's mistake, not the page's.
    if (e.name === "SyntaxError") return { badSelector: true };
    throw e;
  }
  if (matches.length === 0) return { missing: true };
  // Matches come in document order, so one inside an earlier match is inside the last one kept.
  // It is left out, since its text is already in that one.
  const roots = [];
  for (const el of matches) {
    const last = roots[roots.length - 1];
    if (!last || !last.contains(el)) roots.push(el);
  }
  walked = walk(roots, true);
  extracted = "selector";
  selected = matches.length;
} else if (options.scope === "article" && readsAsArticle()) {
  try {
    const article = new Readability(visibleCopy(), { serializer: (el) => el }).parse();
    if (article && article.content && article.textContent.trim()) {
      walked = walk([article.content], false);
      articlePublished = article.publishedTime || null;
    }
  } catch (e) {
    // Readability can fail on unusual documents; the page is read whole, and PhoneAPI logs why.
    articleError = String((e && e.name) || "Error") + ": " + String((e && e.message) || e);
    articleError = articleError.slice(0, MAX_ERROR_CHARS);
  }
  if (!walked || walked.blocks.length === 0) {
    walked = null;
    extracted = "page";
  }
} else if (options.scope === "article") {
  extracted = "page";
}
if (!walked && document.body) walked = walk([document.body], true);
if (!walked) walked = { blocks: [], clipped: false };

const related = relations();

return {
  title: document.title || null,
  // Chrome's own error page is at chrome-error://, but its navigation still names the URL that failed.
  url: location.protocol === "chrome-error:" && nav ? nav.name : location.href,
  readyState: document.readyState,
  visibility: document.visibilityState,
  pending: pagePending(),
  status: status,
  published: published() || articlePublished,
  blocked: blocked(status, bodyText),
  paywall: ldJson.some(paywalled),
  feeds: related.feeds,
  next: related.next,
  canonical: related.canonical,
  data: pageData(null).blobs,
  pageChars: bodyText.trim().length,
  links: document.links.length,
  tables: document.querySelectorAll("table").length,
  forms: document.forms.length,
  extracted: extracted,
  selected: selected,
  clipped: walked.clipped,
  blocks: walked.blocks,
  articleError: articleError,
};
