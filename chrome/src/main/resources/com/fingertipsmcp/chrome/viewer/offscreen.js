// The viewer extension's offscreen document, where the tab is captured and encoded. FingertipsMCP
// drives it over DevTools: it adds the binding fingertipsViewerSend, through which this hands back
// frames and news, and calls fingertipsViewer.capture, window, configure, keyFrame, and stop.
//
// Each frame goes out as the viewer's sockets carry it: a type byte (1 codec config, 2 key, 3
// delta), the timestamp in microseconds as 8 big-endian bytes, then H.264 in Annex B or an Opus
// packet. A message is a letter, V for video, A for audio, or S for news as JSON, then the frame in
// base64 or the JSON. News about a capture carries the id FingertipsMCP gave it, so news from one
// that has stopped is told apart from news of the one that replaced it.
//
// A binding is the only way a page can push to DevTools, and it carries text, so each frame costs
// its base64 and the JSON around it, and its decoding in FingertipsMCP. docs/device-notes.md has
// what that costs.
"use strict";

const CONFIG = 1;
const KEY = 2;
const DELTA = 3;
// Samples within 8 of zero at 16 bits count as silence, as on Android, so a quiet tab sends no
// audio at all.
const SILENT = 8 / 32768;
// When the last frame goes again. A tab that stops changing gets the frame again at these times
// after its last one, so the encoder can sharpen what it blurred while it moved, and then every
// IDLE_MS, so a viewer can tell a still tab from a dead stream. This is a port of the phone's
// RedrawPlan (app/.../stream/RedrawPlan.kt), which Kotlin can't share with the extension; keep the
// two the same.
const SETTLE_MS = [100, 300, 700];
const IDLE_MS = 4000;
// How long capture waits for the stream id that the action on the tab produces.
const STREAM_WAIT_MS = 5000;
// More frames than this waiting in the encoder means it is behind, so new ones are dropped.
const ENCODE_QUEUE = 2;
const KEY_EVERY_S = 10;
const AUDIO_BITRATE = 96000;
const CHUNK = 0x8000;

// What FingertipsMCP asked for: the size to capture at, frame rate, bit rate, and whether anyone
// listens to the audio.
const settings = { id: 0, width: 0, height: 0, fps: 30, bitRate: 4000000, audio: false };

let pending = null;
const waiting = [];
let current = null;

function send(kind, payload) {
  const out = globalThis.fingertipsViewerSend;
  if (typeof out === "function") out(kind + payload);
}

function news(body) {
  send("S", JSON.stringify(body));
}

function base64(bytes) {
  let text = "";
  for (let i = 0; i < bytes.length; i += CHUNK) {
    text += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return btoa(text);
}

// One frame in the sockets' layout, with fill writing the payload.
function frame(type, timestamp, length, fill) {
  const bytes = new Uint8Array(9 + length);
  bytes[0] = type;
  new DataView(bytes.buffer).setBigUint64(1, BigInt(Math.max(0, Math.round(timestamp))));
  fill(bytes.subarray(9));
  return bytes;
}

// The identification header an Opus decoder takes as its description, for when the encoder gives
// none: version 1, the channel count, no pre-skip, 48 kHz, no gain, and the default channel map.
function opusHead(channels) {
  const head = new Uint8Array(19);
  head.set([79, 112, 117, 115, 72, 101, 97, 100, 1, channels]);
  new DataView(head.buffer).setUint32(12, 48000, true);
  return head;
}

// The stream id from the next action on a tab, or an error.
function nextStream() {
  if (pending) {
    const got = pending;
    pending = null;
    return Promise.resolve(got);
  }
  return new Promise((resolve) => {
    const timer = setTimeout(() => {
      waiting.splice(waiting.indexOf(take), 1);
      resolve({ error: "The extension's action gave no stream id" });
    }, STREAM_WAIT_MS);
    function take() {
      clearTimeout(timer);
      const got = pending;
      pending = null;
      resolve(got);
    }
    waiting.push(take);
  });
}

chrome.runtime.onMessage.addListener((message) => {
  if (message.op === "stream") {
    pending = message;
    const next = waiting.shift();
    if (next) next();
  } else if (message.op === "activated") {
    news({ kind: "activated" });
  } else if (message.op === "resized") {
    news({ kind: "resized" });
  }
});

// When the last frame is encoded again, in milliseconds on one clock, as RedrawPlan.kt does it.
class RedrawPlan {
  constructor() {
    this.hasFrame = false;
    this.lastFrameAt = 0;
    this.lastDrawAt = 0;
    this.settled = 0;
  }

  // A new frame came from the tab at now, and is encoded at once.
  captured(now) {
    this.hasFrame = true;
    this.lastFrameAt = now;
    this.settled = 0;
  }

  // A frame was encoded at now, the tab's or a redraw.
  drew(now) {
    this.lastDrawAt = now;
    while (this.settled < SETTLE_MS.length && now >= this.lastFrameAt + SETTLE_MS[this.settled]) {
      this.settled++;
    }
  }

  // Milliseconds from now until the next redraw, or null before the first frame.
  delay(now) {
    if (!this.hasFrame) return null;
    const idleAt = this.lastDrawAt + IDLE_MS;
    const next =
      this.settled >= SETTLE_MS.length ? idleAt : Math.min(this.lastFrameAt + SETTLE_MS[this.settled], idleAt);
    return Math.max(1, next - now);
  }
}

// One tab's capture: its tracks, encoders, and the last frame, kept to send again while still.
class Capture {
  constructor(stream, id) {
    this.stream = stream;
    this.id = id;
    this.open = true;
    this.encoder = null;
    this.size = null;
    this.keyNext = true;
    this.frames = 0;
    this.last = null;
    this.plan = new RedrawPlan();
    this.timer = 0;
    this.audio = null;
    this.audioHead = false;
    this.playback = null;
    const [video] = stream.getVideoTracks();
    const [sound] = stream.getAudioTracks();
    if (video) {
      video.addEventListener("ended", () => this.ended());
      this.read(video, (f) => this.video(f));
    }
    if (sound) {
      // Capturing a tab's audio stops it playing where the tab is, so it is played back here.
      this.playback = new AudioContext();
      this.playback.createMediaStreamSource(new MediaStream([sound])).connect(this.playback.destination);
      this.read(sound, (d) => this.sound(d));
    }
  }

  async read(track, each) {
    const reader = new MediaStreamTrackProcessor({ track: track }).readable.getReader();
    try {
      while (this.open) {
        const { value, done } = await reader.read();
        if (done) break;
        if (this.open) each(value);
        else value.close();
      }
    } finally {
      reader.cancel().catch(() => {});
    }
  }

  ended() {
    if (!this.open) return;
    this.stop();
    news({ kind: "ended", capture: this.id });
  }

  // The video encoder failed and closed, so nothing more can come from this capture.
  failed(detail) {
    if (!this.open) return;
    this.stop();
    news({ kind: "failed", capture: this.id, detail: detail });
  }

  configureVideo(width, height) {
    if (this.encoder && this.encoder.state !== "closed") this.encoder.close();
    this.encoder = new VideoEncoder({
      output: (chunk) => {
        const type = chunk.type === "key" ? KEY : DELTA;
        const bytes = frame(type, chunk.timestamp, chunk.byteLength, (b) => chunk.copyTo(b));
        send("V", base64(bytes));
      },
      error: (e) => this.failed("Video encoder: " + String(e.message || e)),
    });
    this.encoder.configure({
      codec: "avc1.640033",
      width: width,
      height: height,
      bitrate: settings.bitRate,
      framerate: settings.fps,
      latencyMode: "realtime",
      hardwareAcceleration: "prefer-software",
      avc: { format: "annexb" },
    });
    this.size = { width: width, height: height };
    this.keyNext = true;
    news({ kind: "video", capture: this.id, width: width, height: height });
  }

  video(f) {
    const width = f.displayWidth & ~1;
    const height = f.displayHeight & ~1;
    if (!this.size || this.size.width !== width || this.size.height !== height) {
      this.configureVideo(width, height);
    }
    const now = performance.now();
    this.plan.captured(now);
    this.encode(f, now);
    if (this.last) this.last.close();
    this.last = f;
    this.schedule(now);
  }

  encode(f, now) {
    // A frame dropped here was never encoded, so the next one still follows the last encoded.
    if (this.encoder.state !== "configured" || this.encoder.encodeQueueSize > ENCODE_QUEUE) return;
    const key = this.keyNext || this.frames % (settings.fps * KEY_EVERY_S) === 0;
    this.keyNext = false;
    this.frames += 1;
    this.encoder.encode(f, { keyFrame: key });
    this.plan.drew(now);
  }

  // Sets the timer for the next redraw the plan has due.
  schedule(now) {
    clearTimeout(this.timer);
    const wait = this.plan.delay(now);
    if (wait !== null) this.timer = setTimeout(() => this.redraw(), wait);
  }

  // Encodes the last frame again, while the tab is still.
  redraw() {
    if (!this.open || !this.last || !this.encoder) return;
    const now = performance.now();
    const again = new VideoFrame(this.last, { timestamp: this.last.timestamp + (now - this.plan.lastFrameAt) * 1000 });
    this.encode(again, now);
    again.close();
    // A redraw the encoder had no room for counts as drawn too, so the plan moves on and the
    // timer does not spin.
    this.plan.drew(now);
    this.schedule(now);
  }

  silent(data) {
    const samples = new Float32Array(data.numberOfFrames);
    for (let plane = 0; plane < data.numberOfChannels; plane++) {
      data.copyTo(samples, { planeIndex: plane, format: "f32-planar" });
      for (const x of samples) if (Math.abs(x) >= SILENT) return false;
    }
    return true;
  }

  sound(data) {
    try {
      if (!settings.audio || this.silent(data)) return;
      if (!this.audio || this.audio.state === "closed") this.configureAudio(data.numberOfChannels);
      this.audio.encode(data);
    } finally {
      data.close();
    }
  }

  configureAudio(channels) {
    this.audioHead = false;
    this.audio = new AudioEncoder({
      output: (chunk, metadata) => {
        if (!this.audioHead) {
          const given = metadata && metadata.decoderConfig && metadata.decoderConfig.description;
          const head = given ? new Uint8Array(given.buffer || given) : opusHead(channels);
          send("A", base64(frame(CONFIG, chunk.timestamp, head.length, (b) => b.set(head))));
          this.audioHead = true;
        }
        send("A", base64(frame(KEY, chunk.timestamp, chunk.byteLength, (b) => chunk.copyTo(b))));
      },
      error: (e) => news({ kind: "error", detail: "Audio encoder: " + String(e.message || e) }),
    });
    this.audio.configure({ codec: "opus", sampleRate: 48000, numberOfChannels: channels, bitrate: AUDIO_BITRATE });
  }

  stopAudio() {
    if (this.audio && this.audio.state !== "closed") this.audio.close();
    this.audio = null;
  }

  // Ends the capture. A capture that ended or failed has stopped already, so this does nothing then.
  stop() {
    if (!this.open) return;
    this.open = false;
    clearTimeout(this.timer);
    for (const track of this.stream.getTracks()) track.stop();
    if (this.encoder && this.encoder.state !== "closed") this.encoder.close();
    this.stopAudio();
    if (this.last) this.last.close();
    this.last = null;
    if (this.playback) this.playback.close();
  }
}

globalThis.fingertipsViewer = {
  // Captures the tab whose action ran last, at the size, frame rate, and bit rate given, as the
  // capture options.id. Resolves to {ok: true} or {error}. Whatever was captured before stops
  // either way.
  async capture(options) {
    Object.assign(settings, options);
    // The tab captured until now is no longer shown, and a captured tab reads as visible to its
    // page, so it stops at once rather than when the new one starts.
    if (current) current.stop();
    current = null;
    const got = await nextStream();
    if (got.error) return { error: got.error };
    const source = { chromeMediaSource: "tab", chromeMediaSourceId: got.streamId };
    const video = Object.assign({ maxFrameRate: settings.fps }, source);
    if (settings.width && settings.height) {
      video.maxWidth = settings.width;
      video.maxHeight = settings.height;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { mandatory: video },
        audio: { mandatory: source },
      });
      current = new Capture(stream, settings.id);
      return { ok: true };
    } catch (e) {
      return { error: String((e && e.message) || e) };
    }
  },
  // The type of the window with DevTools' id id, which chrome.windows uses too: {type}, with type
  // null when the extension can't see the window, or {error} when the worker did not answer.
  async window(id) {
    try {
      const answer = await chrome.runtime.sendMessage({ op: "window", id: id });
      if (answer && "type" in answer) return { type: answer.type };
      return { error: "The extension's worker gave no answer" };
    } catch (e) {
      return { error: String((e && e.message) || e) };
    }
  },
  // Changes whether audio is encoded, which takes effect at once.
  configure(options) {
    Object.assign(settings, options);
    if (!settings.audio && current) current.stopAudio();
  },
  // The next frame is a key frame, for a viewer that just joined or fell behind.
  keyFrame() {
    if (current) current.keyNext = true;
  },
  // Ends the capture, so the tab is no longer captured.
  stop() {
    pending = null;
    if (current) current.stop();
    current = null;
  },
};
