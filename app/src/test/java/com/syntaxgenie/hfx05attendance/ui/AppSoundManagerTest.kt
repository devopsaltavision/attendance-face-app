package com.syntaxgenie.hfx05attendance.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSoundManagerTest {
    @Test fun soundDoesNotPlayWhenDisabled() = assertFalse(AppSoundManager.isPlaybackEnabled(false))
    @Test fun soundPlaysWhenEnabled() = assertTrue(AppSoundManager.isPlaybackEnabled(true))
}
