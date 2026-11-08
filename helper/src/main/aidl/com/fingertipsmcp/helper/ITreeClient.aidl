package com.fingertipsmcp.helper;

/** Events from the helper's UI tree, delivered in order on one binder. */
interface ITreeClient {
    oneway void onSeq(long seq, long windowsVersion, long lastChangeMs);

    oneway void onBus(String type, String json);

    oneway void onIme(String json);

    oneway void onForeground(String packageName);

    oneway void onConnected(boolean connected);
}
