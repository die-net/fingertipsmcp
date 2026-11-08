package com.fingertipsmcp.helper;

// What the activity manager said of a start.
parcelable StartResult {
    // An ActivityManager.START_* code.
    int code;
    // The component that took the intent, in short form, or null when Android did not say.
    String activity;
    // Whether the wait ran out, so code says nothing.
    boolean timedOut;
    // Why Android refused the start with an exception, when it did. Then code is
    // START_PERMISSION_DENIED.
    String refusal;
}
