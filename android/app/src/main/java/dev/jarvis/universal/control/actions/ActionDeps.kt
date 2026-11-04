package dev.jarvis.universal.control.actions

import android.app.Application
import dev.jarvis.universal.control.AndroidPermissionState
import dev.jarvis.universal.util.Prefs

/** Shared dependencies handed to action modules. */
class ActionDeps(
    val app: Application,
    val prefs: Prefs,
    val permissions: AndroidPermissionState
)
