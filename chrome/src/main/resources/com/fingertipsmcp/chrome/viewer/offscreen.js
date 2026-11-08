// The viewer extension's offscreen document, where the tab is captured and encoded. FingertipsMCP
// drives it over DevTools: it adds the binding fingertipsViewerSend, through which this hands back
// frames and news, and calls fingertipsViewer.capture, configure, keyFrame, and stop.
//
// Each frame goes out as the viewer's sockets carry it: a type byte (1 codec config, 2 key, 3
// delta), the timestamp in microseconds as 8 big-endian bytes, then H.264 in Annex B or an Opus
// packet. A message is a letter, V for video, A for audio, or S for news as JSON, then the frame in
// base64 or the JSON.
"use strict";

const CONFIG = 1;
const KEY = 2;
const DELTA = 3;
// Samples within 8 of zero at 16 bits count as silence, as on Android, so a quiet tab sends no
// audio at all.
const SILENT = 8 / 32768;
// A tab that does not change sends no frames, so the last one goes again after this long, as the
// phone's stream sends one at least every 4 s.
const STILL_MS = 3000;
const STILL_CHECK_MS = 1000;
// How long capture waits for the stream id that the action on the tab produces.
const STREAM_WAIT_MS = 5000;
// More frames than this waiting in the encoder means it is behind, so new ones are dropped.
const ENCODE_QUEUE = 2;
const KEY_EVERY_S = 10;
const AUDIO_BITRATE = 96000;
const CHUNK = 0x8000;

// What FingertipsMCP asked for: the size to capture at, frame rate, bit rate, and whether anyone
// listens to the audio.
const settings = { width: 0, height: 0, fps: 30, bitRate: 4000000, audio: false };

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
  }
});

// One tab's capture: its tracks, encoders, and the last frame, kept to send again while still.
class Capture {
  constructor(stream) {
    this.stream = stream;
    this.open = true;
    this.encoder = null;
    this.size = null;
    this.keyNext = true;
    this.frames = 0;
    this.last = null;
    this.lastAt = 0;
    this.audio = null;
    this.audioHead = false;
    this.playback = null;
    this.timer = setInterval(() => this.still(), STILL_CHECK_MS);
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
    news({ kind: "ended" });
  }

  configureVideo(width, height) {
    if (this.encoder && this.encoder.state !== "closed") this.encoder.close();
    this.encoder = new VideoEncoder({
      output: (chunk) => {
        const type = chunk.type === "key" ? KEY : DELTA;
        const bytes = frame(type, chunk.timestamp, chunk.byteLength, (b) => chunk.copyTo(b));
        send("V", base64(bytes));
      },
      error: (e) => news({ kind: "error", detail: "Video encoder: " + String(e.message || e) }),
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
    news({ kind: "video", width: width, height: height });
  }

  video(f) {
    const width = f.displayWidth & ~1;
    const height = f.displayHeight & ~1;
    if (!this.size || this.size.width !== width || this.size.height !== height) {
      this.configureVideo(width, height);
    }
    this.encode(f);
    if (this.last) this.last.close();
    this.last = f;
  }

  encode(f) {
    // A frame dropped here was never encoded, so the next one still follows the last encoded.
    if (this.encoder.state !== "configured" || this.encoder.encodeQueueSize > ENCODE_QUEUE) return;
    const key = this.keyNext || this.frames % (settings.fps * KEY_EVERY_S) === 0;
    this.keyNext = false;
    this.frames += 1;
    this.encoder.encode(f, { keyFrame: key });
    this.lastAt = performance.now();
  }

  // Sends the last frame again when the tab has been still, so the viewer knows it is live.
  still() {
    if (!this.open || !this.last || !this.encoder) return;
    const idle = performance.now() - this.lastAt;
    if (idle < STILL_MS) return;
    const again = new VideoFrame(this.last, { timestamp: this.last.timestamp + idle * 1000 });
    this.encode(again);
    again.close();
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

  stop() {
    this.open = false;
    clearInterval(this.timer);
    for (const track of this.stream.getTracks()) track.stop();
    if (this.encoder && this.encoder.state !== "closed") this.encoder.close();
    this.stopAudio();
    if (this.last) this.last.close();
    this.last = null;
    if (this.playback) this.playback.close();
  }
}

globalThis.fingertipsViewer = {
  // Captures the tab whose action ran last, at the size, frame rate, and bit rate given. Resolves
  // to {ok: true} or {error}.
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
      current = new Capture(stream);
      return { ok: true };
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
