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
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.instrument.throttle.InstrumentThrottle
import spp.protocol.instrument.throttle.ThrottleStep
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HitLimitLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun hitLimit() {
        startEntrySpan("hitLimit")
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `11 hit limit`() = runBlocking {
        setupLineLabels {
            hitLimit()
        }

        val hitsDoneLatch = CountDownLatch(1)

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    HitLimitLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                hitLimit = 11,
                throttle = InstrumentThrottle(100, ThrottleStep.SECOND),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint 10 times
        for (i in 0 until 10) {
            hitLimit()
        }
        hitsDoneLatch.countDown()

        withContext(Dispatchers.IO) {
            hitsDoneLatch.await(10, TimeUnit.SECONDS)
        }

        //verify still exists
        val liveInstruments = instrumentService.getLiveInstruments().await()
        assertEquals(1, liveInstruments.size)
        assert(liveInstruments.first().hitLimit == 11)
        //todo: verify hit count

        //trigger once more
        hitLimit()
        delay(5000)

        //verify removed
        val liveInstrument = instrumentService.getLiveInstruments().await().firstOrNull()
        assertNull(liveInstrument)
    }
}
