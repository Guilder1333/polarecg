package com.punkunpun.polarisecg.dsp.filter

import com.punkunpun.polarisecg.dsp.ECG_SAMPLE_RATE_HZ

/**
 * All filter settings in one place (plan §7.1). The numbers are starting points to tune on data.
 *
 * `data class`: Kotlin generates equals/toString/copy for it, so `EcgFilterConfig(detectionHighPassHz = 8.0)`
 * changes one value and keeps the rest.
 */
data class EcgFilterConfig(
    val sampleRateHz: Double = ECG_SAMPLE_RATE_HZ.toDouble(),
    /** Display / morphology: removes breathing baseline wander. */
    val morphologyHighPassHz: Double = 0.5,
    val morphologyHighPassOrder: Int = 2,
    /** Display / morphology: removes muscle noise; QRS shape is kept. */
    val morphologyLowPassHz: Double = 40.0,
    val morphologyLowPassOrder: Int = 4,
    /** Detection band-pass: keeps QRS energy, suppresses P/T waves and baseline. */
    val detectionHighPassHz: Double = 5.0,
    val detectionLowPassHz: Double = 20.0,
    val detectionOrder: Int = 2,
    /** Frequency (Hz) at which group delay is reported: roughly the centre of QRS energy. */
    val delayReferenceHz: Double = 10.0,
)

/**
 * The two causal filter chains for one ECG stream. Create one [EcgFilters] per stream and feed
 * every frame to both [morphology] and [detection]; call [reset] after a gap in the data.
 *
 * Causal = uses only past samples, so it works live, but the output is *late* (group delay) and
 * slightly reshaped compared to an offline zero-phase filter such as Python's `filtfilt`.
 * [morphologyDelayMs] / [detectionDelayMs] say how late, for correcting beat times.
 */
class EcgFilters(val config: EcgFilterConfig = EcgFilterConfig()) {
    /** 0.5–40 Hz: for drawing and for judging beat shape. */
    val morphology: FilterCascade = FilterCascade.chain(
        FilterCascade.butterworthHighPass(config.sampleRateHz, config.morphologyHighPassHz, config.morphologyHighPassOrder),
        FilterCascade.butterworthLowPass(config.sampleRateHz, config.morphologyLowPassHz, config.morphologyLowPassOrder),
    )

    /** 5–20 Hz: input of the QRS detector (P3.2). */
    val detection: FilterCascade = FilterCascade.chain(
        FilterCascade.butterworthHighPass(config.sampleRateHz, config.detectionHighPassHz, config.detectionOrder),
        FilterCascade.butterworthLowPass(config.sampleRateHz, config.detectionLowPassHz, config.detectionOrder),
    )

    val morphologyDelayMs: Double get() = delayMs(morphology)
    val detectionDelayMs: Double get() = delayMs(detection)

    fun reset() {
        morphology.reset()
        detection.reset()
    }

    private fun delayMs(f: FilterCascade) = f.groupDelaySamples(config.delayReferenceHz) * 1000.0 / config.sampleRateHz
}
