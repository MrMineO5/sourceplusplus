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

import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxTestContext
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import spp.protocol.artifact.log.Log
import spp.protocol.instrument.LiveLog
import spp.protocol.instrument.location.LiveSourceLocation
import spp.protocol.service.SourceServices
import spp.protocol.view.LiveView
import spp.protocol.view.LiveViewConfig
import spp.protocol.view.LiveViewEvent

class LiveLogSubscriptionTest : LiveInstrumentIntegrationTest() {

    companion object {
        private val log = KotlinLogging.logger {}
    }

    private fun triggerLog() {
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
    }

    @BeforeEach
    fun reset(): Unit = runBlocking {
        viewService.clearLiveViews().await()
    }

    @Test
    fun `test live log subscription`(): Unit = runBlocking {
        setupLineLabels {
            triggerLog()
        }

        val liveLog = LiveLog(
            "test log",
            emptyList(),
            LiveSourceLocation(
                LiveLogSubscriptionTest::class.qualifiedName!!,
                getLineNumber("done"),
                "spp-test-probe"
            ),
            id = "live-log-subscription-test",
            hitLimit = 5,
            applyImmediately = true
        )

        val subscriptionId = viewService.addLiveView(
            LiveView(
                entityIds = mutableSetOf(liveLog.logFormat),
                viewConfig = LiveViewConfig(
                    "test",
                    listOf("endpoint_logs")
                )
            )
        ).await().subscriptionId!!
        log.info("Using subscription id: {}", subscriptionId)

        val consumer = vertx.eventBus().consumer<JsonObject>(
            SourceServices.Subscribe.toLiveViewSubscriberAddress("system")
        )

        val testContext = VertxTestContext()
        var totalCount = 0
        consumer.handler {
            val liveViewEvent = LiveViewEvent(it.body())
            val rawLog = Log(JsonObject(liveViewEvent.metricsData).getJsonObject("log"))
            log.info("Received log: {}", rawLog)

            testContext.verify {
                assertEquals("test log", rawLog.content)
                assertEquals("Live", rawLog.level)

                totalCount += 1
                if (totalCount >= 5) {
                    testContext.completeNow()
                }
            }
        }.completionHandler().await()

        instrumentService.addLiveInstrument(liveLog).await()
        log.info("Applied live log")

        for (i in 0 until 5) {
            triggerLog()
            log.info("Triggered log")

            delay(2000)
        }

        errorOnTimeout(testContext, 30)

        //clean up
        consumer.unregister()
        assertNotNull(viewService.removeLiveView(subscriptionId).await())
    }
}
