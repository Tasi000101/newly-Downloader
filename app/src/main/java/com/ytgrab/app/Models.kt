package com.ytgrab.app

data class VOpt(val height: Int, val size: Long)

data class Info(
    val url: String,
    val title: String,
    val uploader: String,
    val duration: Long,
    val thumb: String,
    val video: List<VOpt>,
    val audioKbps: List<Int>
)

sealed class Choice {
    data class Video(val height: Int) : Choice()
    data class Audio(val kbps: Int) : Choice()
}

data class Hist(
    val title: String,
    val uri: String,
    val mime: String,
    val audio: Boolean,
    val size: Long,
    val time: Long
)
