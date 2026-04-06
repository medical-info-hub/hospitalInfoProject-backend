package com.hospital.service;

import geoindex.api.PageResult;
import geoindex.api.SpatialCacheEngine;
import com.hospital.dto.HospitalWebResponse;
import com.hospital.entity.HospitalMain;
import com.hospital.repository.HospitalJdbcRepository;
import com.hospital.repository.HospitalMainApiRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class SpatialCacheService {

    private final SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine;
    private final HospitalJdbcRepository hospitalJdbcRepository;
    private final HospitalMainApiRepository hospitalMainApiRepository;

    private static final double KM_PER_DEGREE_LAT = 110.0;
    private final AtomicLong totalHitCount  = new AtomicLong(0);
    private final AtomicLong totalMissCount = new AtomicLong(0);

    @Autowired
    public SpatialCacheService(
            SpatialCacheEngine<HospitalWebResponse> spatialCacheEngine,
            HospitalJdbcRepository hospitalJdbcRepository,
            HospitalMainApiRepository hospitalMainApiRepository) {
        this.spatialCacheEngine = spatialCacheEngine;
        this.hospitalJdbcRepository = hospitalJdbcRepository;
        this.hospitalMainApiRepository = hospitalMainApiRepository;
    }

    public List<HospitalWebResponse> search(double userLat, double userLng, double radiusKm) {
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
            log.info("[{}] MISS codes 총 건수: {}", reqId, allMissCodes.size());
            List<HospitalWebResponse> dbResults =
                    hospitalJdbcRepository.findByHospitalCodes(allMissCodes);
            log.info("[{}] DB 조회 결과: {}", reqId, dbResults.size());

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
        double[] mbr = calcMBR(userLat, userLng, radiusKm);
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
    
    
    private double[] calcMBR(double lat, double lng, double radiusKm) {
        double deltaDegreeY = radiusKm / KM_PER_DEGREE_LAT;
        double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(lat));
        double deltaDegreeX = radiusKm / kmPerDegreeLon;
        return new double[]{
            lng - deltaDegreeX, lng + deltaDegreeX,
            lat - deltaDegreeY, lat + deltaDegreeY
        };
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
    }

    public long getTotalHitCount()  { return totalHitCount.get(); }
    public long getTotalMissCount() { return totalMissCount.get(); }
    public void resetStats() {
        totalHitCount.set(0);
        totalMissCount.set(0);
    }
}