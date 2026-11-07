"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { h, page } = require("./fakedom.js");
const { pageData, pageRead } = require("./scripts.js");

function scripts(...tags) {
  return page({}, [], h("body", null, h("p", null, "Hello there"), ...tags));
}

test("a malformed JSON script tag is left out, not thrown", () => {
  const blobs = pageData(
    scripts(
      h("script", { type: "application/ld+json" }, '{"a":1,}'),
      h("script", { type: "application/json", id: "empty" }, ""),
      h("script", { type: "application/json", id: "good" }, '{"b":2}'),
    ),
    null,
  ).blobs;
  assert.deepEqual(blobs, [{ name: "good", source: "script#good", chars: 7 }]);
});

test("a string given to JSON.parse that is not JSON is left out", () => {
  const broken = "{" + '\\"k\\": 1, '.repeat(40) + "oops";
  const blobs = pageData(scripts(h("script", null, 'window.cfg = JSON.parse("' + broken + '");')), null).blobs;
  assert.deepEqual(blobs, []);
});

test("a text document that starts like JSON but is not has no blob", () => {
  const doc = page({ contentType: "text/plain" }, [], h("body", null, h("pre", null, "{not json")));
  assert.deepEqual(pageData(doc, null).blobs, []);
});

test("a JSON response is one blob", () => {
  const doc = page({ contentType: "application/json" }, [], h("body", null, h("pre", null, ' {"a":[1]} ')));
  assert.deepEqual(pageData(doc, "*", 100).blobs, [
    { name: "response", source: "the response body, as JSON", chars: 9, text: '{"a":[1]}' },
  ]);
});

test("a read of a page with a malformed JSON script still returns its text", () => {
  const doc = scripts(h("script", { type: "application/ld+json" }, '{"a":1,}'));
  const read = pageRead(doc, { scope: "page" });
  assert.deepEqual(read.data, []);
  assert.deepEqual(read.blocks, [{ k: "p", r: [{ t: "Hello there" }] }]);
});

test("blobs past the most a read takes are counted", () => {
  const tags = [];
  for (let n = 0; n < 80; n++) tags.push(h("script", { type: "application/json" }, "[" + n + "]"));
  const found = pageData(scripts(...tags), null);
  assert.equal(found.blobs.length, 50);
  assert.equal(found.more, 30);
});

test("blob names are unique, whatever ids the page uses", () => {
  const json = (id) => h("script", { type: "application/json", id: id }, "[1]");
  const names = pageData(scripts(json("x"), json("x"), json("x-2")), null).blobs.map((b) => b.name);
  assert.deepEqual(names, ["x", "x-2", "x-2-2"]);
});

test("a member assignment is read, however deep", () => {
  const big = JSON.stringify({ items: Array.from({ length: 40 }, (_, n) => ({ id: n })) });
  const code = [
    "window.App.state = " + big + ";",
    "App.config = " + big + ";",
    "function F() { this.data = " + big + "; }",
  ].join("\n");
  const blobs = pageData(scripts(h("script", null, code)), null).blobs;
  assert.deepEqual(
    blobs.map((b) => [b.name, b.source]),
    [
      ["App.state", "window.App.state"],
      ["App.config", "App.config"],
      ["data", "this.data"],
    ],
  );
});
