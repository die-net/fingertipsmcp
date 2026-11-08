package com.fingertipsmcp.helper;

import com.fingertipsmcp.helper.AxisRange;

parcelable TouchscreenInfo {
    int deviceId;
    int source;
    float maxX;
    float maxY;
    AxisRange pressure;
    AxisRange touchMajor;
    AxisRange touchMinor;
    AxisRange orientation;
    AxisRange size;
}
