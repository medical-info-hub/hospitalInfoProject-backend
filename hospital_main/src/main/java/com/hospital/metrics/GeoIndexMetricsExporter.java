package com.hospital.metrics;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import geoindex.api.SpatialCacheEngine;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class GeoIndexMetricsExporter {

	private final SpatialCacheEngine<?> spatialCacheEngine;
	private final MeterRegistry meterRegistry;

	public GeoIndexMetricsExporter(SpatialCacheEngine<?> spatialCacheEngine, MeterRegistry meterRegistry) {
		this.spatialCacheEngine = spatialCacheEngine;
		this.meterRegistry = meterRegistry;
	}

	@PostConstruct
	public void registerMetrics() {
		  // Index
        meterRegistry.gauge("geoindex.index.queryCount",   spatialCacheEngine, e -> e.getMetrics().queryCount);
        meterRegistry.gauge("geoindex.index.avgPageIds",   spatialCacheEngine, e -> e.getMetrics().avgPageIds);
        meterRegistry.gauge("geoindex.index.avgIntervals", spatialCacheEngine, e -> e.getMetrics().avgIntervals);

        // Cache
        meterRegistry.gauge("geoindex.cache.hit",        spatialCacheEngine, e -> e.getMetrics().pageHit);
        meterRegistry.gauge("geoindex.cache.miss",       spatialCacheEngine, e -> e.getMetrics().pageMiss);
        meterRegistry.gauge("geoindex.cache.hitRate",    spatialCacheEngine, e -> e.getMetrics().pageHitRate);
        meterRegistry.gauge("geoindex.cache.size",       spatialCacheEngine, e -> e.getMetrics().cacheSize);
        meterRegistry.gauge("geoindex.cache.evictCount", spatialCacheEngine, e -> e.getMetrics().evictCount);

        // Disk
        meterRegistry.gauge("geoindex.disk.pageRead",   spatialCacheEngine, e -> e.getMetrics().pageReadCount);
        meterRegistry.gauge("geoindex.disk.pageWrite",  spatialCacheEngine, e -> e.getMetrics().pageWriteCount);

        // Storage
        meterRegistry.gauge("geoindex.storage.flushCount",      spatialCacheEngine, e -> e.getMetrics().flushCount);
        meterRegistry.gauge("geoindex.storage.flushedPages",    spatialCacheEngine, e -> e.getMetrics().flushedPages);
        meterRegistry.gauge("geoindex.storage.dirtyPages",      spatialCacheEngine, e -> e.getMetrics().dirtyPages);
        meterRegistry.gauge("geoindex.storage.rebuildCount",    spatialCacheEngine, e -> e.getMetrics().rebuildCount);
        meterRegistry.gauge("geoindex.storage.avgRebuildMs",    spatialCacheEngine, e -> e.getMetrics().avgRebuildMs);
        meterRegistry.gauge("geoindex.storage.overflowPageUsed", spatialCacheEngine, e -> e.getMetrics().overflowPageUsed);
    }

}
