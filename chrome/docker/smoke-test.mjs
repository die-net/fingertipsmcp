#!/usr/bin/env node
// smoke-test checks a running FingertipsMCP for Chrome container the way a client uses it: the
// device answers, a session opens a tab and reads a public page, a screenshot is a PNG, closing the
// session's last tab leaves Chrome running, and a video viewer gets a header and a frame. It logs
// the renderer WebGL names. CI runs it against the image it just built; see docs/development.md.
//
// Usage: chrome/docker/smoke-test.mjs PAIRING_JSON
// Needs Node 22 or later, for fetch and WebSocket. Exits 0 when every check passes and 1 at the
// first that fails, saying which.

import { readFileSync } from 'node:fs';

const START_MS = 60_000;
const CALL_MS = 60_000;
const VIEWER_MS = 5_000;
const PAGE = 'https://example.com/';

const pairing = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const base = `http://${pairing.host}:${pairing.port}`;
const auth = { Authorization: `Bearer ${pairing.token}` };
let session = null;

function fail(message) {
  console.error(`FAIL: ${message}`);
  process.exit(1);
}

function check(ok, message) {
  if (!ok) fail(message);
  console.log(`ok: ${message}`);
}

async function call(method, path, body) {
  const headers = { ...auth };
  if (session) headers['Mcp-Session-Id'] = session;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(base + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(CALL_MS),
  });
  return res;
}

async function json(method, path, body) {
  const res = await call(method, path, body);
  const text = await res.text();
  if (!res.ok) fail(`${method} ${path} answered ${res.status}: ${text}`);
  return text ? JSON.parse(text) : null;
}

// The server answers once the JVM is up and listening, so poll it to a deadline.
async function waitForServer() {
  const deadline = Date.now() + START_MS;
  for (;;) {
    try {
      const res = await fetch(`${base}/v1/device`, { headers: auth, signal: AbortSignal.timeout(2_000) });
      if (res.ok) return res.json();
      fail(`GET /v1/device answered ${res.status}`);
    } catch (e) {
      if (Date.now() > deadline) fail(`the server did not answer within ${START_MS / 1000} s: ${e.cause ?? e}`);
    }
    await new Promise((r) => setTimeout(r, 200));
  }
}

// Opens a video viewer and resolves with the header and the time to the first frame, or fails.
function viewer() {
  return new Promise((resolve) => {
    const url = `${base.replace(/^http/, 'ws')}/v1/stream/video?access_token=${encodeURIComponent(pairing.token)}`;
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    const opened = Date.now();
    let header = null;
    let headerMs = null;
    let done = false;
    const timer = setTimeout(() => {
      ws.close();
      fail(`the viewer got ${header ? 'a header but no frame' : 'no header'} within ${VIEWER_MS / 1000} s`);
    }, VIEWER_MS);
    ws.onmessage = (event) => {
      if (typeof event.data === 'string') {
        header = JSON.parse(event.data);
        headerMs ??= Date.now() - opened;
      } else if (header) {
        clearTimeout(timer);
        done = true;
        const frameMs = Date.now() - opened;
        ws.close();
        resolve({ header, headerMs, frameMs, bytes: event.data.byteLength });
      }
    };
    ws.onclose = (event) => {
      if (!done) fail(`the viewer's socket closed with ${event.code} ${event.reason}`);
    };
    ws.onerror = () => {
      if (!done) fail('the viewer socket failed');
    };
  });
}

const before = await waitForServer();
check(Boolean(before.model), `GET /v1/device answers: ${before.model} on ${before.manufacturer} ${before.release}`);

session = (await json('POST', '/v1/sessions')).id;
check(Boolean(session), 'POST /v1/sessions started a session');

const tab = await json('POST', '/v1/browser/tabs', { url: PAGE });
check(tab.id?.startsWith('chrome~'), `opened ${tab.url} as ${tab.id}`);

const running = await json('GET', '/v1/device');
check(!running.model.includes('not running'), `Chrome runs: ${running.model}, screen ${running.display.widthPx}x${running.display.heightPx}`);

const read = await json('POST', `/v1/browser/targets/${tab.id}/read`, { show: true });
if (read.title !== 'Example Domain' || !read.content?.includes('documentation examples')) fail(`read gave ${JSON.stringify(read)}`);
console.log(`ok: read ${read.totalChars} characters of ${read.url}, shown ${read.shown}`);

// Sites fingerprint the renderer through WebGL, so log what it names. Chrome under Xvfb has none.
const gl = await json('POST', `/v1/browser/targets/${tab.id}/evaluate`, {
  expression: `(() => {
    const gl = document.createElement('canvas').getContext('webgl');
    const info = gl?.getExtension('WEBGL_debug_renderer_info');
    return gl && info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : null;
  })()`,
});
if (gl.exception) fail(`the WebGL probe threw ${gl.exception}`);
console.log(`WebGL: ${gl.value ?? 'none'}`);

const shot = await call('GET', '/v1/screenshot');
const png = Buffer.from(await shot.arrayBuffer());
check(
  shot.ok && shot.headers.get('content-type') === 'image/png' && png.subarray(1, 4).toString() === 'PNG',
  `GET /v1/screenshot is a PNG of ${png.readUInt32BE(16)}x${png.readUInt32BE(20)}, ${png.length} bytes`,
);

// Close every tab listed, the session's last, so the last close is Chrome's last tab: the blank tab
// Chrome's first window opened is listed too, as a tab no session owns.
const listed = (await json('GET', '/v1/browser/targets')).filter((t) => t.type === 'page');
console.log(`tabs listed: ${listed.map((t) => `${t.id} ${t.url}`).join(', ')}`);
check(listed.some((t) => t.id === tab.id), "the session's tab is listed");
for (const t of [...listed.filter((t) => t.id !== tab.id), tab]) {
  await json('POST', `/v1/browser/targets/${t.id}/close`);
}
const after = await json('GET', '/v1/browser/targets');
check(after.length === 0, `closed all ${listed.length} tabs listed, leaving ${JSON.stringify(after)}`);
const still = await json('GET', '/v1/device');
check(still.model === running.model, `Chrome still runs after its last tab closed: ${still.model}`);

const video = await viewer();
check(
  video.header.width > 0 && video.bytes > 0,
  `the viewer got a ${video.header.codec} header of ${video.header.width}x${video.header.height} in ${video.headerMs} ms and a ${video.bytes}-byte frame in ${video.frameMs} ms`,
);

const res = await call('DELETE', '/v1/sessions/current');
check(res.status === 204, 'ended the session');
