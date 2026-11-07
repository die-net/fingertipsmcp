"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { h, page } = require("./fakedom.js");
const { pageRead } = require("./scripts.js");

const PROSE = "This sentence is long enough to be part of a paragraph of real prose. ".repeat(6);

// Every link the blocks hold, in order.
function links(blocks) {
  const out = [];
  for (const block of blocks) {
    for (const run of block.r || []) if (run.h) out.push(run.h);
    for (const cell of block.c || []) for (const run of cell.r || []) if (run.h) out.push(run.h);
  }
  return out;
}

// A search results page: each result a link and a line about it, in a list.
function listing() {
  const items = [];
  for (let n = 1; n <= 20; n++) {
    items.push(h("li", null, h("a", { href: "/item/" + n }, "Item " + n), h("p", null, PROSE)));
  }
  return page({}, [], h("body", null, h("h1", null, "Results"), h("ul", null, ...items)));
}

// What Readability picks on a listing: a fragment with a few stray links and none of the results.
const fragment = () => ({
  content: h("div", null, h("p", null, "Sort by ", h("a", { href: "/sort" }, "price"))),
  textContent: "Sort by price",
});

test("a listing is read whole in article scope", () => {
  const read = pageRead(listing(), { scope: "article" }, fragment());
  assert.equal(read.extracted, "page");
  assert.equal(links(read.blocks).length, 20);
});

test("an article is read through Readability", () => {
  const body = h(
    "body",
    null,
    h("nav", null, h("a", { href: "/home" }, "Home")),
    h("article", null, h("h1", null, "Title"), h("p", null, PROSE), h("p", null, PROSE)),
  );
  const article = { content: h("div", null, h("p", null, "The article")), textContent: "x" };
  const read = pageRead(page({}, [], body), { scope: "article" }, article);
  assert.equal(read.extracted, "article");
  assert.deepEqual(read.blocks, [{ k: "p", r: [{ t: "The article" }] }]);
});

test("a table cell keeps its links and styles", () => {
  const table = h(
    "table",
    null,
    h("tbody", null,
      h("tr", null, h("th", null, "Part"), h("th", null, "Sheet")),
      h("tr", null,
        h("td", null, h("p", null, "RP2350"), h("p", null, h("b", null, "new"))),
        h("td", null, h("a", { href: "/rp2350.pdf" }, "datasheet"), h("span", { "data-hidden": "" }, "x")),
      ),
    ),
  );
  const read = pageRead(page({}, [], h("body", null, table)), { scope: "page" });
  assert.deepEqual(read.blocks, [
    { k: "tr", t: 0, c: [[{ t: "Part" }], [{ t: "Sheet" }]], th: true },
    {
      k: "tr",
      t: 0,
      c: [
        [{ t: " RP2350  " }, { t: "new", s: "b" }, { t: " " }],
        [{ t: "datasheet", h: "https://example.com/rp2350.pdf" }],
      ],
      th: false,
    },
  ]);
});

test("a selector that is not CSS says so", () => {
  assert.deepEqual(pageRead(listing(), { scope: "page", selector: "a[[" }), { badSelector: true });
  assert.deepEqual(pageRead(listing(), { scope: "page", selector: "table" }), { missing: true });
});

test("prose in a part the page names as not its article does not count", () => {
  const body = h(
    "body",
    null,
    h("div", { class: "comments" }, h("p", null, PROSE), h("p", null, PROSE), h("p", null, PROSE)),
  );
  const read = pageRead(page({}, [], body), { scope: "article" }, fragment());
  assert.equal(read.extracted, "page");
});

test("Readability reads the page without what the page hides", () => {
  const body = h(
    "body",
    null,
    h("article", null, h("p", null, PROSE), h("p", { "data-hidden": "" }, "Hidden by a stylesheet")),
    h("script", { type: "application/ld+json", "data-hidden": "" }, '{"@type":"Article"}'),
  );
  let given = null;
  const read = pageRead(page({}, [], body), { scope: "article" }, (doc) => {
    given = doc;
    return { content: doc.body, textContent: doc.body.textContent };
  });
  assert.equal(read.extracted, "article");
  const text = read.blocks.flatMap((b) => b.r.map((r) => r.t)).join("");
  assert.ok(!text.includes("Hidden by a stylesheet"), text);
  assert.ok(given.querySelector('script[type="application/ld+json"]'), "JSON-LD left out");
});

test("a Readability failure is named, and the page is read whole", () => {
  const body = h("body", null, h("article", null, h("p", null, PROSE), h("p", null, PROSE)));
  const read = pageRead(page({}, [], body), { scope: "article" }, () => {
    throw new TypeError("Cannot read properties of null");
  });
  assert.equal(read.extracted, "page");
  assert.equal(read.articleError, "TypeError: Cannot read properties of null");
});

// MDN draws its main content through an element with display: contents, which has no box of its
// own, so Chrome's checkVisibility says it is not visible. Both scopes left its whole text out.
test("content in a display: contents element is read", () => {
  const body = h("body", null, h("main", { "data-contents": "" }, h("p", null, PROSE), h("p", null, PROSE)));
  const whole = pageRead(page({}, [], body), { scope: "page" });
  assert.ok(whole.blocks.length >= 2, JSON.stringify(whole.blocks));
  let given = null;
  const read = pageRead(page({}, [], body), { scope: "article" }, (doc) => {
    given = doc;
    return { content: doc.body, textContent: doc.body.textContent };
  });
  assert.equal(read.extracted, "article");
  assert.ok(given.body.textContent.includes(PROSE.trim()), "Readability was not given the content");
});
