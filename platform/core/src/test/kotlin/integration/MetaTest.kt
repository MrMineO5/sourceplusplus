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
package integration

import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.location.LiveSourceLocation
import java.util.concurrent.TimeUnit

@ExtendWith(VertxExtension::class)
class MetaTest : PlatformIntegrationTest() {

    @Test
    fun multipleMetaAttributes() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("MetaTest", 42),
                meta = mutableMapOf("key1" to "value1", "key2" to "value2")
            )
        ).onComplete {
            if (it.succeeded()) {
                testContext.verify {
                    assertNotNull(it.result())
                    val instrument = it.result()!!
                    assertEquals(instrument.meta["key1"], "value1")
                    assertEquals(instrument.meta["key2"], "value2")
                }

                instrumentService.removeLiveInstrument(it.result().id!!).onComplete {
                    if (it.succeeded()) {
                        testContext.completeNow()
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }

    @Disabled
    @Test
    fun getInstrumentsWithMeta() {
        val testContext = VertxTestContext()
        instrumentService.addLiveInstrument(
            LiveBreakpoint(
                location = LiveSourceLocation("MetaTest", 42),
                meta = mutableMapOf("key1" to "value1", "key2" to "value2")
            )
        ).onComplete {
            if (it.succeeded()) {
                instrumentService.getLiveInstruments().onComplete {
                    if (it.succeeded()) {
                        testContext.verify {
                            assertEquals(1, it.result().size)
                            val instrument = it.result()[0]
                            assertEquals(instrument.meta["key1"], "value1")
                            assertEquals(instrument.meta["key2"], "value2")
                        }
                        instrumentService.removeLiveInstrument(it.result()[0].id!!).onComplete {
                            if (it.succeeded()) {
                                testContext.completeNow()
                            } else {
                                testContext.failNow(it.cause())
                            }
                        }
                    } else {
                        testContext.failNow(it.cause())
                    }
                }
            } else {
                testContext.failNow(it.cause())
            }
        }

        if (testContext.awaitCompletion(10, TimeUnit.SECONDS)) {
            if (testContext.failed()) {
                throw testContext.causeOfFailure()
            }
        } else {
            throw RuntimeException("Test timed out")
        }
    }
}
