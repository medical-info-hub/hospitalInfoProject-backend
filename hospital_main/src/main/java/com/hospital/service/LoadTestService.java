package com.hospital.service;

import com.hospital.dto.CacheStats;
import com.hospital.dto.HospitalWebResponse;
import com.hospital.repository.HospitalJdbcRepository;
import geoindex.api.SpatialCacheEngine;
import geoindex.buffer.CacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LoadTestService {

	private final HospitalJdbcRepository hospitalJdbcRepository;
	private final SpatialCacheService spatialCacheService;
	private final SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine;
	private final CacheManager cacheManager; 

	private static final double KM_PER_DEGREE_LAT = 110.0;

	// -------------------------------------------------------------------------
	// 집계 필드
	// -------------------------------------------------------------------------
	private final AtomicInteger totalComparisons = new AtomicInteger(0);
	private final AtomicInteger failedComparisons = new AtomicInteger(0);
	private final AtomicLong totalFsMissing = new AtomicLong(0);
	private final AtomicLong totalGiMissing = new AtomicLong(0);

	@Autowired
	public LoadTestService(HospitalJdbcRepository hospitalJdbcRepository, SpatialCacheService spatialCacheService,
			SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine,CacheManager cacheManager) {
		this.hospitalJdbcRepository = hospitalJdbcRepository;
		this.spatialCacheService = spatialCacheService;
		this.spatialCacheEngine = spatialCacheEngine;
		this.cacheManager = cacheManager;
	}

	public List<HospitalWebResponse> fullScan(double lat, double lng, double radius) {
		long start = System.currentTimeMillis();
		double[] mbr = calcMBR(lat, lng, radius);
		List<HospitalWebResponse> result = hospitalJdbcRepository.findByMBRDirect(mbr[0], mbr[1], mbr[2], mbr[3]);
		log.info("⏱️  Full Scan: {}ms | 결과: {}건", System.currentTimeMillis() - start, result.size());
		return result;
	}

	public List<HospitalWebResponse> geoIndex(double lat, double lng, double radius) {
		long start = System.currentTimeMillis();
		List<HospitalWebResponse> result = spatialCacheService.search(lat, lng, radius);
		log.info("요청 lat={} lng={} radius={} | GeoIndex: {}ms | 결과: {}건",
			    lat, lng, radius, System.currentTimeMillis() - start, result.size());
		return result;
	}

	public Map<String, Object> compare(double lat, double lng, double radiusKm) {
	    long fsStart = System.currentTimeMillis();
	    List<HospitalWebResponse> fullScanResult = fullScan(lat, lng, radiusKm);
	    long fsTime = System.currentTimeMillis() - fsStart;

	    long giStart = System.currentTimeMillis();
	    List<HospitalWebResponse> geoIndexResult = geoIndex(lat, lng, radiusKm);
	    long giTime = System.currentTimeMillis() - giStart;

	    Set<String> fsCodes = fullScanResult.stream()
	        .map(HospitalWebResponse::getHospitalCode)
	        .collect(Collectors.toSet());
	    Set<String> giCodes = geoIndexResult.stream()
	        .map(HospitalWebResponse::getHospitalCode)
	        .collect(Collectors.toSet());

	    Set<String> onlyInFs = new HashSet<>(fsCodes);
	    onlyInFs.removeAll(giCodes);

	    Set<String> onlyInGi = new HashSet<>(giCodes);
	    onlyInGi.removeAll(fsCodes);

	    boolean match = onlyInFs.isEmpty() && onlyInGi.isEmpty();

	    // 집계
	    totalComparisons.incrementAndGet();
	    if (!match) {
	        failedComparisons.incrementAndGet();
	        totalFsMissing.addAndGet(onlyInFs.size());
	        totalGiMissing.addAndGet(onlyInGi.size());
	    }

	    // 누락 코드 출력
	    if (!onlyInFs.isEmpty()) {
	        log.warn("=== 누락 발생 lat={} lng={} FS={}건 GI={}건 ===",
	            lat, lng, fullScanResult.size(), geoIndexResult.size());
	        onlyInFs.stream()
	            .limit(10)  // 너무 많으면 10개만
	            .forEach(code -> log.warn("누락 코드: {}", code));
	        if (onlyInFs.size() > 10) {
	            log.warn("... 외 {}건 생략", onlyInFs.size() - 10);
	        }
	    }

	    log.info("비교 lat={} lng={} | FullScan: {}ms {}건 | GeoIndex: {}ms {}건 | 일치: {} | FS누락: {}건 | GI누락: {}건",
	        lat, lng, fsTime, fullScanResult.size(), giTime, geoIndexResult.size(),
	        match, onlyInFs.size(), onlyInGi.size());

	    return Map.of(
	        "match", match,
	        "fullScanCount", fullScanResult.size(),
	        "geoIndexCount", geoIndexResult.size(),
	        "onlyInFullScan", onlyInFs.size(),
	        "onlyInGeoIndex", onlyInGi.size(),
	        "fullScanMs", fsTime,
	        "geoIndexMs", giTime
	    );
	}

	// -------------------------------------------------------------------------
	// 집계 결과 조회
	// -------------------------------------------------------------------------
	public Map<String, Object> getSummary() {
	    int total = totalComparisons.get();
	    int failed = failedComparisons.get();
	    long fsMissing = totalFsMissing.get();
	    long giMissing = totalGiMissing.get();
	    boolean perfect = failed == 0;

	    log.info("=== 테스트 결과 요약 ===");
	    log.info("총 비교: {}건 | 불일치: {}건 | FS누락 합계: {}건 | GI누락 합계: {}건 | {}",
	        total, failed, fsMissing, giMissing, perfect ? "✅ 완벽" : "❌ 누락 있음");

	    return Map.of(
	        "totalComparisons", total,
	        "failedComparisons", failed,
	        "totalFsMissing", fsMissing,
	        "totalGiMissing", giMissing,
	        "perfect", perfect,
	        "result", perfect ? "✅ 완벽" : "❌ 누락 있음"
	    );
	}

	public CacheStats getCacheStats() {
		return new CacheStats(spatialCacheEngine.getCacheSize(), spatialCacheService.getTotalHitCount(),
				spatialCacheService.getTotalMissCount());
	}

	public void resetStats() {
		spatialCacheService.resetStats();
	}

	// -------------------------------------------------------------------------
	// 전체 초기화 (테스트 시작 전 호출)
	// -------------------------------------------------------------------------
	public void reset() {
	    spatialCacheEngine.clearCache();
	    spatialCacheService.resetStats();
	    cacheManager.clearCache();    
	    totalComparisons.set(0);
	    failedComparisons.set(0);
	    totalFsMissing.set(0);
	    totalGiMissing.set(0);
	}

	private double[] calcMBR(double lat, double lng, double radius) {
		double deltaDegreeY = radius / KM_PER_DEGREE_LAT;
		double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(lat));
		double deltaDegreeX = radius / kmPerDegreeLon;
		return new double[] { lng - deltaDegreeX, lng + deltaDegreeX, lat - deltaDegreeY, lat + deltaDegreeY };
	}
}