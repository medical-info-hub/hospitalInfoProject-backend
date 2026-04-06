package com.hospital.config;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.cache.CachePolicy;
import geoindex.cache.WarmupStore;
import geoindex.api.SpatialCacheEngine;
import geoindex.index.GeoHashIndex;
import geoindex.metric.EngineMetrics;
import geoindex.storage.DiskManager;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.hospital.dto.HospitalWebResponse;
import com.hospital.dto.PharmacyWebResponse;

@Configuration
public class GeoIndexConfig {

    private static final String HOSPITAL_DB_FILE = "hospital.db";
    private static final String PHARMACY_DB_FILE = "pharmacy.db";

    
    @Bean
    public WarmupStore hospitalWarmupStore() {
    	return new WarmupStore(Path.of("hospital-warmup.store"));
    }
    
    
    @Bean
    public WarmupStore pharmacyWarmupStore() {
    	return new WarmupStore(Path.of("pharmacy-warmup.store"));
    }
    
    
    @Bean
    public EngineMetrics engineMetrics() {
        return new EngineMetrics();
    }

    @Bean(name = "hospitalDiskManager", destroyMethod = "close")
    public DiskManager hospitalDiskManager(EngineMetrics engineMetrics) {
        return new DiskManager(HOSPITAL_DB_FILE, engineMetrics);
    }
    
    @Bean(name = "pharmacyDiskManager", destroyMethod = "close")
    public DiskManager pharmacyDiskManager(EngineMetrics engineMetrics) {
        return new DiskManager(PHARMACY_DB_FILE, engineMetrics);
    }

    @Bean(name = "hospitalCacheManager", destroyMethod = "close")
    public CacheManager hospitalCacheManager(DiskManager hospitalDiskManager, EngineMetrics engineMetrics) {
        return new CacheManager(hospitalDiskManager, engineMetrics);
    }
    
    @Bean(name = "pharmacyCacheManager", destroyMethod = "close")
    public CacheManager pharmacyCacheManager(DiskManager pharmacyDiskManager, EngineMetrics engineMetrics) {
        return new CacheManager(pharmacyDiskManager, engineMetrics);
    }

    @Bean
    public GeoHashIndex geoHashIndex() {
        return new GeoHashIndex();
    }

    @Bean
    public SpatialRecordManager hospitalSpatialRecordManager(
            @Qualifier("hospitalCacheManager") CacheManager hospitalCacheManager,
            GeoHashIndex geoHashIndex, EngineMetrics engineMetrics) {
        return new SpatialRecordManager(hospitalCacheManager, geoHashIndex, engineMetrics);  
    }
    
    @Bean
    public SpatialRecordManager pharmacySpatialRecordManager(
            @Qualifier("pharmacyCacheManager") CacheManager pharmacyCacheManager,
            GeoHashIndex geoHashIndex, EngineMetrics engineMetrics) {
        return new SpatialRecordManager(pharmacyCacheManager, geoHashIndex, engineMetrics);  
    }

    @Bean
    public SpatialCacheEngine<HospitalWebResponse> hospitalSpatialCacheEngine(
            SpatialRecordManager hospitalSpatialRecordManager, EngineMetrics engineMetrics, WarmupStore hospitalWarmupStore) {
        return new SpatialCacheEngine<>(hospitalSpatialRecordManager, CachePolicy.DEFAULT, engineMetrics, hospitalWarmupStore); 
    }
    
    @Bean
    public SpatialCacheEngine<PharmacyWebResponse> pharmacySpatialCacheEngine(
            SpatialRecordManager pharmacySpatialRecordManager, EngineMetrics engineMetrics, WarmupStore pharmacyWarmupStore) {
        return new SpatialCacheEngine<>(pharmacySpatialRecordManager, CachePolicy.DEFAULT, engineMetrics, pharmacyWarmupStore); 
    }
}