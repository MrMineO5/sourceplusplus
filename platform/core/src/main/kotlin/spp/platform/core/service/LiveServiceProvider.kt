/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
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
package spp.platform.core.service

import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import spp.platform.common.DeveloperAuth
import spp.platform.common.service.SourceBridgeService
import spp.platform.storage.SourceStorage
import spp.protocol.SourceServices
import spp.protocol.marshall.ProtocolMarshaller
import spp.protocol.platform.ProbeAddress
import spp.protocol.platform.developer.Developer
import spp.protocol.platform.developer.SelfInfo
import spp.protocol.platform.general.Service
import spp.protocol.platform.status.ActiveInstance
import spp.protocol.service.LiveService
import spp.protocol.service.LiveViewService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class LiveServiceProvider(private val vertx: Vertx) : LiveService {

    companion object {
        private val log = LoggerFactory.getLogger(LiveServiceProvider::class.java)
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
            .withZone(ZoneId.systemDefault())
    }

    override fun getClients(): Future<JsonObject> {
        val promise = Promise.promise<JsonObject>()
        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(
                JsonObject().apply {
                    val bridgeService = SourceBridgeService.service(vertx).await()
                    if (bridgeService != null) {
                        put("markers", bridgeService.getActiveMarkers().await())
                        put("probes", bridgeService.getActiveProbes().await())
                    }
                }
            )
        }
        return promise.future()
    }

    override fun getStats(): Future<JsonObject> {
        log.trace("Getting platform stats")
        val promise = Promise.promise<JsonObject>()
        val devAuth = Vertx.currentContext().get<DeveloperAuth>("developer")

        val subStats = Promise.promise<JsonObject>()
        LiveViewService.createProxy(vertx, devAuth.accessToken).getLiveViewSubscriptionStats().onComplete {
            if (it.succeeded()) {
                subStats.complete(it.result())
            } else {
                subStats.fail(it.cause())
            }
        }

        GlobalScope.launch(vertx.dispatcher()) {
            val platformStats = getPlatformStats()
            subStats.future().onSuccess {
                promise.complete(
                    JsonObject()
                        .put("platform", platformStats)
                        .put("subscriptions", it)
                )
            }.onFailure {
                promise.fail(it)
            }
        }
        return promise.future()
    }

    private suspend fun getPlatformStats(): JsonObject {
        return JsonObject()
            .apply {
                val bridgeService = SourceBridgeService.service(vertx).await()
                if (bridgeService != null) {
                    put("connected-markers", bridgeService.getConnectedMarkers().await())
                    put("connected-probes", bridgeService.getConnectedProbes().await())
                }
            }
            .put(
                "services", //todo: get services from service registry
                JsonObject()
                    .put(
                        "core",
                        JsonObject()
                            .put(
                                SourceServices.Utilize.LIVE_SERVICE,
                                vertx.sharedData().getLocalCounter(SourceServices.Utilize.LIVE_SERVICE).await().get()
                                    .await()
                            )
                            .put(
                                SourceServices.Utilize.LIVE_INSTRUMENT,
                                vertx.sharedData().getLocalCounter(SourceServices.Utilize.LIVE_INSTRUMENT).await().get()
                                    .await()
                            )
                            .put(
                                SourceServices.Utilize.LIVE_VIEW,
                                vertx.sharedData().getLocalCounter(SourceServices.Utilize.LIVE_VIEW).await().get()
                                    .await()
                            )
                    )
                    .put(
                        "probe",
                        JsonObject()
                            .put(
                                ProbeAddress.LIVE_INSTRUMENT_REMOTE,
                                vertx.sharedData().getLocalCounter(ProbeAddress.LIVE_INSTRUMENT_REMOTE)
                                    .await().get().await()
                            )
                    )
            )
    }

    override fun getSelf(): Future<SelfInfo> {
        val promise = Promise.promise<SelfInfo>()
        val selfId = Vertx.currentContext().get<DeveloperAuth>("developer").selfId
        log.trace("Getting self info")

        GlobalScope.launch(vertx.dispatcher()) {
            promise.complete(
                SelfInfo(
                    developer = Developer(selfId),
                    roles = SourceStorage.getDeveloperRoles(selfId),
                    permissions = SourceStorage.getDeveloperPermissions(selfId).toList(),
                    access = SourceStorage.getDeveloperAccessPermissions(selfId)
                )
            )
        }
        return promise.future()
    }

    override fun getServices(): Future<List<Service>> {
        val promise = Promise.promise<List<Service>>()
        val forward = JsonObject()
        forward.put("developer_id", Vertx.currentContext().get<DeveloperAuth>("developer").selfId)
        forward.put("method", HttpMethod.POST.name())
        forward.put(
            "body", JsonObject()
                .put(
                    "query", "query (\$durationStart: String!, \$durationEnd: String!, \$durationStep: Step!) {\n" +
                            "  getAllServices(duration: {start: \$durationStart, end: \$durationEnd, step: \$durationStep}) {\n" +
                            "    key: id\n" +
                            "    label: name\n" +
                            "  }\n" +
                            "}"
                )
                .put(
                    "variables", JsonObject()
                        .put("durationStart", formatter.format(Instant.now().minus(365, ChronoUnit.DAYS)))
                        .put("durationEnd", formatter.format(Instant.now()))
                        .put("durationStep", "MINUTE")
                )
        )

        vertx.eventBus().request<JsonObject>("skywalking-forwarder", forward) {
            if (it.succeeded()) {
                val response = it.result().body()
                val body = JsonObject(response.getString("body"))
                val data = body.getJsonObject("data")
                val services = data.getJsonArray("getAllServices")
                val result = mutableListOf<Service>()
                for (i in 0 until services.size()) {
                    val service = services.getJsonObject(i)
                    result.add(
                        Service(
                            id = service.getString("key"),
                            name = service.getString("label")
                        )
                    )
                }
                promise.complete(result)
            } else {
                promise.fail(it.cause())
            }
        }
        return promise.future()
    }

    override fun getActiveProbes(): Future<List<ActiveInstance>> {
        val promise = Promise.promise<List<ActiveInstance>>()
        GlobalScope.launch(vertx.dispatcher()) {
            val bridgeService = SourceBridgeService.service(vertx).await()
            if (bridgeService != null) {
                promise.complete(
                    bridgeService.getActiveProbes().await().list.map {
                        ProtocolMarshaller.deserializeActiveInstance(JsonObject.mapFrom(it))
                    }
                )
            }
        }
        return promise.future()
    }
}
