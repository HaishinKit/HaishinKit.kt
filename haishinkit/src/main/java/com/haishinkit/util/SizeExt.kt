package com.haishinkit.util

import android.util.Size

fun Size.swap(swapped: Boolean): Size =
    if (swapped) {
        Size(height, width)
    } else {
        this
    }

val Size.aspectRatio: Float
    get() = width.toFloat() / height.toFloat()
