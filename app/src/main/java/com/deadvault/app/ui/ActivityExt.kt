package com.deadvault.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/** Compose's LocalContext may be wrapped; unwrap it to reach the hosting activity. */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    return null
}
