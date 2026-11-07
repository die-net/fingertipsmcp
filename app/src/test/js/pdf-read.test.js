"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { pdfRead } = require("./scripts.js");

function line(y, text) {
  return { page: 1, y: y, x: 50, size: 10, runs: [{ t: text }] };
}

// A page of a statement: body text, a table of figures whose last row sits wherever the table
// ends, and the page number at the foot.
function statement(n) {
  return {
    lines: [
      line(700, "Account statement for the quarter"),
      line(600, "The figures below are in dollars, as the bank reported them"),
      line(500, "Deposits withdrawals and the balance at the end of each month"),
      line(80 + n * 20, "1200 340 " + (n * 100 + 7)),
      line(30, String(n)),
    ],
  };
}

test("a running footer goes, and rows of figures above it stay", () => {
  const pdf = pdfRead();
  const pages = [1, 2, 3, 4].map(statement);
  pdf.dropRunning(pages);
  for (const [i, page] of pages.entries()) {
    const texts = page.lines.map((l) => l.runs[0].t);
    assert.ok(!texts.includes(String(i + 1)), "page number " + (i + 1) + " is left");
    assert.ok(texts.some((t) => t.startsWith("1200 340")), "figures on page " + (i + 1) + " went");
  }
});
