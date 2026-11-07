// A small DOM for running the page scripts under node: elements, text, attributes, tables, and
// the CSS selectors the scripts use (tag, #id, .class, [attr], [attr="v"], [attr*="v" i], :not(),
// descendant and child combinators, and lists). A selector it cannot parse throws a DOMException
// named SyntaxError, as Chrome does for one that is not valid CSS.
"use strict";

class Text {
  constructor(data) {
    this.nodeType = 3;
    this.data = data;
    this.parentNode = null;
  }
  get textContent() {
    return this.data;
  }
}

class Element {
  constructor(tag, attrs, children) {
    this.nodeType = 1;
    this.tagName = tag.toUpperCase();
    this.attrs = Object.assign({}, attrs || {});
    this.childNodes = [];
    this.parentNode = null;
    this.style = { display: this.attrs["data-hidden"] !== undefined ? "none" : "" };
    for (const child of children || []) this.append(child);
  }
  append(child) {
    const node = typeof child === "string" ? new Text(child) : child;
    node.parentNode = this;
    this.childNodes.push(node);
    return node;
  }
  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null;
  }
  hasAttribute(name) {
    return this.getAttribute(name) !== null;
  }
  get id() {
    return this.getAttribute("id") || "";
  }
  get type() {
    return this.getAttribute("type") || "";
  }
  get rel() {
    return this.getAttribute("rel") || "";
  }
  get title() {
    return this.getAttribute("title") || "";
  }
  get hidden() {
    return this.hasAttribute("hidden");
  }
  get href() {
    const href = this.getAttribute("href");
    if (href === null) return "";
    return new URL(href, this.ownerBase()).href;
  }
  ownerBase() {
    let node = this;
    while (node.parentNode) node = node.parentNode;
    return node.baseUrl || "https://example.com/";
  }
  get children() {
    return this.childNodes.filter((n) => n.nodeType === 1);
  }
  get textContent() {
    return this.childNodes.map((n) => n.textContent).join("");
  }
  get innerText() {
    return this.textContent;
  }
  get rows() {
    if (this.tagName !== "TABLE") return undefined;
    return this.descendants().filter((el) => el.tagName === "TR");
  }
  get cells() {
    if (this.tagName !== "TR") return undefined;
    return this.children.filter((el) => el.tagName === "TD" || el.tagName === "TH");
  }
  closest(selector) {
    const list = parseSelector(selector);
    for (let node = this; node && node.nodeType === 1; node = node.parentNode) {
      if (list.some((complex) => matches(node, complex))) return node;
    }
    return null;
  }
  contains(other) {
    for (let node = other; node; node = node.parentNode) if (node === this) return true;
    return false;
  }
  checkVisibility() {
    for (let node = this; node && node.nodeType === 1; node = node.parentNode) {
      if (node.style.display === "none") return false;
    }
    return true;
  }
  getClientRects() {
    return this.checkVisibility() ? [{}] : [];
  }
  descendants() {
    const out = [];
    const walk = (node) => {
      for (const child of node.childNodes) {
        if (child.nodeType !== 1) continue;
        out.push(child);
        walk(child);
      }
    };
    walk(this);
    return out;
  }
  querySelectorAll(selector) {
    const list = parseSelector(selector);
    return this.descendants().filter((el) => list.some((complex) => matches(el, complex)));
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  cloneNode() {
    throw new Error("fakedom: cloneNode is not supported");
  }
}

class Document extends Element {
  constructor(options, children) {
    super("#document", {}, children);
    this.nodeType = 9;
    this.baseUrl = options.url || "https://example.com/";
    this.contentType = options.contentType || "text/html";
    this.docTitle = options.title || "";
    this.readyState = "complete";
    this.visibilityState = "visible";
  }
  get title() {
    return this.docTitle;
  }
  get documentElement() {
    return this.children[0] || null;
  }
  get body() {
    return this.querySelector("body");
  }
  get links() {
    return this.querySelectorAll("a[href]");
  }
  get forms() {
    return this.querySelectorAll("form");
  }
  cloneNode() {
    return this;
  }
}

function syntaxError(selector) {
  return new DOMException(
    "Failed to execute 'querySelectorAll' on 'Document': '" + selector + "' is not a valid selector.",
    "SyntaxError",
  );
}

// A selector list, as complex selectors, each a list of {compound, combinator} from left to right.
function parseSelector(selector) {
  let i = 0;
  const s = selector;
  const fail = () => {
    throw syntaxError(selector);
  };
  const space = () => {
    while (i < s.length && /\s/.test(s[i])) i++;
  };
  const ident = () => {
    const m = /^-?[A-Za-z_][\w-]*/.exec(s.slice(i));
    if (!m) fail();
    i += m[0].length;
    return m[0];
  };
  const quoted = () => {
    const q = s[i];
    const end = s.indexOf(q, i + 1);
    if (end < 0) fail();
    const value = s.slice(i + 1, end);
    i = end + 1;
    return value;
  };
  function compound() {
    const parts = [];
    for (;;) {
      const c = s[i];
      if (c === "*") {
        i++;
      } else if (c !== undefined && /[A-Za-z]/.test(c)) {
        const tag = ident().toUpperCase();
        parts.push((el) => el.tagName === tag);
      } else if (c === "#") {
        i++;
        const id = ident();
        parts.push((el) => el.id === id);
      } else if (c === ".") {
        i++;
        const name = ident();
        parts.push((el) => (el.getAttribute("class") || "").split(/\s+/).includes(name));
      } else if (c === "[") {
        i++;
        space();
        const name = ident();
        space();
        let op = null;
        let value = null;
        let fold = false;
        const m = /^[*^$]?=/.exec(s.slice(i));
        if (m) {
          op = m[0];
          i += op.length;
          space();
          value = s[i] === '"' || s[i] === "'" ? quoted() : ident();
          space();
          if (s[i] === "i" || s[i] === "I") {
            fold = true;
            i++;
            space();
          }
        }
        if (s[i] !== "]") fail();
        i++;
        parts.push((el) => {
          let have = el.getAttribute(name);
          if (have === null) return false;
          if (op === null) return true;
          let want = value;
          if (fold) {
            have = have.toLowerCase();
            want = want.toLowerCase();
          }
          if (op === "=") return have === want;
          if (op === "*=") return have.includes(want);
          if (op === "^=") return have.startsWith(want);
          return have.endsWith(want);
        });
      } else if (s.startsWith(":not(", i)) {
        i += 5;
        const inner = compound();
        if (s[i] !== ")") fail();
        i++;
        parts.push((el) => !inner(el));
      } else {
        break;
      }
    }
    if (parts.length === 0 && s[i - 1] !== "*") fail();
    return (el) => parts.every((p) => p(el));
  }
  const list = [];
  space();
  for (;;) {
    const complex = [];
    let combinator = null;
    for (;;) {
      complex.push({ test: compound(), combinator: combinator });
      const before = i;
      space();
      if (s[i] === ">") {
        i++;
        space();
        combinator = ">";
      } else if (i < s.length && s[i] !== "," && i > before) {
        combinator = " ";
      } else {
        break;
      }
    }
    list.push(complex);
    if (i >= s.length) break;
    if (s[i] !== ",") fail();
    i++;
    space();
  }
  return list;
}

// Whether el matches the complex selector, read from its last compound back to its first.
function matches(el, complex, at) {
  const index = at === undefined ? complex.length - 1 : at;
  const part = complex[index];
  if (!part.test(el)) return false;
  if (index === 0) return true;
  if (part.combinator === ">") {
    const parent = el.parentNode;
    return !!parent && parent.nodeType === 1 && matches(parent, complex, index - 1);
  }
  for (let node = el.parentNode; node && node.nodeType === 1; node = node.parentNode) {
    if (matches(node, complex, index - 1)) return true;
  }
  return false;
}

// Builds an element: h("p", {class: "x"}, "text", h("b", null, "bold")).
function h(tag, attrs, ...children) {
  return new Element(tag, attrs, children);
}

// A document with html, head, and body, and the globals the page scripts read.
function page(options, head, body) {
  const doc = new Document(options, [h("html", null, h("head", null, ...head), body)]);
  return {
    document: doc,
    location: { href: doc.baseUrl, protocol: new URL(doc.baseUrl).protocol },
    performance: {
      getEntriesByType: () => [{ responseStatus: options.status || 200, name: doc.baseUrl }],
    },
    getComputedStyle: () => ({ visibility: "visible" }),
  };
}

module.exports = { h, page, Element, Document };
