package com.carlink.core.detect

/** A fixed head-unit layout overrides unreliable battery/modem-based screen detection. */
object ScreenType {
    /** true = handheld, false = car stereo, null = detect. */
    @Volatile var forcedHandheld: Boolean? = null
}
