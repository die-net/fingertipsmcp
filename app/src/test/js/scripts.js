// Runs FingertipsMCP's page scripts under node, on a fakedom page, as Chrome runs them in an isolated
// world: page-read.js as the body of a function given its options, after page-pending.js and
// page-data.js, with Readability replaced by a stub that returns the article a test gives.
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const ASSETS = path.join(__dirname, "..", "..", "main", "assets");

function asset(name) {
  return fs.readFileSync(path.join(ASSETS, name), "utf8");
}

// A copy made in this realm, so assert.deepStrictEqual compares values, not prototypes.
function plain(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function context(globals) {
  return vm.createContext(Object.assign({ DOMException: DOMException, URL: URL }, globals));
}

// What pageData(want, budget) returns on the page.
function pageData(globals, want, budget) {
  const ctx = context(globals);
  vm.runInContext(asset("page-data.js"), ctx);
  ctx.want = want;
  ctx.budget = budget;
  return plain(vm.runInContext("pageData(want, budget)", ctx));
}

// What page-read.js returns on the page with options. article, when given, is what the
// Readability stub's parse() returns: {content, textContent}, or a function of the document
// Readability was given that returns one or throws.
function pageRead(globals, options, article) {
  const ctx = context(Object.assign({ __article: article === undefined ? null : article }, globals));
  const readability =
    "function Readability(doc, options) { this.doc = doc; }\n" +
    "Readability.prototype.parse = function () {\n" +
    '  if (typeof __article === "function") return __article(this.doc);\n' +
    "  return __article;\n" +
    "};\n";
  const source =
    readability + asset("page-pending.js") + "\n" + asset("page-data.js") + "\n" + asset("page-read.js");
  const script = "(function (options) {\n" + source + "\n})(" + JSON.stringify(options) + ")";
  return plain(vm.runInContext(script, ctx));
}

// pdf-read.js in a context of its own, whose functions a test can call. pdf.js is not loaded, so
// only the functions that work on lines already taken from a page can run.
function pdfRead() {
  const ctx = context({});
  vm.runInContext(asset("pdf-read.js"), ctx);
  return ctx;
}

module.exports = { asset, pageData, pageRead, pdfRead, plain };
