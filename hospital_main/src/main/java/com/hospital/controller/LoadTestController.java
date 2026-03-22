package com.hospital.controller;

import com.hospital.dto.CacheStats;
import com.hospital.dto.HospitalWebResponse;
import com.hospital.service.LoadTestService;

import geoindex.api.SpatialRecordManager;
import geoindex.index.GeoHashIndex;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/loadtest")
public class LoadTestController {
	private final LoadTestService loadTestService;
	private final GeoHashIndex geoHashIndex;
	private final SpatialRecordManager spatialRecordManager;
	
	@Autowired
	public LoadTestController(LoadTestService loadTestService,
			GeoHashIndex geoHashIndex,
			SpatialRecordManager spatialRecordManager) {
		this.loadTestService = loadTestService;
		this.geoHashIndex = geoHashIndex;
		this.spatialRecordManager = spatialRecordManager;
	}

	@GetMapping(value = "/fullscan", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> fullScan(@RequestParam double lat, @RequestParam double lng,
			@RequestParam(defaultValue = "5.0") double radius) {
		long start = System.currentTimeMillis();
		List<HospitalWebResponse> result = loadTestService.fullScan(lat, lng, radius);
		return Map.of("count", result.size(), "elapsedMs", System.currentTimeMillis() - start);
	}

	@GetMapping(value = "/geoindex", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> geoIndex(@RequestParam double lat, @RequestParam double lng,
			@RequestParam(defaultValue = "5.0") double radius) {
		long start = System.currentTimeMillis();
		List<HospitalWebResponse> result = loadTestService.geoIndex(lat, lng, radius);
		return Map.of("count", result.size(), "elapsedMs", System.currentTimeMillis() - start);
	}

	@GetMapping("/compare")
	public Map<String, Object> compare(@RequestParam double lat, @RequestParam double lng,
			@RequestParam(defaultValue = "5.0") double radius) {
		return loadTestService.compare(lat, lng, radius);
	}

	// 캐시 통계 (JMeter 부하 중 모니터링)
	@GetMapping("/cache/stats")
	public CacheStats getCacheStats() {
		return loadTestService.getCacheStats();
	}

	// 전체 초기화 (테스트 시작 전 호출)
	@PostMapping("/cache/reset")
	public String reset() {
		loadTestService.reset();
		return "캐시 + 통계 + 집계 초기화 완료";
	}

	// -------------------------------------------------------------------------
	// 추가된 두 개
	// -------------------------------------------------------------------------

	// 테스트 결과 요약 (JMeter 끝나고 호출)
	@GetMapping("/summary")
	public Map<String, Object> summary() {
		return loadTestService.getSummary();
	}

	// 집계만 초기화 (캐시는 유지하고 싶을 때)
	@PostMapping("/summary/reset")
	public String resetSummary() {
		loadTestService.reset();
		return "집계 초기화 완료";
	}

	@GetMapping("/debug")
	public Map<String, Object> debug(
	        @RequestParam double lat,
	        @RequestParam double lng) {
	    
	    int storedPageId = geoHashIndex.toPageId(lat, lng);
	    List<Integer> searchPageIds = geoHashIndex.getPageIds(lat, lng, 0.1);
	    List<String> codesInPage = spatialRecordManager.getAllCodesByPageId(storedPageId);
	    
	    return Map.of(
	        "storedPageId", storedPageId,
	        "searchPageIds", searchPageIds,
	        "contains", searchPageIds.contains(storedPageId),
	        "codesInPage", codesInPage.size(),  // ← 이게 0이면 파일에 없는 것
	        "codes", codesInPage
	    );
	}
}