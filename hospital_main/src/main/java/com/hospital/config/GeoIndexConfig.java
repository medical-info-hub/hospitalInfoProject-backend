package com.hospital.config;

import geoindex.api.SpatialRecordManager;
import geoindex.buffer.CacheManager;
import geoindex.api.SpatialCacheEngine;
import geoindex.index.GeoHashIndex;
import geoindex.storage.DiskManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.hospital.dto.HospitalWebResponse;

@Configuration
public class GeoIndexConfig {

    private static final String DB_FILE = "geoindex.db";

    @Bean
    public DiskManager diskManager() {
        return new DiskManager(DB_FILE);
    }

    @Bean(name = "geoCacheManager", destroyMethod = "close")
    public CacheManager cacheManager(DiskManager diskManager) {
        return new CacheManager(diskManager);
    }

    @Bean
    public GeoHashIndex geoHashIndex() {
        return new GeoHashIndex();
    }

    @Bean
    public SpatialRecordManager spatialRecordManager(
            @Qualifier("geoCacheManager") CacheManager cacheManager,
            GeoHashIndex geoHashIndex) {
        return new SpatialRecordManager(cacheManager, geoHashIndex);  
    }

    @Bean
    public SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine(
            SpatialRecordManager spatialRecordManager) {
        return new SpatialCacheEngine<>(spatialRecordManager); 
    }
}