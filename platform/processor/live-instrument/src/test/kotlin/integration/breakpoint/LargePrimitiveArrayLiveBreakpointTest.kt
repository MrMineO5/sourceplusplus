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
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import java.util.*

class LargePrimitiveArrayLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    private fun largePrimitiveArray() {
        startEntrySpan("largePrimitiveArray")
        val largePrimitiveArray = ByteArray(100_000)
        Arrays.fill(largePrimitiveArray, 1.toByte())
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `large primitive array`() = runBlocking {
        setupLineLabels {
            largePrimitiveArray()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //largePrimitiveArray
                val largePrimitiveArrayVariable = topFrame.variables.first { it.name == "largePrimitiveArray" }
                assertNotNull(largePrimitiveArrayVariable)
                assertEquals(
                    "byte[]",
                    largePrimitiveArrayVariable.liveClazz
                )

                val arrayValues = largePrimitiveArrayVariable.value as JsonArray
                assertEquals(101, arrayValues.size())
                for (i in 0..99) {
                    val value = arrayValues.getJsonObject(i)
                    assertEquals(1, value.getInteger("value"))
                }
                val lastValue = (arrayValues.last() as JsonObject).getJsonObject("value")
                assertEquals("MAX_LENGTH_EXCEEDED", lastValue.getString("@skip"))
                assertEquals(100_000, lastValue.getInteger("@skip[size]"))
                assertEquals(100, lastValue.getInteger("@skip[max]"))
            }

            //test passed
            testContext.completeNow()
        }.completionHandler().await()

        //add live breakpoint
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation(
                    LargePrimitiveArrayLiveBreakpointTest::class.qualifiedName!!,
                    getLineNumber("done"),
                    "spp-test-probe"
                ),
                applyImmediately = true
            )
        ).await()

        //trigger live breakpoint
        largePrimitiveArray()

        errorOnTimeout(testContext)
    }
}
