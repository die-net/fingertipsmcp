package com.fingertipsmcp.helper;

import android.content.ClipData;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import com.fingertipsmcp.helper.ITreeClient;
import com.fingertipsmcp.helper.ShellResult;
import com.fingertipsmcp.helper.StartResult;
import com.fingertipsmcp.helper.TouchscreenInfo;

// Implemented by the shell-UID helper process. Every method verifies that the caller is the
// FingertipsMCP app's UID before doing anything.
interface IHelper {
    int protocolVersion() = 1;

    int pid() = 2;

    // Injects through InputManager. mode is an InputManager.INJECT_INPUT_EVENT_MODE_* value.
    boolean injectMotionEvent(in MotionEvent event, int mode) = 10;

    boolean injectKeyEvent(in KeyEvent event, int mode) = 11;

    // The primary touchscreen. deviceId 0 means the phone has none.
    TouchscreenInfo touchscreenInfo() = 12;

    // Connects to an abstract-namespace unix socket (e.g. "chrome_devtools_remote") as the shell
    // UID and returns the connected socket's fd.
    ParcelFileDescriptor openAbstractSocket(String name) = 20;

    // JSON array of abstract socket names matching devtools patterns, with owning pid/uid where
    // known: [{"name": "...", "pid": 123, "package": "..."}].
    String listDevtoolsSockets() = 21;

    // Runs a command as the shell UID. Output is truncated to maxOutputBytes each.
    ShellResult exec(in String[] argv, long timeoutMs, int maxOutputBytes) = 30;

    // Starts `logcat` with the given args and returns the read end of its stdout.
    ParcelFileDescriptor logcat(in String[] args) = 31;

    // Returns the read end of a pipe that will contain a PNG screenshot.
    ParcelFileDescriptor screencap() = 32;

    // Mirrors the given display onto the provided Surface (e.g. a MediaCodec input surface).
    boolean startMirror(in Surface surface, int width, int height, int displayId) = 40;

    void stopMirror() = 41;

    // Captures all device audio (REMOTE_SUBMIX, Android 11+) and returns a pipe of PCM s16le.
    ParcelFileDescriptor startAudioCapture(int sampleRate, int channels) = 42;

    void stopAudioCapture() = 43;

    // UI tree. payload and the return value are JSON. Errors are JSON too, with status != 200.
    void setTreeClient(ITreeClient client) = 50;

    String tree(String op, String payload) = 51;

    // The package of the top task, or null when there is none. Cheaper than dumpsys.
    String topPackage() = 60;

    // Starts an activity as the shell user, which may start one from the background. With a
    // timeoutMs above 0, waits up to that long for it to be drawn, as `am start -W` does, and the
    // result names the component that took the intent.
    StartResult startActivity(in Intent intent, long timeoutMs) = 61;

    // The primary clip, read as the shell user, which may read it with another app in front. Null
    // when the clipboard is empty.
    ClipData primaryClip() = 70;

    void setPrimaryClip(in ClipData clip) = 71;

    // Waits up to timeoutMs (at most 5 s) for the clipboard to change after the count seen, and
    // returns the count of changes. Pass -1 to read the count without waiting.
    long awaitClipChange(long seen, long timeoutMs) = 72;

    // Returns the read end of a pipe with a content:// URI's bytes, read as the shell user by
    // `content read`. Reading the clip with primaryClip grants the shell read access to its URIs.
    ParcelFileDescriptor readContent(String uri) = 73;

    // Empties the clipboard, as the shell user.
    void clearPrimaryClip() = 74;

    // When the clip on the clipboard was put there, from its description, or -1 when the
    // clipboard is empty. Reading the description does not read the clip itself.
    long primaryClipTimestamp() = 75;

    // The port wireless debugging listens on, or 0 or less while it is off.
    int adbWirelessPort() = 76;

    oneway void shutdown() = 99;
}
