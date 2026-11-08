// Turns a PDF into blocks for browser_read and browser_find, in the same shape page-read.js gives.
// FingertipsMCP answers the tab's navigation to the PDF's URL with an empty page of its own, so this
// runs in that page's main world after pdf.js, with no site script beside it, and stays there as
// `fingertipsPdf`, keeping the parsed file between calls until the tab navigates or closes. The page
// has the PDF's origin, so it fetches the file as the site's own page would, from Chrome's cache
// when opening the PDF put it there. pdf.js gives positioned runs of text; this joins them into
// lines, then lines into headings, list items, paragraphs, and code by font size, spacing, and
// font. Rendering, offsets, and searching happen in Kotlin. FingertipsMCP wraps this in a strict
// function, so only `fingertipsPdf` is global.

const MAX_CHARS = 2000000;
// A line counts as a heading when its font is this much larger than the body text's.
const HEADING_RATIO = 1.15;
const MAX_HEADING_CHARS = 200;
// Lines further apart than this many line heights start a new paragraph.
const PARAGRAPH_GAP = 1.6;
// How far into a line's letters and digits a bookmark's title may start, leaving room for a
// section number such as "4.2.1" that the bookmark leaves out.
const NUMBER_ROOM = 8;
// A section number at the start of a line: "3", "2.1", "4.2.1.", or a Roman numeral with a dot.
const SECTION = /^\s*((?:\d+\.)*\d+\.?|[IVXL]+\.)\s+(?=\p{Lu})/u;
const MAX_SECTION_CHARS = 80;
// How many lines at the top and at the bottom of a page can be a running header or footer.
const EDGE_LINES = 2;
const BULLET = /^\s*([•◦▪‣∙●○■□–]|-(?=\s))\s*/;
const NUMBERED = /^\s*(\d{1,3})[.)]\s+/;

// Where text sits on the page as it is displayed, after the page's own rotation, turned by
// `quarter` quarter turns so that text in that direction reads left to right. `y` grows upward.
function placed(point, quarter) {
  const [x, y] = point;
  const turned = [
    [x, y],
    [y, -x],
    [-x, -y],
    [-y, x],
  ][quarter];
  return { x: turned[0], y: -turned[1] };
}

// The direction an item's text runs on the displayed page, in quarter turns clockwise.
function quarterOf(m) {
  return ((Math.round(Math.atan2(m[1], m[0]) / (Math.PI / 2)) % 4) + 4) % 4;
}

// Moves a cut in text to the nearest word edge, since positions within an item are estimates.
function wordEdge(text, at) {
  if (at <= 0 || at >= text.length) return Math.max(0, Math.min(at, text.length));
  if (/\s/.test(text[at]) || /\s/.test(text[at - 1])) return at;
  let back = at;
  while (back > 0 && !/\s/.test(text[back - 1])) back--;
  let ahead = at;
  while (ahead < text.length && !/\s/.test(text[ahead])) ahead++;
  return at - back <= ahead - at ? back : ahead;
}

// An item's text in pieces, each with the link it falls under. An item can hold a whole line,
// and a link can cover one word of it, so the item is cut where each link's box starts and ends,
// with character positions estimated from the item's width.
function linkedPieces(links, text, x0, width, baseline, size) {
  const y = baseline + size / 3;
  const cuts = [];
  for (const link of links) {
    const r = link.rect;
    if (y < r[1] || y > r[3] || width <= 0) continue;
    const from = Math.max(r[0], x0);
    const to = Math.min(r[2], x0 + width);
    if (to <= from) continue;
    const start = wordEdge(text, Math.round(((from - x0) / width) * text.length));
    const end = wordEdge(text, Math.round(((to - x0) / width) * text.length));
    if (end > start) cuts.push({ start: start, end: end, url: link.url });
  }
  if (cuts.length === 0) return [{ t: text, h: null }];
  cuts.sort((a, b) => a.start - b.start);
  const pieces = [];
  let at = 0;
  for (const cut of cuts) {
    if (cut.start < at) continue;
    if (cut.start > at) pieces.push({ t: text.slice(at, cut.start), h: null });
    pieces.push({ t: text.slice(cut.start, cut.end), h: cut.url });
    at = cut.end;
  }
  if (at < text.length) pieces.push({ t: text.slice(at), h: null });
  return pieces;
}

// The page's lines, each with its runs, baseline, and largest font size. Positions are taken as
// the page is displayed, so a page with its own rotation reads like any other. Text that does not
// run the way most of the page's text does is mostly a stamp in the margin or a label along a
// figure's axis, and its items do not share a baseline, so it is left out. A run in a monospace
// font is code, and a line that is all code is marked `pre`.
async function pageLines(page, number) {
  const content = await page.getTextContent();
  const annotations = await page.getAnnotations({ intent: "display" });
  const viewport = page.getViewport({ scale: 1 });
  const items = [];
  const chars = [0, 0, 0, 0];
  for (const item of content.items) {
    if (typeof item.str !== "string") continue;
    const m = globalThis.pdfjsLib.Util.transform(viewport.transform, item.transform);
    const quarter = quarterOf(m);
    chars[quarter] += item.str.length;
    items.push({ item: item, m: m, quarter: quarter });
  }
  const quarter = chars.indexOf(Math.max(...chars));
  const links = annotations
    .filter((a) => a.subtype === "Link" && a.url)
    .map((a) => {
      const p = placed(viewport.convertToViewportPoint(a.rect[0], a.rect[1]), quarter);
      const q = placed(viewport.convertToViewportPoint(a.rect[2], a.rect[3]), quarter);
      const box = [Math.min(p.x, q.x), Math.min(p.y, q.y), Math.max(p.x, q.x), Math.max(p.y, q.y)];
      return { rect: box, url: a.url };
    });
  const lines = [];
  let line = null;
  const end = () => {
    if (line && line.runs.some((r) => r.t.trim())) {
      line.pre = line.runs.every((r) => r.s === "code" || !r.t.trim());
      lines.push(line);
    }
    line = null;
  };
  for (const { item, m, quarter: q } of items) {
    if (q !== quarter) continue;
    const size = Math.hypot(m[2], m[3]) || item.height || 0;
    const at = placed([m[4], m[5]], quarter);
    if (line && item.str && Math.abs(at.y - line.y) > Math.max(size, line.size) * 0.5) end();
    if (item.str) {
      if (!line) line = { page: number, y: at.y, x: at.x, size: 0, runs: [] };
      line.size = Math.max(line.size, size);
      const style = content.styles[item.fontName];
      const code = style && style.fontFamily === "monospace" ? "code" : null;
      for (const piece of linkedPieces(links, item.str, at.x, item.width || 0, at.y, size)) {
        const last = line.runs[line.runs.length - 1];
        if (last && (last.h || null) === piece.h && (last.s || null) === code) {
          last.t += piece.t;
        } else {
          const run = { t: piece.t };
          if (piece.h) run.h = piece.h;
          if (code) run.s = code;
          line.runs.push(run);
        }
      }
    }
    if (item.hasEOL) end();
  }
  end();
  return { lines: lines, links: links.length, viewport: viewport, quarter: quarter };
}

// Drops running headers and footers, such as a page number or a title repeated in the margin of
// every page: one of the two lines nearest the top or bottom of its page, no larger than the body
// text, whose text without its digits is at the same height on at least 3 pages and a quarter of
// them. A slide's title repeats too, but it is set larger. A watermark, such as "Draft", is the
// same text, set larger than the body, at the same height and size on at least half the pages,
// wherever it sits. Body text that repeats, as a form's labels do, stays.
function dropRunning(pages) {
  const body = bodySize(pages.flatMap((page) => page.lines));
  const marks = new Map();
  const markKey = (line) =>
    line.size > body + 0.5
      ? [Math.round(line.y / 3), Math.round(line.size), norm(lineText(line))].join(":")
      : null;
  for (const page of pages) {
    for (const k of new Set(page.lines.map(markKey).filter(Boolean))) {
      marks.set(k, (marks.get(k) || 0) + 1);
    }
  }
  const watermarked = Math.max(3, pages.length / 2);
  for (const page of pages) {
    page.lines = page.lines.filter((l) => {
      const k = markKey(l);
      return !k || marks.get(k) < watermarked;
    });
  }
  const keys = pages.map((page) => {
    const order = page.lines.slice().sort((a, b) => b.y - a.y);
    const edges = new Map();
    order.slice(0, EDGE_LINES).forEach((line) => edges.set(line, "top"));
    order.slice(-EDGE_LINES).forEach((line) => edges.set(line, edges.get(line) || "bottom"));
    const found = new Map();
    // A running line sits in the same place on each page, so its height is in the key, as for a
    // watermark. Without it, every line of only digits near an edge shared one key, and the last
    // rows of a table of figures went with the page numbers.
    for (const [line, side] of edges) {
      if (line.size > body + 0.5) continue;
      const text = norm(lineText(line).replace(/\d+/g, ""));
      found.set(line, [side, Math.round(line.y / 3), text].join(":"));
    }
    return found;
  });
  const counts = new Map();
  for (const found of keys) {
    for (const k of new Set(found.values())) counts.set(k, (counts.get(k) || 0) + 1);
  }
  const least = Math.max(3, pages.length / 4);
  pages.forEach((page, i) => {
    page.lines = page.lines.filter((line) => {
      const k = keys[i].get(line);
      return !k || counts.get(k) < least;
    });
  });
}

function lineText(line) {
  return line.runs.map((r) => r.t).join("");
}

// The size most of the text is set in, rounded to half a point.
function bodySize(lines) {
  const chars = new Map();
  for (const line of lines) {
    const size = Math.round(line.size * 2) / 2;
    chars.set(size, (chars.get(size) || 0) + lineText(line).length);
  }
  let best = 0;
  let most = -1;
  for (const [size, count] of chars) {
    if (count > most) {
      best = size;
      most = count;
    }
  }
  return best;
}

// The largest text on the first page with letters in it, which is usually the title.
function titleSize(lines) {
  let largest = 0;
  for (const line of lines) {
    if (line.page === 1 && /\p{L}/u.test(lineText(line))) largest = Math.max(largest, line.size);
  }
  return largest;
}

// Whether a line is a heading: larger than the body text, and no larger than the title when the
// first page has one set larger than the body, since larger text past it is then a figure's labels
// rather than the document's structure.
function isHeading(line, fonts) {
  const text = lineText(line).trim();
  return (
    fonts.body > 0 &&
    line.size >= fonts.body * HEADING_RATIO &&
    (fonts.title < fonts.body * HEADING_RATIO || line.size <= fonts.title + 0.5) &&
    text.length <= MAX_HEADING_CHARS &&
    /\p{L}/u.test(text)
  );
}

// Heading levels from 1, largest size first.
function headingLevels(lines, fonts) {
  const sizes = new Set();
  for (const line of lines) if (isHeading(line, fonts)) sizes.add(Math.round(line.size * 2) / 2);
  const levels = new Map();
  Array.from(sizes)
    .sort((a, b) => b - a)
    .forEach((size, i) => levels.set(size, Math.min(i + 1, 6)));
  return levels;
}

// Appends a line's runs to a block's, joining a word hyphenated across the break.
function appendRuns(block, runs) {
  const last = block.r[block.r.length - 1];
  const first = runs[0];
  if (last && /\p{L}-$/u.test(last.t) && /^\p{Ll}/u.test(first.t)) {
    last.t = last.t.slice(0, -1);
  } else if (last) {
    last.t += " ";
  }
  for (const run of runs) {
    const tail = block.r[block.r.length - 1];
    if (tail && (tail.h || null) === (run.h || null) && (tail.s || null) === (run.s || null)) {
      tail.t += run.t;
    } else {
      block.r.push(Object.assign({}, run));
    }
  }
}

// Drops a list marker from the start of a line's runs.
function stripMarker(runs, marker) {
  const copy = runs.map((r) => Object.assign({}, r));
  copy[0].t = copy[0].t.slice(marker.length);
  return copy;
}

// The lines of the title: the first run of lines on the first page in its largest text. Lines
// that follow each other, as a title over two lines does, belong together.
function titleLines(lines) {
  const largest = titleSize(lines);
  const found = [];
  for (const line of lines) {
    if (line.page !== 1) break;
    if (Math.abs(line.size - largest) < 0.5 && /\p{L}/u.test(lineText(line))) {
      found.push(line);
    } else if (found.length > 0) {
      break;
    }
  }
  return found;
}

// The title, for a PDF whose metadata has none.
function firstPageTitle(lines) {
  const title = titleLines(lines)
    .map((line) => lineText(line).trim())
    .join(" ");
  return title && title.length <= MAX_HEADING_CHARS ? title : null;
}

// A line's letters and digits, lowercased, for matching it to a bookmark's title.
function norm(text) {
  return text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
}

// Where a bookmark points: its page, and the point on it, when the bookmark names one.
async function destination(doc, dest) {
  try {
    const explicit = typeof dest === "string" ? await doc.getDestination(dest) : dest;
    if (!Array.isArray(explicit) || explicit[0] == null) return null;
    const ref = explicit[0];
    const index = typeof ref === "number" ? ref : await doc.getPageIndex(ref);
    const kind = explicit[1] && explicit[1].name;
    let point = null;
    if (kind === "XYZ" && typeof explicit[3] === "number") point = [explicit[2] || 0, explicit[3]];
    if ((kind === "FitH" || kind === "FitBH") && typeof explicit[2] === "number") {
      point = [0, explicit[2]];
    }
    return { page: index + 1, point: point };
  } catch (e) {
    // A bookmark that points nowhere this can follow is skipped.
    return null;
  }
}

// The document's bookmarks in order, each with its depth from 1 and where it points.
async function bookmarks(doc) {
  const out = [];
  const walk = async (items, depth) => {
    for (const item of items || []) {
      out.push({ title: item.title || "", depth: depth, target: await destination(doc, item.dest) });
      await walk(item.items, depth + 1);
    }
  };
  await walk(await doc.getOutline().catch(() => null), 1);
  return out;
}

// Splits runs at a character offset into the text before and after it.
function splitRuns(runs, at) {
  const before = [];
  const after = [];
  let seen = 0;
  for (const run of runs) {
    const cut = Math.max(0, Math.min(run.t.length, at - seen));
    if (cut > 0) before.push(Object.assign({}, run, { t: run.t.slice(0, cut) }));
    if (cut < run.t.length) after.push(Object.assign({}, run, { t: run.t.slice(cut) }));
    seen += run.t.length;
  }
  return [before, after];
}

// The offset in raw text just past its first `count` letters and digits, and past any spaces and
// punctuation that follow them.
function rawOffset(text, count) {
  let seen = 0;
  let i = 0;
  while (i < text.length && seen < count) {
    if (/[\p{L}\p{N}]/u.test(text[i])) seen++;
    i++;
  }
  while (i < text.length && /[\s.:\u2014\u2013-]/.test(text[i])) i++;
  return i;
}

// Marks the lines that bookmarks point to as headings at the bookmark's depth. A bookmark is
// matched to the line nearest below the point it names, or the first on its page, whose text holds
// the title near its start; a title can carry on to the next line, and a heading run into its
// paragraph is split off. Returns how many bookmarks were found.
function markBookmarks(marks, pages) {
  let found = 0;
  marks.forEach((mark, id) => {
    const want = norm(mark.title);
    const page = mark.target && pages[mark.target.page - 1];
    if (want.length < 2 || !page) return;
    const top = mark.target.point
      ? placed(page.viewport.convertToViewportPoint(...mark.target.point), page.quarter).y
      : null;
    let best = null;
    page.lines.forEach((line, i) => {
      if (line.mark) return;
      const one = norm(lineText(line));
      const next = page.lines[i + 1];
      const joined = one + (next && !next.mark ? norm(lineText(next)) : "");
      const at = joined.indexOf(want);
      if (at < 0 || at > NUMBER_ROOM || at >= one.length) return;
      const distance = top === null ? i : top - line.y;
      if (top !== null && distance < -line.size * 1.5) return;
      if (!best || distance < best.distance) {
        best = { index: i, distance: distance, end: at + want.length, length: one.length };
      }
    });
    if (!best) return;
    found++;
    const line = page.lines[best.index];
    const tag = { level: mark.depth, id: id };
    if (best.end > best.length) {
      line.mark = tag;
      page.lines[best.index + 1].mark = tag;
      return;
    }
    const text = lineText(line);
    const cut = rawOffset(text, best.end);
    if (text.slice(cut).trim().length > 0) {
      const [head, rest] = splitRuns(line.runs, cut);
      line.runs = head;
      page.lines.splice(best.index + 1, 0, Object.assign({}, line, { runs: rest, mark: null }));
    }
    line.mark = tag;
  });
  return found;
}

// A section number as numbers: "2.1." is [2, 1], and "IV." is [4].
function sectionNumber(text) {
  const match = SECTION.exec(text);
  if (!match) return null;
  const number = match[1].replace(/\.$/, "");
  if (/^[IVXL]+$/.test(number)) {
    const values = { I: 1, V: 5, X: 10, L: 50 };
    let total = 0;
    for (let i = 0; i < number.length; i++) {
      const value = values[number[i]];
      total += i + 1 < number.length && values[number[i + 1]] > value ? -value : value;
    }
    return [total];
  }
  return number.split(".").map(Number);
}

// Whether section number `next` can follow `last` in a document: the next number at some level,
// such as 2.4 then 2.5 or 3, or the first child, such as 2.4 then 2.4.1. One skipped number is
// allowed, for a heading this misses. A table of bit fields or a numbered list does not count up
// this way.
function follows(last, next) {
  if (next.some((n) => n === 0)) return false;
  if (!last) return next.every((n, i) => n <= (i === 0 ? 2 : 1));
  if (next.length === last.length + 1 && next[next.length - 1] <= 2) {
    return next.slice(0, -1).every((n, i) => n === last[i]);
  }
  if (next.length > last.length) return false;
  const k = next.length - 1;
  const step = next[k] - last[k];
  return step >= 1 && step <= 2 && next.slice(0, k).every((n, i) => n === last[i]);
}

// The depth of a section number that starts a standalone heading line, such as 2 for "4.1
// Results", or 0. A line in a list or a contents page has a number too, so the line must be short,
// start its title with a capital, not end like a list item or a contents entry, stand apart from
// the lines around it, and be no smaller than the body text, as a numbered footnote is.
function sectionDepth(line, prev, next, body) {
  const text = lineText(line).trim();
  const match = SECTION.exec(text);
  if (!match || text.length > MAX_SECTION_CHARS || line.size < body - 0.5) return 0;
  if (/[,;:]$/.test(text) || /\s\d+$/.test(text) || /\.{3,}|(\. ){3,}/.test(text)) return 0;
  const above = prev && prev.page === line.page ? prev.y - line.y : Infinity;
  const below = next && next.page === line.page ? line.y - next.y : Infinity;
  if (above < line.size * 1.3 || below < line.size * 1.1) return 0;
  return sectionNumber(text).length;
}

// Heading levels renumbered from 1 in order, so that sizes or depths that never appear leave no
// gaps in the outline.
function closeLevels(blocks) {
  const used = Array.from(new Set(blocks.filter((b) => b.k === "h").map((b) => b.l))).sort(
    (a, b) => a - b,
  );
  for (const block of blocks) if (block.k === "h") block.l = Math.min(used.indexOf(block.l) + 1, 6);
  return blocks;
}

// Each line's heading level, or 0. With bookmarks, only the lines they point to and the title are
// headings. Without them, text set larger than the body is, by size, and so is a body-size line
// that opens with a section number, at a level below the headings numbered one level up.
function headingPlan(lines, marked) {
  const fonts = { body: bodySize(lines), title: titleSize(lines) };
  // A title set no larger than the body is not told apart from it.
  const titled = fonts.title > fonts.body ? titleLines(lines) : [];
  if (marked) {
    const shift = titled.some((l) => l.mark) ? 0 : 1;
    return lines.map((l) => (l.mark ? l.mark.level + shift : titled.includes(l) ? 1 : 0));
  }
  const levels = headingLevels(lines, fonts);
  const byDepth = new Map();
  const plan = lines.map((line, i) => {
    if (!isHeading(line, fonts)) return 0;
    const level = levels.get(Math.round(line.size * 2) / 2) || 1;
    const match = SECTION.exec(lineText(line).trim());
    if (match) {
      const depth = match[1].replace(/\.$/, "").split(".").length;
      if (!byDepth.has(depth)) byDepth.set(depth, level);
    }
    return level;
  });
  const offset = titled.length > 0 ? 1 : 0;
  let last = null;
  lines.forEach((line, i) => {
    const number = sectionNumber(lineText(line).trim());
    if (plan[i]) {
      if (number && follows(last, number)) last = number;
      return;
    }
    if (line.pre || isHeading(line, fonts)) return;
    const depth = sectionDepth(line, lines[i - 1], lines[i + 1], fonts.body);
    if (!depth || !follows(last, number)) return;
    last = number;
    const above = byDepth.get(depth - 1);
    plan[i] = byDepth.get(depth) || (above ? above + 1 : offset + depth);
  });
  return plan;
}

function toBlocks(lines, marked) {
  const plan = headingPlan(lines, marked);
  const blocks = [];
  let block = null;
  let prev = null;
  let prevMark = null;
  const flush = () => {
    if (block && (block.k === "pre" ? block.x.trim() : block.r.some((r) => r.t.trim()))) {
      blocks.push(block);
    }
    block = null;
  };
  lines.forEach((line, i) => {
    if (prev && line.page !== prev.page) {
      flush();
      if (blocks.length > 0) blocks.push({ k: "hr" });
    }
    const text = lineText(line);
    const gap = prev && prev.page === line.page ? prev.y - line.y : Infinity;
    const near = gap > 0 && gap <= PARAGRAPH_GAP * Math.max(line.size, prev ? prev.size : 0);
    const sameSize = prev && Math.abs(line.size - prev.size) < 0.5;
    const level = plan[i];
    const mark = line.mark || null;
    if (level) {
      const same = mark ? mark === prevMark : !prevMark && near;
      if (block && block.k === "h" && block.l === level && same) {
        appendRuns(block, line.runs);
      } else {
        flush();
        block = { k: "h", l: level, r: line.runs.map((r) => Object.assign({}, r)) };
      }
    } else if (line.pre) {
      if (block && block.k === "pre" && gap > 0 && gap <= PARAGRAPH_GAP * 1.5 * line.size) {
        block.x += "\n" + text;
      } else {
        flush();
        block = { k: "pre", x: text };
      }
    } else if (BULLET.test(text) || NUMBERED.test(text)) {
      flush();
      const numbered = NUMBERED.exec(text);
      const marker = (numbered || BULLET.exec(text))[0];
      block = { k: "li", r: stripMarker(line.runs, marker) };
      if (numbered) block.o = Number(numbered[1]);
      if (!block.r[0].t.trim()) block.r.shift();
    } else if (block && (block.k === "p" || block.k === "li") && near && sameSize) {
      appendRuns(block, line.runs);
    } else {
      flush();
      block = { k: "p", r: line.runs.map((r) => Object.assign({}, r)) };
    }
    prev = line;
    prevMark = mark;
  });
  flush();
  return closeLevels(blocks);
}

// A document's pages as far as they have been read, and the text taken from them.
function newProgress() {
  return { pages: [], next: 1, links: 0, chars: 0, clipped: false };
}

// Reads the next page into `progress`, keeping text up to MAX_CHARS.
async function readPage(doc, progress) {
  const page = await doc.getPage(progress.next);
  const found = await pageLines(page, progress.next);
  page.cleanup();
  progress.next++;
  progress.links += found.links;
  const kept = [];
  for (const line of found.lines) {
    progress.chars += lineText(line).length;
    if (progress.chars > MAX_CHARS) progress.clipped = true;
    if (!progress.clipped) kept.push(line);
  }
  progress.pages.push(Object.assign(found, { lines: kept }));
}

// The facts and blocks of a document whose pages have all been read, without the page's own state,
// which `read` adds each time.
async function finish(doc, url, progress) {
  const pages = progress.pages;
  dropRunning(pages);
  const marks = await bookmarks(doc);
  // Bookmarks that mostly name nothing in the text, such as page numbers, are not an outline.
  const marked = marks.length >= 2 && markBookmarks(marks, pages) >= marks.length / 2;
  if (!marked) for (const page of pages) for (const line of page.lines) line.mark = null;
  const lines = pages.flatMap((page) => page.lines);
  const meta = await doc.getMetadata().catch(() => null);
  const title =
    (meta && meta.info && typeof meta.info.Title === "string" && meta.info.Title.trim()) ||
    firstPageTitle(lines);
  return {
    title: title || null,
    url: url,
    pending: [],
    status: 0,
    pageChars: progress.chars,
    links: progress.links,
    tables: 0,
    forms: 0,
    extracted: "pdf",
    clipped: progress.clipped,
    blocks: toBlocks(lines, marked),
  };
}

// The document parsed from `url`, the pages read so far, and its extract once worked out.
let task = null;
let doc = null;
let url = null;
let progress = null;
let extract = null;

async function drop() {
  doc = null;
  url = null;
  progress = null;
  extract = null;
  if (task) {
    const old = task;
    task = null;
    await old.destroy();
  }
}

// Fetches the file at `want` and parses it. A copy that opening the PDF left in Chrome's cache is
// used as it is, fresh or not, so a cached PDF costs no request. Returns `{ok: true}`, or the error
// and what went wrong: `fetch` when the request failed, `http` for an error status, `type` when the
// reply is not a PDF, `size` when it is over `maxBytes`, and `parse` when pdf.js cannot read it.
async function load(want, maxBytes) {
  await drop();
  let response;
  try {
    response = await fetch(want, { credentials: "include", cache: "force-cache" });
  } catch (e) {
    return { error: "fetch", detail: String((e && e.message) || e) };
  }
  if (!response.ok) return { error: "http", detail: String(response.status) };
  const type = (response.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  if (type && !/\/(x-)?pdf$/.test(type) && type !== "application/octet-stream") {
    return { error: "type", detail: type };
  }
  const length = Number(response.headers.get("content-length") || 0);
  if (length > maxBytes) return { error: "size", detail: String(length) };
  const data = new Uint8Array(await response.arrayBuffer());
  if (data.length > maxBytes) return { error: "size", detail: String(data.length) };
  task = globalThis.pdfjsLib.getDocument({
    data: data,
    verbosity: 0,
    isEvalSupported: false,
    disableFontFace: true,
    useWorkerFetch: false,
    stopAtErrors: false,
  });
  try {
    doc = await task.promise;
  } catch (e) {
    return { error: "parse", detail: String((e && e.message) || e) };
  }
  url = want;
  progress = newProgress();
  return { ok: true };
}

// The load FingertipsMCP started: its URL, the promise of its end, and its result once it has one.
// FingertipsMCP makes a new page for each load, so a page has at most one.
let job = null;

// Whether two URLs name one document, as the browser writes them and whatever their fragment.
function sameDocument(a, b) {
  try {
    return new URL(a).href.split("#")[0] === new URL(b).href.split("#")[0];
  } catch (e) {
    return false;
  }
}

globalThis.fingertipsPdf = {
  // "parsed" when the document from `want` is parsed, "loading" while it is fetched or parsed,
  // and null otherwise.
  state(want) {
    if (!job || !sameDocument(job.url, want)) return null;
    if (!job.result) return "loading";
    return job.result.ok ? "parsed" : null;
  },
  // Starts loading `want`, as `load` does, without waiting for it.
  start(want, maxBytes) {
    const started = { url: want, result: null, done: null };
    job = started;
    started.done = load(want, maxBytes).then(
      (result) => {
        started.result = result;
      },
      (e) => {
        started.result = { error: "parse", detail: String((e && e.message) || e) };
      },
    );
  },
  // Waits up to `budget` milliseconds for the load, so that no one call waits for a whole
  // download. Returns its result, or `{pending: true}` while it goes on.
  async wait(budget) {
    if (!job) throw new Error("No PDF is loading");
    let timer = null;
    const later = new Promise((resolve) => {
      timer = setTimeout(resolve, budget);
    });
    await Promise.race([job.done, later]);
    clearTimeout(timer);
    return job.result || { pending: true };
  },
  // Reads pages for about `budget` milliseconds, so that no one call runs long. Returns whether
  // every page has been read.
  async step(budget) {
    if (!doc) throw new Error("No PDF is loaded");
    if (extract) return true;
    const until = Date.now() + budget;
    while (!progress.clipped && progress.next <= doc.numPages && Date.now() < until) {
      await readPage(doc, progress);
    }
    return progress.clipped || progress.next > doc.numPages;
  },
  // The document's facts and blocks, once `step` has read every page, worked out once, with whether
  // the page is loaded and drawn now, which can change between reads.
  async read() {
    if (!doc) throw new Error("No PDF is loaded");
    if (!extract) {
      if (!progress.clipped && progress.next <= doc.numPages) throw new Error("Pages are still unread");
      extract = await finish(doc, url, progress);
      progress = null;
    }
    return Object.assign(
      { readyState: document.readyState, visibility: document.visibilityState },
      extract,
    );
  },
  drop: drop,
};
