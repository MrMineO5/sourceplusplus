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
package spp.processor.live.impl

import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.skywalking.oap.server.core.CoreModule
import org.apache.skywalking.oap.server.core.analysis.Stream
import org.apache.skywalking.oap.server.core.analysis.StreamDefinition
import org.apache.skywalking.oap.server.core.analysis.metrics.Metrics
import org.apache.skywalking.oap.server.core.analysis.metrics.WithMetadata
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsPersistentWorker
import org.apache.skywalking.oap.server.core.analysis.worker.MetricsStreamProcessor
import org.apache.skywalking.oap.server.core.remote.RemoteSenderService
import org.apache.skywalking.oap.server.core.remote.data.StreamData
import org.apache.skywalking.oap.server.core.remote.selector.Selector
import org.apache.skywalking.oap.server.library.module.ModuleDefineHolder
import org.apache.skywalking.oap.server.library.module.ModuleManager
import org.joor.Reflect
import spp.platform.common.ClusterConnection
import spp.processor.ViewProcessor
import spp.processor.live.impl.view.model.ClusterMetrics
import spp.processor.live.impl.view.util.EntityNaming
import spp.protocol.artifact.metrics.MetricType

class SPPMetricsStreamProcessor : MetricsStreamProcessor() {

    private val realProcessor: MetricsStreamProcessor by lazy { MetricsStreamProcessor() }
    private var sppRemoteSender: SPPRemoteSender? = null

    override fun `in`(metrics: Metrics) {
        realProcessor.`in`(metrics)
    }

    override fun create(moduleDefineHolder: ModuleDefineHolder, stream: Stream, metricsClass: Class<out Metrics>) {
        if (sppRemoteSender == null) {
            moduleDefineHolder.find(CoreModule.NAME).provider().apply {
                sppRemoteSender = SPPRemoteSender(
                    moduleDefineHolder as ModuleManager,
                    getService(RemoteSenderService::class.java)
                )
                registerServiceImplementation(RemoteSenderService::class.java, sppRemoteSender)
            }
        }

        realProcessor.create(moduleDefineHolder, stream, metricsClass)
    }

    override fun create(
        moduleDefineHolder: ModuleDefineHolder,
        stream: StreamDefinition,
        metricsClass: Class<out Metrics>
    ) {
        realProcessor.create(moduleDefineHolder, stream, metricsClass)
    }

    override fun getPersistentWorkers(): MutableList<MetricsPersistentWorker> {
        return realProcessor.getPersistentWorkers()
    }

    override fun setL1FlushPeriod(l1FlushPeriod: Long) {
        realProcessor.setL1FlushPeriod(l1FlushPeriod)
    }

    override fun getL1FlushPeriod(): Long {
        return realProcessor.getL1FlushPeriod()
    }

    override fun setStorageSessionTimeout(storageSessionTimeout: Long) {
        realProcessor.setStorageSessionTimeout(storageSessionTimeout)
    }

    override fun setMetricsDataTTL(metricsDataTTL: Int) {
        realProcessor.setMetricsDataTTL(metricsDataTTL)
    }

    /**
     * todo: moved from l1 worker (MetricsStreamProcessor) to l2 worker (RemoteSenderService, but still inefficient.
     *  Added supportedRealtimeMetrics to avoid metric locking issues, but should be able to support all metrics.
     */
    class SPPRemoteSender(
        moduleManager: ModuleManager,
        private val delegate: RemoteSenderService
    ) : RemoteSenderService(moduleManager) {

        private val supportedRealtimeMetrics = MetricType.ALL.map { it.metricId + "_rec" }

        override fun send(nextWorkName: String, metrics: StreamData, selector: Selector) {
            if (nextWorkName.startsWith("spp_") || supportedRealtimeMetrics.contains(nextWorkName)) {
                val metadata = (metrics as WithMetadata).meta
                val entityName = EntityNaming.getEntityName(metadata)
                if (!entityName.isNullOrEmpty()) {
                    val copiedMetrics: Metrics = metrics::class.java.newInstance() as Metrics
                    copiedMetrics.deserialize(metrics.serialize().build())

                    GlobalScope.launch(ClusterConnection.getVertx().dispatcher()) {
                        if (copiedMetrics.javaClass.simpleName.startsWith("spp_")) {
                            Reflect.on(copiedMetrics).set("metadata", (metrics as WithMetadata).meta)
                        }

                        val metricId = Reflect.on(copiedMetrics).call("id0").get<String>()
                        val fullMetricId = copiedMetrics.javaClass.simpleName + "_" + metricId

                        Vertx.currentContext().putLocal("current_metrics", copiedMetrics)
                        ViewProcessor.realtimeMetricCache.compute(fullMetricId) { _, old ->
                            val new = ClusterMetrics(copiedMetrics)
                            if (old != null) {
                                new.metrics.combine(old.metrics)
                            }
                            new
                        }
                        ViewProcessor.liveViewService.meterView.export(copiedMetrics, true)
                    }
                }
            }

            delegate.send(nextWorkName, metrics, selector)
        }
    }
}
