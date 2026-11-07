// Finds the JSON a page embeds for its scripts, for browser_read and browser_find with scope
// data. Runs in an isolated world. Each blob comes back as JSON text: a JSON script tag as the
// page wrote it, and an object or array literal in code turned into JSON here, so PhoneAPI never
// parses JavaScript and never runs any. pageData(want, budget) lists the first DATA_MAX_BLOBS
// blobs, with the text of those want names, in page order, while their text fits in budget
// characters, and counts the blobs past them as more; want null lists names and sizes only, and
// want "*" wants every blob.

const DATA_MIN_ASSIGNED = 256;
const DATA_MAX_BLOBS = 50;
const DATA_MAX_DEPTH = 200;

// An assignment whose value may be data: window.NAME =, self.NAME =, this.NAME =, each also with
// more .NAME after it, as window.App.state =, window["NAME"] =, var, let, or const NAME =, or a
// bare NAME =, or NAME.NAME =, that starts a statement, as older Next.js writes __NEXT_DATA__ and
// App.state = does.
const DATA_ASSIGN =
  /(?:\b(?:window|self|globalThis|this)\s*\.\s*([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*)|\b(?:window|self|globalThis)\s*\[\s*["']([^"'\]]+)["']\s*\]|\b(?:var|let|const)\s+([A-Za-z_$][\w$]*)|(?:^|[;{}\n])\s*([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*))\s*=\s*/dg;
// The global or object a member assignment starts from, which a blob's name leaves out.
const DATA_GLOBAL = /^(?:window|self|globalThis|this)\./;
// A call: a name, dotted or not, then an open parenthesis, such as Search.results.load(.
const DATA_CALLEE = /([A-Za-z_$][\w$]*(?:\s*\.\s*[A-Za-z_$][\w$]*)*)\s*\(/g;
// An argument that is a name, a number, or a dotted name, stepped over to reach a literal.
const DATA_PLAIN_ARG = /^[-+]?[\w$.]+/;
const DATA_NAME_START = /^[\p{L}\p{Nl}$_]$/u;
const DATA_NAME_PART = /^[\p{L}\p{Nl}\p{Mn}\p{Mc}\p{Nd}\p{Pc}$_‌‍]$/u;
// What literalJson throws when its text is not a literal holding data.
const DATA_NOT_LITERAL = {};
const DATA_JSON_TYPE = /(^|[/+])json$/;
const DATA_SCRIPT_TYPE = /^(text|application)\/(x-)?(javascript|ecmascript)$/;

function pageData(want, budget) {
  const blobs = [];
  const taken = new Set();
  const counts = new Map();
  let more = 0;

  // Adds a blob whose JSON text is text, or counts it when the page already has as many as are
  // read.
  function add(name, source, text) {
    if (text === null) return;
    if (blobs.length >= DATA_MAX_BLOBS) {
      more++;
      return;
    }
    // A name is a path's first segment, so it has no slash, and no two blobs share one, even when
    // the page has ids x, x, and x-2.
    const base = name.replace(/[/ ]/g, "_");
    name = base;
    for (let n = 2; taken.has(name); n++) name = base + "-" + n;
    taken.add(name);
    blobs.push({ name: name, source: source, text: text });
  }

  // Adds the literals a script assigns or passes to a call, when they are large enough to be data
  // rather than settings. A string given to JSON.parse is read as JSON, as the page reads it.
  function addScript(script) {
    for (const found of scriptLiterals(script)) {
      if (found.raw.length < DATA_MIN_ASSIGNED) continue;
      add(found.name, found.source, found.json ? checkedJson(found.raw) : literalJson(found.raw));
    }
  }

  const type = (document.contentType || "").toLowerCase();
  const body = document.body;
  const pre = document.querySelector("body > pre");
  const raw = pre ? pre.textContent : body ? body.textContent : "";
  if (DATA_JSON_TYPE.test(type) || (type === "text/plain" && /^\s*[[{]/.test(raw))) {
    add("response", "the response body, as JSON", checkedJson(raw.trim()));
  } else if (DATA_SCRIPT_TYPE.test(type)) {
    addScript(raw);
  } else {
    for (const script of document.querySelectorAll("script:not([src])")) {
      const kind = (script.type || "").trim().toLowerCase();
      if (kind.includes("json")) {
        const [name, source] = scriptName(script, kind, counts);
        add(name, source, checkedJson(script.textContent.trim()));
      } else if (kind === "" || kind.includes("javascript") || kind === "module") {
        addScript(script.textContent);
      }
    }
  }

  let left = budget || 0;
  const listed = blobs.map((blob) => {
    const out = { name: blob.name, source: blob.source, chars: blob.text.length };
    if (want === "*" || (want && want.includes(blob.name))) {
      if (blob.text.length <= left) {
        out.text = blob.text;
        left -= blob.text.length;
      } else {
        out.omitted = true;
      }
    }
    return out;
  });
  return { blobs: listed, more: more };
}

// What a data read returns: the page's facts, and its blobs, with the text of those want names, and
// how many more it has past those read.
function dataPage(want, budget) {
  const nav = performance.getEntriesByType("navigation")[0];
  const found = pageData(want, budget);
  return {
    title: document.title || null,
    url: location.href,
    readyState: document.readyState,
    visibility: document.visibilityState,
    status: (nav && nav.responseStatus) || 0,
    blobs: found.blobs,
    more: found.more,
  };
}

// Names a JSON script tag by its id, or by its type and count.
function scriptName(script, kind, counts) {
  let source = "script[type=" + kind + "]";
  let name;
  if (script.id) {
    name = script.id;
    source = "script#" + script.id;
  } else {
    const which = kind.includes("ld+json") ? "ld+json" : "json";
    counts.set(which, (counts.get(which) || 0) + 1);
    name = which + "-" + counts.get(which);
  }
  const url = script.getAttribute("data-url");
  if (url) source += " data-url=" + url;
  return [name, source];
}

// text when it is JSON, else null. Malformed JSON in a page is common, and JSON.parse says so with
// a SyntaxError.
function checkedJson(text) {
  try {
    JSON.parse(text);
    return text;
  } catch (e) {
    if (e instanceof SyntaxError) return null;
    throw e;
  }
}

// The object and array literals a script assigns or passes to a call, in script order.
function scriptLiterals(script) {
  const out = assignments(script).concat(callArguments(script));
  return out.sort((a, b) => a.at - b.at);
}

function squeeze(text, sep) {
  return text.split(/\s+/).filter(Boolean).join(sep);
}

// Object and array literals assigned in a script, and strings given to JSON.parse.
function assignments(script) {
  const out = [];
  DATA_ASSIGN.lastIndex = 0;
  let m;
  while ((m = DATA_ASSIGN.exec(script)) !== null) {
    let name;
    let source;
    const start = m.index;
    const assigned = start + m[0].length;
    if (m[1] !== undefined) {
      name = squeeze(m[1], "");
      source = squeeze(script.slice(start, m.indices[1][1]), "");
    } else if (m[2] !== undefined) {
      name = m[2];
      source = squeeze(script.slice(start, script.indexOf("]", m.indices[2][1]) + 1), "");
    } else if (m[3] !== undefined) {
      name = m[3];
      source = squeeze(script.slice(start, m.indices[3][1]), " ");
    } else {
      // A statement that starts with this.NAME, or window.NAME, is named as the forms above are.
      source = squeeze(m[4], "");
      name = source.replace(DATA_GLOBAL, "");
      if (name === "var" || name === "let" || name === "const") continue;
    }
    const rest = script.slice(assigned, assigned + 16);
    const parse = /^JSON\.parse\(\s*/.exec(rest);
    if (parse) {
      const decoded = jsString(script, assigned + parse[0].length);
      if (decoded) {
        const found = { name: name, source: source + " = JSON.parse(…)", raw: decoded.value };
        out.push(Object.assign(found, { at: assigned, json: true }));
      }
      continue;
    }
    const end = balanced(script, assigned);
    if (end > 0) {
      out.push({ name: name, source: source, raw: script.slice(assigned, end), at: assigned });
    }
  }
  return out;
}

// Object and array literals passed to a call, as a JSONP answer wraps its data. Only strings,
// numbers, and names are stepped over to reach one, so the scan never runs far into code, and a
// literal followed by anything but a comma or the close is an expression, not data.
function callArguments(script) {
  const out = [];
  DATA_CALLEE.lastIndex = 0;
  const space = (at) => {
    while (at < script.length && " \t\r\n".includes(script[at])) at++;
    return at;
  };
  let m;
  while ((m = DATA_CALLEE.exec(script)) !== null) {
    const callee = squeeze(m[1], "");
    let pos = m.index + m[0].length;
    for (;;) {
      pos = space(pos);
      if (pos >= script.length || script[pos] === ")") break;
      const c = script[pos];
      let end;
      const literal = c === "{" || c === "[";
      if (literal) {
        end = balanced(script, pos);
      } else if (c === '"' || c === "'" || c === "`") {
        end = stringEnd(script, pos);
      } else {
        const plain = DATA_PLAIN_ARG.exec(script.slice(pos, pos + 256));
        end = plain ? pos + plain[0].length : 0;
      }
      if (end <= 0) break;
      const start = pos;
      pos = space(end);
      if (pos >= script.length || (script[pos] !== "," && script[pos] !== ")")) break;
      if (literal) {
        const raw = script.slice(start, end);
        out.push({ name: callee, source: callee + "(…)", raw: raw, at: start });
      }
      if (script[pos] === ")") break;
      pos++;
    }
  }
  return out;
}

// Where the string literal that starts at i ends, or 0 when it does not close.
function stringEnd(s, i) {
  const q = s[i];
  for (let j = i + 1; j < s.length; j++) {
    const c = s[j];
    if (c === "\\") j++;
    else if (c === q) return j + 1;
    else if (c === "\n" && q !== "`") return 0;
  }
  return 0;
}

// Where the {…} or […] that starts at i ends, skipping brackets inside strings, or 0 when it
// does not close.
function balanced(s, i) {
  if (s[i] !== "{" && s[i] !== "[") return 0;
  let depth = 0;
  let quote = "";
  for (let j = i; j < s.length; j++) {
    const c = s[j];
    if (quote) {
      if (c === "\\") j++;
      else if (c === quote) quote = "";
      continue;
    }
    if (c === '"' || c === "'" || c === "`") quote = c;
    else if (c === "{" || c === "[") depth++;
    else if (c === "}" || c === "]") {
      depth--;
      if (depth === 0) return j + 1;
    }
  }
  return 0;
}

// Decodes the string literal in single or double quotes that starts at i, as {value, end}, or
// null when it does not close or has an escape strict code refuses. A lone surrogate becomes
// U+FFFD.
function jsString(s, i) {
  const q = s[i];
  if (q !== '"' && q !== "'") return null;
  let value = "";
  let run = i + 1;
  for (let j = i + 1; j < s.length; ) {
    const c = s[j];
    if (c === q) return { value: value + s.slice(run, j), end: j + 1 };
    if (c === "\n" || c === "\r") return null;
    if (c !== "\\") {
      j++;
      continue;
    }
    value += s.slice(run, j);
    const escape = jsEscape(s, j);
    if (!escape) return null;
    value += escape.value;
    j = escape.end;
    run = j;
  }
  return null;
}

const DATA_ESCAPES = { n: "\n", t: "\t", r: "\r", b: "\b", f: "\f", v: "\v" };

// What the escape at i stands for, as {value, end}, or null for one strict code refuses.
function jsEscape(s, i) {
  const e = s[i + 1];
  if (e === undefined) return null;
  if (DATA_ESCAPES[e] !== undefined) return { value: DATA_ESCAPES[e], end: i + 2 };
  if (e === "0") return /[0-9]/.test(s[i + 2] || "") ? null : { value: "\0", end: i + 2 };
  if (/[1-9]/.test(e)) return null;
  if (e === "x") {
    const hex = s.slice(i + 2, i + 4);
    if (!/^[0-9a-fA-F]{2}$/.test(hex)) return null;
    return { value: String.fromCharCode(parseInt(hex, 16)), end: i + 4 };
  }
  if (e === "u") {
    const first = unicodeEscape(s, i);
    if (!first) return null;
    let code = first.code;
    let end = first.end;
    if (code >= 0xd800 && code <= 0xdbff) {
      const low = unicodeEscape(s, end);
      if (low && low.code >= 0xdc00 && low.code <= 0xdfff) {
        code = 0x10000 + ((code - 0xd800) << 10) + (low.code - 0xdc00);
        end = low.end;
      } else {
        code = 0xfffd;
      }
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      code = 0xfffd;
    }
    return { value: String.fromCodePoint(code), end: end };
  }
  if (e === "\r") return { value: "", end: s[i + 2] === "\n" ? i + 3 : i + 2 };
  if (e === "\n" || e === " " || e === " ") return { value: "", end: i + 2 };
  const code = s.codePointAt(i + 1);
  const size = code > 0xffff ? 2 : 1;
  return { value: s.slice(i + 1, i + 1 + size), end: i + 1 + size };
}

// The \uXXXX or \u{X…} escape at i, as {code, end}.
function unicodeEscape(s, i) {
  if (s[i] !== "\\" || s[i + 1] !== "u") return null;
  if (s[i + 2] === "{") {
    const close = s.indexOf("}", i + 3);
    const hex = close < 0 ? "" : s.slice(i + 3, close);
    if (!/^[0-9a-fA-F]{1,6}$/.test(hex)) return null;
    const code = parseInt(hex, 16);
    return code > 0x10ffff ? null : { code: code, end: close + 1 };
  }
  const hex = s.slice(i + 2, i + 6);
  return /^[0-9a-fA-F]{4}$/.test(hex) ? { code: parseInt(hex, 16), end: i + 6 } : null;
}

// The JSON a JavaScript literal holding data stands for: JSON, or an object or array literal as
// code writes one, with keys unquoted or quoted either way, single-quoted strings, JavaScript's
// escapes, and trailing commas. Anything else, such as a function, a name, an expression,
// undefined, a number JSON cannot write, a comment, or a hole in an array, gives null, so
// nothing is guessed at. Numbers keep their text, so no digits are lost.
function literalJson(s) {
  const out = [];
  let i = 0;

  function space() {
    while (i < s.length && " \t\n\r\v\f".includes(s[i])) i++;
  }

  // Whether the character at j can be in an identifier, and at its start when first is set.
  // ASCII is decided without a regular expression, since nearly every name is ASCII.
  function namePart(j, first) {
    const code = s.charCodeAt(j);
    if (code < 128) {
      const letter = (code | 32) >= 97 && (code | 32) <= 122;
      return letter || code === 36 || code === 95 || (!first && code >= 48 && code <= 57);
    }
    const ch = String.fromCodePoint(s.codePointAt(j));
    return (first ? DATA_NAME_START : DATA_NAME_PART).test(ch);
  }

  function name() {
    const start = i;
    while (i < s.length && namePart(i, i === start)) i += s.codePointAt(i) > 0xffff ? 2 : 1;
    return s.slice(start, i);
  }

  // Whether an identifier character or a dot follows, which makes a number or a key something
  // JSON has no form for, such as 0x1f, 1n, or 1_000.
  function nameContinues() {
    return i < s.length && (s[i] === "." || namePart(i, false));
  }

  function digits() {
    while (i < s.length && s[i] >= "0" && s[i] <= "9") i++;
  }

  function number() {
    const start = i;
    if (s[i] === "-") i++;
    if (s[i] === "0") i++;
    else if (s[i] >= "1" && s[i] <= "9") digits();
    else return false;
    if (s[i] === "." && s[i + 1] >= "0" && s[i + 1] <= "9") {
      i++;
      digits();
    }
    if (s[i] === "e" || s[i] === "E") {
      i++;
      if (s[i] === "+" || s[i] === "-") i++;
      if (!(s[i] >= "0" && s[i] <= "9")) return false;
      digits();
    }
    if (nameContinues()) return false;
    out.push(s.slice(start, i));
    return true;
  }

  // An object key: a string, a name, or an integer, which JavaScript turns into the string of
  // its digits. Other numbers are refused, since their string is not their text.
  function key() {
    const c = s[i];
    if (c === '"' || c === "'") {
      const decoded = jsString(s, i);
      if (!decoded) return null;
      i = decoded.end;
      return decoded.value;
    }
    if (c >= "0" && c <= "9") {
      const start = i;
      digits();
      const text = s.slice(start, i);
      if ((text.length > 1 && text[0] === "0") || text.length > 15 || nameContinues()) return null;
      return text;
    }
    const word = name();
    return word === "" ? null : word;
  }

  // Reads the comma or the close that follows a member; true when it was the close.
  function separator(closer) {
    space();
    if (s[i] === closer) {
      i++;
      return true;
    }
    if (s[i] !== ",") throw DATA_NOT_LITERAL;
    i++;
    return false;
  }

  function value(depth) {
    if (depth > DATA_MAX_DEPTH) throw DATA_NOT_LITERAL;
    space();
    const c = s[i];
    if (c === "{") {
      i++;
      out.push("{");
      let first = true;
      for (;;) {
        space();
        if (s[i] === "}") {
          i++;
          break;
        }
        const k = key();
        if (k === null) throw DATA_NOT_LITERAL;
        space();
        if (s[i] !== ":") throw DATA_NOT_LITERAL;
        i++;
        if (!first) out.push(",");
        first = false;
        out.push(JSON.stringify(k), ":");
        value(depth + 1);
        if (separator("}")) break;
      }
      out.push("}");
    } else if (c === "[") {
      i++;
      out.push("[");
      let first = true;
      for (;;) {
        space();
        if (s[i] === "]") {
          i++;
          break;
        }
        if (!first) out.push(",");
        first = false;
        value(depth + 1);
        if (separator("]")) break;
      }
      out.push("]");
    } else if (c === '"' || c === "'") {
      const decoded = jsString(s, i);
      if (!decoded) throw DATA_NOT_LITERAL;
      i = decoded.end;
      out.push(JSON.stringify(decoded.value));
    } else if (c === "-" || (c >= "0" && c <= "9")) {
      if (!number()) throw DATA_NOT_LITERAL;
    } else {
      const word = name();
      if (word !== "true" && word !== "false" && word !== "null") throw DATA_NOT_LITERAL;
      out.push(word);
    }
  }

  try {
    value(0);
    space();
    return i < s.length ? null : out.join("");
  } catch (e) {
    if (e === DATA_NOT_LITERAL) return null;
    throw e;
  }
}
