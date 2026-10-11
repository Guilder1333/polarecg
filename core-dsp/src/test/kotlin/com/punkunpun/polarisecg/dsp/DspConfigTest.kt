package com.punkunpun.polarisecg.dsp

import kotlin.test.Test
import kotlin.test.assertEquals

class DspConfigTest {

    @Test
    fun ecgSampleRateMatchesH10() {
        assertEquals(130, ECG_SAMPLE_RATE_HZ)
    }
}
