package com.hospital.service;

import geoindex.api.PageResult;
import geoindex.api.SpatialCacheEngine;
import geoindex.metric.MetricsSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.hospital.dto.HospitalWebResponse;
import com.hospital.entity.HospitalMain;
import com.hospital.repository.HospitalJdbcRepository;
import com.hospital.repository.HospitalMainApiRepository;
import com.hospital.util.DistanceCalculator;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class HospitalSpatialCacheService {

    private final SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine;
    private final HospitalJdbcRepository hospitalJdbcRepository;
    private final HospitalMainApiRepository hospitalMainApiRepository;

    private final AtomicLong totalHitCount  = new AtomicLong(0);
    private final AtomicLong totalMissCount = new AtomicLong(0);
    private final Executor hospitalTaskExecutor;
    private final DistanceCalculator distanceCalculator;

    
    @Value("${cache.warmup.size:3000}") 
    private int cacheSize;

    @Autowired
    public HospitalSpatialCacheService(
            @Qualifier("hospitalSpatialCacheEngine") SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine,
            HospitalJdbcRepository hospitalJdbcRepository,
            HospitalMainApiRepository hospitalMainApiRepository, Executor hospitalTaskExecutor, DistanceCalculator distanceCalculator) {
        this.spatialCacheEngine = spatialCacheEngine;
        this.hospitalJdbcRepository = hospitalJdbcRepository;
        this.hospitalMainApiRepository = hospitalMainApiRepository;
        this.hospitalTaskExecutor = hospitalTaskExecutor;
        this.distanceCalculator = distanceCalculator;
    }

    public List<HospitalWebResponse> search(double userLat, double userLng, double radiusKm) {
        List<HospitalWebResponse> results = spatialCacheEngine.search(userLat, userLng, radiusKm, codes -> {
        	List<HospitalWebResponse> dbResults = hospitalJdbcRepository.findByHospitalCodes(codes);
        	return dbResults.stream()
        			.collect(Collectors.toMap(HospitalWebResponse::getHospitalCode, h -> h));
        });
        
        double[] mbr = distanceCalculator.calcMBR(userLat, userLng, radiusKm);
        return results.stream()
        		.filter(h -> h.getCoordinateX() >= mbr[0] && h.getCoordinateX() <= mbr[1]
        				&& h.getCoordinateY() >= mbr[2] && h.getCoordinateY() <= mbr[3])
        		.collect(Collectors.toList());
    }
    public List<HospitalWebResponse> searchV1(double userLat, double userLng, double radiusKm) {
        String reqId = Thread.currentThread().getName();

        // 1. search → HIT/MISS 판단
        List<PageResult<HospitalWebResponse>> pageResults =
                spatialCacheEngine.search(userLat, userLng, radiusKm);

        totalHitCount.addAndGet(pageResults.stream().filter(PageResult::isHit).count());
        totalMissCount.addAndGet(pageResults.stream().filter(r -> !r.isHit()).count());

        // 2. MISS → codes 수집 → DB 한번에 조회
        List<String> allMissCodes = new ArrayList<>();
        for (PageResult<HospitalWebResponse> result : pageResults) {
            if (!result.isHit()) {
                allMissCodes.addAll(result.getCodes());
            }
        }

        // 3. DB 결과 Map으로 변환 + putCache
        Map<String, HospitalWebResponse> dbMap = new HashMap<>();
        if (!allMissCodes.isEmpty()) {
    
            List<HospitalWebResponse> dbResults =
                    hospitalJdbcRepository.findByHospitalCodes(allMissCodes);
        

            dbResults.forEach(h -> dbMap.put(h.getHospitalCode(), h));

            // dbMap 누락 체크
            long nullCount = allMissCodes.stream()
                    .filter(code -> dbMap.get(code) == null)
                    .count();
            if (nullCount > 0) {
                log.warn("[{}] dbMap 누락: {}건 / {}건", reqId, nullCount, allMissCodes.size());
                allMissCodes.stream()
                        .filter(code -> dbMap.get(code) == null)
                        .limit(5)
                        .forEach(code -> log.warn("[{}] 누락 코드: {}", reqId, code));
            }

            for (PageResult<HospitalWebResponse> result : pageResults) {
                if (!result.isHit()) {
                    List<HospitalWebResponse> pageData = result.getCodes().stream()
                            .map(dbMap::get)
                            .filter(Objects::nonNull)
                            .toList();
                    spatialCacheEngine.putCache(result.getPageId(), pageData);
                }
            }
        }

        // 4. HIT → getCached() / MISS → dbMap에서 직접 (재조회 없음)
        double[] mbr = distanceCalculator.calcMBR(userLat, userLng, radiusKm);
        List<HospitalWebResponse> allResults = new ArrayList<>();

        for (PageResult<HospitalWebResponse> result : pageResults) {
            List<HospitalWebResponse> data = result.isHit()
                ? result.getCached()
                : result.getCodes().stream()
                      .map(dbMap::get)
                      .filter(Objects::nonNull)
                      .toList();

            // dbMap 누락 추적
            if (!result.isHit()) {
                long nullCount = result.getCodes().stream()
                        .filter(code -> dbMap.get(code) == null)
                        .count();
                if (nullCount > 0) {
                    log.warn("[{}] 4단계 누락 pageId={} nullCount={}",
                            reqId, result.getPageId(), nullCount);
                }
            }

            for (HospitalWebResponse h : data) {
                if (h.getCoordinateX() >= mbr[0] && h.getCoordinateX() <= mbr[1]
                 && h.getCoordinateY() >= mbr[2] && h.getCoordinateY() <= mbr[3]) {
                    allResults.add(h);
                }
            }
        }

        return allResults;
    }
    

    public void buildGeoIndex() {
        log.info("GeoIndex 리빌드 시작");
        spatialCacheEngine.rebuild(srm -> {
            List<HospitalMain> all = hospitalMainApiRepository.findAll();
            for (HospitalMain h : all) {
                if (h.getCoordinateY() != null && h.getCoordinateX() != null) {
                    srm.put(
                        h.getCoordinateY(),
                        h.getCoordinateX(),
                        h.getHospitalCode().getBytes()
                    );
                }
            }
            log.info("GeoIndex 리빌드 완료: {}건", all.size());
        });
        warmupCache();
    }
    
    @PostConstruct
    public void warmupCache() {
        CompletableFuture.runAsync(() -> {
            log.info("워밍업 시작");
            Map<Integer, List<String>> targets = spatialCacheEngine.getWarmupTargets(cacheSize);
            List<String> allCodes = targets.values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            log.info("워밍업 codes: {}건", allCodes.size());
            int chunkSize = 1000;
            List<HospitalWebResponse> allData = new ArrayList<>();
            for (int i = 0; i < allCodes.size(); i += chunkSize) {
                List<String> chunk = allCodes.subList(i, Math.min(i + chunkSize, allCodes.size()));
                allData.addAll(hospitalJdbcRepository.findByHospitalCodes(chunk));
            }

            Map<String, HospitalWebResponse> dataByCode = allData.stream()
                    .collect(Collectors.toMap(HospitalWebResponse::getHospitalCode, h -> h));
            targets.forEach((pageId, codes) -> {
                List<HospitalWebResponse> data = codes.stream()
                        .map(dataByCode::get)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                spatialCacheEngine.putCache(pageId, data);
            });
            log.info("워밍업 완료 - cacheSize: {}", spatialCacheEngine.getCacheSize());
        }, hospitalTaskExecutor);
    }

    @PreDestroy
    public void shutdown() {
        spatialCacheEngine.persistWarmup();
    }
    
    public MetricsSnapshot getMetric() {
    	return spatialCacheEngine.getMetrics();
    }



    public long getTotalHitCount()  { return totalHitCount.get(); }
    public long getTotalMissCount() { return totalMissCount.get(); }
    public void resetStats() {
        totalHitCount.set(0);
        totalMissCount.set(0);
    }
}