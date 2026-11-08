package com.fingertipsmcp.model.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/** Text copied onto the JSON Schema for this class or property. KDoc does not reach descriptors. */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class Doc(val value: String)
