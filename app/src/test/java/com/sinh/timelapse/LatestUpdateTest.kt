package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class LatestUpdateTest {
    @Test fun slowConsumerBoundsRoutineBacklogWithoutReorderingTerminalWork() {
        val tasks = ArrayDeque<Runnable>()
        val executor = Executor { tasks.addLast(it) }
        val values = mutableListOf<Int>()
        val update = LatestUpdate<Int>(executor) { values.add(it) }
        repeat(10000) { update.offer(it) }
        assertEquals(1, tasks.size)
        executor.execute { values.add(-1) } // A terminal transition is NOT put through the mailbox.
        tasks.removeFirst().run(); tasks.removeFirst().run()
        assertEquals(listOf(9999, -1), values)
        update.offer(10000); assertEquals(1, tasks.size)
    }
    @Test fun reentrantProducerYieldsToAlreadyQueuedDurableTransitions() {
        val tasks = ArrayDeque<Runnable>()
        val executor = Executor { tasks.addLast(it) }
        val values = mutableListOf<Int>()
        lateinit var update: LatestUpdate<Int>
        update = LatestUpdate(executor) { value -> values.add(value); if (value == 1) update.offer(2) }
        update.offer(1); executor.execute { values.add(-1) }
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(1, -1, 2), values)
    }
}
