/*
 * Source++, the continuous feedback platform for developers.
 * Copyright (C) 2022-2023 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package integration.breakpoint

import integration.LiveInstrumentIntegrationTest
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.throttle.InstrumentThrottle
import spp.protocol.instrument.throttle.ThrottleStep
import java.util.concurrent.atomic.AtomicInteger

class ThrottleLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun throttleTest() {
        startEntrySpan("throttleTest")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `one per second`() = runBlocking {
        setupLineLabels {
            throttleTest()
        }

        //verify breakpoint is hit once per second (10 times)
        val bpHitCount = AtomicInteger(0)
        val testContext = VertxTestContext()
        onBreakpointHit(-1) {
            testContext.verify {
                assertTrue(bpHitCount.incrementAndGet() <= 11) //allow for some variance
            }
        }

        //add live breakpoint
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    ThrottleLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                hitLimit = -1,
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint (100 times)
        val counter = AtomicInteger(0)
        vertx.setPeriodic(100) {
            throttleTest()
            if (counter.incrementAndGet() >= 100) {
                vertx.cancelTimer(it)
            }
        }

        successOnTimeout(testContext, 30)
        assertEquals(10.0, bpHitCount.get().toDouble(), 1.0) //allow for some variance

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveInstrument.id!!).await())
    }

    @Test
    fun `two per second`() = runBlocking {
        setupLineLabels {
            throttleTest()
        }

        //verify breakpoint is hit twice per second (20 times)
        val bpHitCount = AtomicInteger(0)
        val testContext = VertxTestContext()
        onBreakpointHit(-1) {
            testContext.verify {
                assertTrue(bpHitCount.incrementAndGet() <= 21) //allow for some variance
            }
        }

        //add live breakpoint
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    ThrottleLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                hitLimit = -1,
                throttle = InstrumentThrottle(2, ThrottleStep.SECOND),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint (100 times)
        val counter = AtomicInteger(0)
        vertx.setPeriodic(100) {
            throttleTest()
            if (counter.incrementAndGet() >= 100) {
                vertx.cancelTimer(it)
            }
        }

        successOnTimeout(testContext, 30)
        assertEquals(20.0, bpHitCount.get().toDouble(), 1.0) //allow for some variance

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveInstrument.id!!).await())
    }

    @Test
    fun `no throttle`() = runBlocking {
        setupLineLabels {
            throttleTest()
        }

        val bpHitCount = AtomicInteger(0)
        val testContext = VertxTestContext()
        onBreakpointHit(-1) {
            testContext.verify {
                assertTrue(bpHitCount.incrementAndGet() <= 100)
            }
        }

        //add live breakpoint
        val liveInstrument = instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    ThrottleLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                hitLimit = -1,
                throttle = InstrumentThrottle(1000, ThrottleStep.SECOND), //todo: impl NOP throttle
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint (100 times)
        val counter = AtomicInteger(0)
        vertx.setPeriodic(100) {
            throttleTest()
            if (counter.incrementAndGet() >= 100) {
                vertx.cancelTimer(it)
            }
        }

        successOnTimeout(testContext, 30)
        assertEquals(100.0, bpHitCount.get().toDouble(), 7.0) //allow for some variance

        //clean up
        assertNotNull(instrumentService.removeLiveInstrument(liveInstrument.id!!).await())
    }
}
