package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class AppDesignPolicyTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * .2126 + channel(8) * .7152 + channel(0) * .0722
    }
    private fun contrast(foreground: Int, background: Int): Double {
        val a = luminance(foreground); val b = luminance(background)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
    @Test fun textTokensMeetContrastOnTheirSurfaces() {
        listOf(AppDesignPolicy.BACKGROUND, AppDesignPolicy.SURFACE).forEach { surface ->
            listOf(AppDesignPolicy.TEXT, AppDesignPolicy.MUTED).forEach { assertTrue(contrast(it, surface) >= 4.5) }
        }
        assertTrue(contrast(AppDesignPolicy.AMBER, AppDesignPolicy.AMBER_SURFACE) >= 4.5)
        assertTrue(contrast(AppDesignPolicy.DANGER, AppDesignPolicy.DANGER_SURFACE) >= 4.5)
    }
    @Test fun sharedTouchTargetAndCaptureLabelAreExplicit() {
        assertTrue(AppDesignPolicy.TARGET_DP >= 48)
        assertEquals("● LIVE  00:18:42", AppDesignPolicy.captureLabel(true, "00:18:42"))
        assertFalse(AppDesignPolicy.captureLabel(false, "00:18:42").contains("LIVE"))
    }
}
