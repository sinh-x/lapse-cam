package com.sinh.timelapse

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AdaptiveSamplingTest {
    private val caps = listOf(5, 30, 600)
    private val rates = listOf(15, 24, 30)

    private fun rejected(action: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { action() }
    }

    @Test fun `defaults are frozen Adaptive thirty seconds at thirty fps`() {
        val defaults = SamplingConfiguration.Adaptive()
        assertEquals(30, defaults.maxSeconds)
        assertEquals(30, defaults.fps)
        assertEquals(900, defaults.frameBudget)
        assertEquals(defaults, SamplingConfiguration.parseAdaptive())
        val changedDefaults = SamplingConfiguration.Adaptive(5, 15)
        assertEquals(75, changedDefaults.frameBudget)
        assertEquals(900, defaults.frameBudget)
    }

    @Test fun `all caps and fps have exact checked budgets`() {
        for (cap in caps) for (fps in rates) {
            assertEquals(cap * fps, SamplingConfiguration.Adaptive(cap, fps).frameBudget)
            assertEquals(SamplingConfiguration.Adaptive(cap, fps),
                SamplingConfiguration.parseAdaptive(cap.toString(), fps.toString()))
        }
        assertEquals(75, SamplingConfiguration.Adaptive(5, 15).frameBudget)
        assertEquals(18_000, SamplingConfiguration.Adaptive(600, 30).frameBudget)
    }

    @Test fun `malformed fractional signed spaced and overflowing inputs are rejected`() {
        val invalid = listOf("", " ", "abc", "5.0", "5.5", "1e2", "NaN", "-5", "+5", " 5", "5 ", "５",
            "2147483648", "9223372036854775808", "999999999999999999999999999999999999999")
        for (value in invalid) {
            rejected { SamplingConfiguration.parseAdaptive(value, "30") }
            rejected { SamplingConfiguration.parseAdaptive("30", value) }
        }
        for (cap in listOf(Int.MIN_VALUE, -1, 0, 4, 601, Int.MAX_VALUE)) {
            rejected { SamplingConfiguration.Adaptive(cap) }
        }
        for (fps in listOf(Int.MIN_VALUE, -1, 0, 14, 16, 23, 25, 29, 31, Int.MAX_VALUE)) {
            rejected { SamplingConfiguration.Adaptive(fps = fps) }
        }
    }

    @Test fun `Fixed retains all six intervals without an Adaptive cap`() {
        for (seconds in listOf(1, 2, 5, 10, 30, 60)) for (fps in rates) {
            val fixed = SamplingConfiguration.Fixed(seconds, fps)
            assertEquals(seconds * 1_000L, fixed.intervalMillis)
            assertEquals(fps, fixed.fps)
            // A Fixed seven-day stream is not subject to any Adaptive frame budget.
            assertTrue(7 * 24 * 60 * 60L / seconds >= 10_080)
        }
        for (seconds in listOf(Int.MIN_VALUE, 0, 3, 600, Int.MAX_VALUE)) {
            rejected { SamplingConfiguration.Fixed(seconds) }
        }
        rejected { SamplingConfiguration.Fixed(1, 60) }
    }

    @Test fun `exact zero one two B minus one B and B plus one vectors at every cap and fps`() {
        for (cap in caps) for (fps in rates) {
            val config = SamplingConfiguration.Adaptive(cap, fps)
            val budget = config.frameBudget
            for (count in listOf(0, 1, 2, budget - 1, budget, budget + 1)) {
                val sampling = AdaptiveSampling(config)
                repeat(count) { sampling.onCommitted(CommittedFrame(it.toLong(), it * 500L)) }
                val expected = if (count <= budget) (0 until count).toList()
                    else (0 until count step 2).toMutableList().apply {
                        if (last() != count - 1) add(count - 1)
                    }
                assertEquals("cap=$cap fps=$fps count=$count", expected.map(Int::toLong), sampling.selectedFrames().map { it.id })
                assertEquals(if (count > budget) 1L else 0L, sampling.thinningStage)
                assertEquals(if (count > budget) 1_000L else 500L, sampling.intervalMillis)
                assertEquals(budget + 1, sampling.candidateCapacity)
                assertEquals(count, sampling.maximumCandidateCount)
                assertInvariants(sampling, if (count == 0) null else CommittedFrame((count - 1).toLong(), (count - 1) * 500L))
            }
        }
    }

    @Test fun `default 901st completion retains exactly 451 even indices and slows to one second`() {
        val sampling = AdaptiveSampling()
        repeat(901) { sampling.onCommitted(CommittedFrame(it.toLong(), it * 500L)) }
        assertEquals((0L..900L step 2).toList(), sampling.selectedFrames().map { it.id })
        assertEquals(451, sampling.selectedCount)
        assertEquals(1L, sampling.thinningStage)
        assertEquals(1_000L, sampling.intervalMillis)
    }

    @Test fun `odd budget explicitly appends latest while even budget already retains it`() {
        for (budgetConfig in listOf(SamplingConfiguration.Adaptive(5, 15), SamplingConfiguration.Adaptive(5, 24))) {
            val b = budgetConfig.frameBudget
            val sampling = AdaptiveSampling(budgetConfig)
            repeat(b + 1) { sampling.onCommitted(CommittedFrame(it.toLong(), it.toLong())) }
            val expected = (0L..b.toLong() step 2).toMutableList()
            if (b % 2 != 0) expected.add(b.toLong())
            assertEquals(expected, sampling.selectedFrames().map { it.id })
            assertEquals(b / 2 + 1 + b % 2, sampling.selectedCount)
        }
    }

    @Test fun `repeated thinning matches whole candidate oracle not original history parity`() {
        for (config in listOf(SamplingConfiguration.Adaptive(5, 15), SamplingConfiguration.Adaptive(5, 24))) {
            val sampling = AdaptiveSampling(config)
            var oracle = emptyList<CommittedFrame>()
            var stage = 0L
            repeat(3_000) { index ->
                val frame = CommittedFrame(index * 3L + 10L, index * 503L)
                val candidate = oracle + frame
                oracle = if (candidate.size > config.frameBudget) {
                    stage++
                    candidate.filterIndexed { i, _ -> i % 2 == 0 || i == candidate.lastIndex }
                } else candidate
                assertTrue(sampling.onCommitted(frame))
                assertEquals(oracle, sampling.selectedFrames())
                assertEquals(stage, sampling.thinningStage)
                assertInvariants(sampling, frame, 10L)
            }
            assertTrue(sampling.thinningStage > 7)
            assertEquals(60_000L, sampling.intervalMillis)
        }
    }

    @Test fun `replay is deterministic and duplicate prefix IDs including thinned IDs are ignored`() {
        val config = SamplingConfiguration.Adaptive(5, 15)
        val events = (0..2_000).map { CommittedFrame(it * 2L, it * 501L) }
        val original = AdaptiveSampling(config)
        val replay = AdaptiveSampling(config)
        events.forEach { original.onCommitted(it) }
        events.forEach { replay.onCommitted(it) }
        val snapshot = original.selectedFrames()
        val stage = original.thinningStage
        events.forEach { assertFalse(original.onCommitted(it)) }
        assertEquals(snapshot, original.selectedFrames())
        assertEquals(snapshot, replay.selectedFrames())
        assertEquals(stage, original.thinningStage)
        assertEquals(stage, replay.thinningStage)
        assertEquals(original.maxCompletedGapMillis, replay.maxCompletedGapMillis)
        assertEquals(original.intervalMillis, replay.intervalMillis)
    }

    @Test fun `invalid new active order leaves state unchanged and ID extremes never wrap`() {
        rejected { CommittedFrame(-1, 0) }
        rejected { CommittedFrame(0, -1) }
        val sampling = AdaptiveSampling()
        sampling.onCommitted(CommittedFrame(0, 1))
        rejected { sampling.onCommitted(CommittedFrame(1, 0)) }
        assertEquals(listOf(CommittedFrame(0, 1)), sampling.selectedFrames())
        assertEquals(0L, sampling.maxCompletedGapMillis)
        assertEquals(0L, sampling.thinningStage)
        sampling.onCommitted(CommittedFrame(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE - 1, sampling.maxCompletedGapMillis)
        assertFalse(sampling.onCommitted(CommittedFrame(Long.MAX_VALUE, Long.MAX_VALUE)))
        assertEquals(listOf(0L, Long.MAX_VALUE), sampling.selectedFrames().map { it.id })
    }

    @Test fun `equal active offsets are chronological and snapshot mutation cannot change selection`() {
        val sampling = AdaptiveSampling()
        sampling.onCommitted(CommittedFrame(0, 0))
        sampling.onCommitted(CommittedFrame(1, 0))
        val snapshot = sampling.selectedFrames() as MutableList<CommittedFrame>
        snapshot.clear()
        assertEquals(2, sampling.selectedCount)
        assertEquals(listOf(0L, 1L), sampling.selectedFrames().map { it.id })
        assertEquals(0L, sampling.maxCompletedGapMillis)
        assertInvariants(sampling, CommittedFrame(1, 0))
    }

    @Test fun `seven day irregular fake timelines satisfy oracle gap replay and bounded streaming memory at all caps and fps`() {
        val sevenDays = 7 * 24 * 60 * 60 * 1_000L
        for (cap in caps) for (fps in rates) {
            val config = SamplingConfiguration.Adaptive(cap, fps)
            val sampling = AdaptiveSampling(config)
            val replay = AdaptiveSampling(config)
            // Only the test oracle keeps full history. Production receives one event, no file/history provider.
            val history = mutableListOf<CommittedFrame>()
            var oracle = mutableListOf<CommittedFrame>()
            var active = 0L
            var stage = 0L
            var maximumGap = 0L
            while (active <= sevenDays) {
                val frame = CommittedFrame(history.size.toLong(), active)
                history.lastOrNull()?.let { maximumGap = maxOf(maximumGap, active - it.activeOffsetMillis) }
                history.add(frame)
                oracle.add(frame)
                val thins = oracle.size > config.frameBudget
                if (thins) {
                    stage++
                    oracle = oracle.filterIndexed { i, _ -> i % 2 == 0 || i == oracle.lastIndex }.toMutableList()
                }
                sampling.onCommitted(frame)
                assertEquals(stage, sampling.thinningStage)
                assertEquals(maximumGap, sampling.maxCompletedGapMillis)
                assertTrue(sampling.selectedCount <= config.frameBudget)
                assertEquals(config.frameBudget + 1, sampling.candidateCapacity)
                assertTrue(sampling.maximumCandidateCount <= config.frameBudget + 1)
                if (thins || history.size % 257 == 0) {
                    assertEquals(oracle, sampling.selectedFrames())
                    assertInvariants(sampling, frame)
                }
                // Fake slow processing/missed slots: never assume perfect 2 fps or uniform spacing.
                active += sampling.intervalMillis + if (history.size % 17 == 0) 1_137L else 0L
            }
            history.forEach { replay.onCommitted(it) }
            assertEquals(oracle, sampling.selectedFrames())
            assertEquals(oracle, replay.selectedFrames())
            assertEquals(stage, replay.thinningStage)
            assertTrue(stage > 1)
            assertInvariants(sampling, history.last())
        }
    }

    @Test fun `streaming completion retains only one bounded candidate array and never requires history access`() {
        val sampling = AdaptiveSampling(SamplingConfiguration.Adaptive(5, 15))
        // This source deliberately has no enumeration API: saves deliver only their newly committed event.
        class CompletedPhotoSource {
            var saves = 0
            fun save(): CommittedFrame = CommittedFrame(saves++.toLong(), saves * 500L)
        }
        val source = CompletedPhotoSource()
        repeat(100_000) { sampling.onCommitted(source.save()) }
        assertEquals(100_000, source.saves)
        assertEquals(76, sampling.candidateCapacity)
        assertEquals(76, sampling.maximumCandidateCount)
        assertTrue(sampling.selectedCount <= 75)
        // No second history/seen-ID collection is retained by the production selector.
        val storage = AdaptiveSampling::class.java.declaredFields.filter { it.type.isArray }
        assertEquals(1, storage.size)
        storage.single().isAccessible = true
        val slots = storage.single().get(sampling) as Array<*>
        assertEquals(76, slots.size)
        assertEquals(sampling.selectedCount, slots.count { it != null })
        assertTrue(AdaptiveSampling::class.java.declaredFields.none {
            Collection::class.java.isAssignableFrom(it.type) || Map::class.java.isAssignableFrom(it.type)
        })
        assertInvariants(sampling, CommittedFrame(99_999, 50_000_000))
    }

    @Test fun `failed or uncommitted shutters are not selection events`() {
        val sampling = AdaptiveSampling(SamplingConfiguration.Adaptive(5, 15))
        repeat(75) { sampling.onCommitted(CommittedFrame(it.toLong(), it * 500L)) }
        val before = sampling.selectedFrames()
        // A failed shutter has no onCommitted call; a later successful ID may skip that request.
        assertEquals(before, sampling.selectedFrames())
        assertEquals(0L, sampling.thinningStage)
        sampling.onCommitted(CommittedFrame(76, 38_000))
        assertEquals(1L, sampling.thinningStage)
        assertEquals(76L, sampling.selectedFrames().last().id)
        assertFalse(sampling.selectedFrames().any { it.id == 75L })
    }

    private fun assertInvariants(sampling: AdaptiveSampling, latest: CommittedFrame?, firstId: Long = 0L) {
        val selected = sampling.selectedFrames()
        assertTrue(selected.size <= sampling.configuration.frameBudget)
        assertEquals(selected.size, selected.map { it.id }.toSet().size)
        assertTrue(selected.zipWithNext().all { (a, b) -> a.id < b.id && a.activeOffsetMillis <= b.activeOffsetMillis })
        if (latest == null) assertTrue(selected.isEmpty()) else {
            assertEquals(firstId, selected.first().id)
            assertEquals(latest, selected.last())
        }
        val gap = selected.zipWithNext().maxOfOrNull { (a, b) -> b.activeOffsetMillis - a.activeOffsetMillis } ?: 0L
        val oracleBound = BigInteger.valueOf(sampling.maxCompletedGapMillis).shiftLeft(sampling.thinningStage.toInt())
        assertTrue(BigInteger.valueOf(gap) <= oracleBound)
        assertTrue(CaptureTiming.isWithinAdaptiveGapBound(gap, sampling.maxCompletedGapMillis, sampling.thinningStage))
    }
}
