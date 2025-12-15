package com.hospital.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.hospital.dto.HospitalWebResponse;
import com.hospital.repository.HospitalJdbcRepository;

import ch.hsr.geohash.BoundingBox;
import ch.hsr.geohash.GeoHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 지오해시 기반 격자 캐싱 서비스
 *
 * 핵심 원칙:
 * 1. 각 격자는 독립적인 캐시 단위
 * 2. 병원은 자신의 위치에 해당하는 격자에 분류되어 저장
 * 3. 조회 시 9개 격자 캐시를 병합
 * 4. 캐시 미스 격자만 DB에서 해당 격자 범위로 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeohashCacheService {

	private final RedisTemplate<String, Object> redisTemplate;
	private final HospitalJdbcRepository hospitalJdbcRepository;
	private final Executor hospitalTaskExecutor;

	private static final int GEOHASH_PRECISION = 5;
	private static final String CACHE_KEY_PREFIX = "hospital:geo:";
	private static final long CACHE_TTL_HOURS = 1;

	/**
	 * 지오해시 캐시에서 병원 데이터 조회 (Pipeline 최적화)
	 *
	 * @param userLat 사용자 위도
	 * @param userLon 사용자 경도
	 * @return 캐시된 병원 리스트 + 누락된 격자 정보
	 */
	public CacheResult getFromCache(double userLat, double userLon) {
		long startTime = System.currentTimeMillis();

		// 1. 중심 격자 키 생성
		String centerKey = getGeohash(userLat, userLon);

		// 2. 인접 8개 격자 키 생성
		Set<String> neighborKeys = getNeighborGeohashes(userLat, userLon);

		// 3. 모든 격자 키 (중심 + 인접 8개)
		List<String> allGeohashKeys = new ArrayList<>();
		allGeohashKeys.add(centerKey);
		allGeohashKeys.addAll(neighborKeys);

		log.debug("조회 대상 - 중심: {}, 인접 8개: {}", centerKey, neighborKeys);

		// 4. mget으로 한 번에 조회 (executePipelined 대신)
		List<String> cacheKeys = allGeohashKeys.stream()
			.map(key -> CACHE_KEY_PREFIX + key)
			.collect(Collectors.toList());

		List<Object> pipelineResults = redisTemplate.opsForValue().multiGet(cacheKeys);

		long pipelineTime = System.currentTimeMillis() - startTime;
		log.debug("Redis MGET 조회 완료 ({}ms, {}개 키)", pipelineTime, allGeohashKeys.size());

		// 5. 결과 처리
		List<HospitalWebResponse> cachedHospitals = new ArrayList<>();
		Set<String> missedCenterKey = new HashSet<>();
		Set<String> missedNeighborKeys = new HashSet<>();

		for (int i = 0; i < allGeohashKeys.size(); i++) {
			String geohashKey = allGeohashKeys.get(i);
			Object cached = pipelineResults != null ? pipelineResults.get(i) : null;
			String cacheKey = cacheKeys.get(i);

			if (cached != null && cached instanceof List) {
				@SuppressWarnings("unchecked")
				List<HospitalWebResponse> hospitals = (List<HospitalWebResponse>) cached;
				cachedHospitals.addAll(hospitals);

				if (geohashKey.equals(centerKey)) {
					log.debug("중심 격자 캐시 HIT: {} ({}개 병원)", cacheKey, hospitals.size());
				} else {
					log.debug("인접 격자 캐시 HIT: {} ({}개 병원)", cacheKey, hospitals.size());
				}
			} else {
				if (geohashKey.equals(centerKey)) {
					missedCenterKey.add(geohashKey);
					log.debug("중심 격자 캐시 MISS: {}", cacheKey);
				} else {
					missedNeighborKeys.add(geohashKey);
					log.debug("인접 격자 캐시 MISS: {}", cacheKey);
				}
			}
		}

		long totalTime = System.currentTimeMillis() - startTime;
		log.debug("캐시 조회 총 시간: {}ms (HIT: {}개, MISS: {}개)",
			totalTime, cachedHospitals.size(), missedCenterKey.size() + missedNeighborKeys.size());

		return new CacheResult(cachedHospitals, missedCenterKey, missedNeighborKeys);
	}

	/**
	 * 누락된 격자들을 병렬로 동기 조회 (빠른 응답)
	 *
	 * @param missedKeys 캐시 미스된 격자 키들
	 * @return DB에서 조회한 병원 리스트
	 */
	public List<HospitalWebResponse> fetchMissedGridsParallel(Set<String> missedKeys) {
		if (missedKeys.isEmpty()) {
			return List.of();
		}

		log.info("캐시 MISS 격자 {}개 - 병렬 DB 조회 시작", missedKeys.size());

		// 병렬로 각 격자 조회 (hospitalTaskExecutor 사용)
		List<CompletableFuture<Map.Entry<String, List<HospitalWebResponse>>>> futures = missedKeys.stream()
			.map(geohashKey -> CompletableFuture.supplyAsync(() -> {
				List<HospitalWebResponse> hospitals = fetchHospitalsForGrid(geohashKey);
				return Map.entry(geohashKey, hospitals);
			}, hospitalTaskExecutor))
			.collect(Collectors.toList());

		// 모든 작업 완료 대기
		CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

		// 결과 수집
		List<HospitalWebResponse> allHospitals = new ArrayList<>();
		List<CompletableFuture<Void>> cachingFutures = new ArrayList<>();

		for (CompletableFuture<Map.Entry<String, List<HospitalWebResponse>>> future : futures) {
			try {
				Map.Entry<String, List<HospitalWebResponse>> entry = future.get();
				String geohashKey = entry.getKey();
				List<HospitalWebResponse> hospitals = entry.getValue();
				allHospitals.addAll(hospitals);

				// Redis 캐싱을 병렬로 처리
				CompletableFuture<Void> cachingFuture = CompletableFuture.runAsync(() -> {
					String cacheKey = CACHE_KEY_PREFIX + geohashKey;
					if (!hospitals.isEmpty()) {
						redisTemplate.opsForValue().set(cacheKey, hospitals, CACHE_TTL_HOURS, TimeUnit.HOURS);
						log.info("격자 캐싱: {} ({}개 병원)", cacheKey, hospitals.size());
					} else {
						redisTemplate.opsForValue().set(cacheKey, List.of(), CACHE_TTL_HOURS, TimeUnit.HOURS);
						log.debug("빈 격자 캐싱: {}", geohashKey);
					}
				}, hospitalTaskExecutor);

				cachingFutures.add(cachingFuture);
			} catch (Exception e) {
				log.error("격자 조회 중 오류 발생", e);
			}
		}

		// 모든 캐싱 작업 완료 대기
		CompletableFuture.allOf(cachingFutures.toArray(new CompletableFuture[0])).join();

		log.info("병렬 조회 완료: 총 {}개 병원", allHospitals.size());
		return allHospitals;
	}

	/**
	 * 특정 격자의 병원 데이터를 DB에서 조회
	 *
	 * @param geohashKey 격자 지오해시 키
	 * @return 해당 격자에 속하는 병원 리스트
	 */
	private List<HospitalWebResponse> fetchHospitalsForGrid(String geohashKey) {
		long startTime = System.currentTimeMillis();

		// 1. 지오해시로부터 바운딩 박스 계산
		GeoHash geoHash = GeoHash.fromGeohashString(geohashKey);
		BoundingBox bbox = geoHash.getBoundingBox();

		double minLat = bbox.getSouthLatitude();
		double maxLat = bbox.getNorthLatitude();
		double minLon = bbox.getWestLongitude();
		double maxLon = bbox.getEastLongitude();

		log.debug("격자 {} 범위: lat[{}, {}], lon[{}, {}]",
			geohashKey, minLat, maxLat, minLon, maxLon);

		// 2. DB에서 해당 격자 범위의 병원 조회 (MBR)
		long dbStartTime = System.currentTimeMillis();
		List<HospitalWebResponse> hospitals = hospitalJdbcRepository.findByMBRDirect(
			minLon, maxLon, minLat, maxLat
		);
		long dbTime = System.currentTimeMillis() - dbStartTime;

		// 3. 실제로 이 격자에 속하는 병원만 필터링 (정확도 보장)
		List<HospitalWebResponse> filtered = hospitals.stream()
			.filter(hospital -> {
				String hospitalGeohash = getGeohash(hospital.getCoordinateY(), hospital.getCoordinateX());
				return hospitalGeohash.equals(geohashKey);
			})
			.toList();

		long totalTime = System.currentTimeMillis() - startTime;
		log.info("격자 {} 조회 완료: DB {}ms, 필터링 {}ms, 총 {}ms (조회: {}개 -> 필터링: {}개)",
			geohashKey, dbTime, totalTime - dbTime, totalTime, hospitals.size(), filtered.size());

		return filtered;
	}


	/**
	 * 주변 8개 격자 지오해시 반환
	 */
	private Set<String> getNeighborGeohashes(double lat, double lon) {
		Set<String> neighbors = new HashSet<>();
		GeoHash centerGeoHash = GeoHash.withCharacterPrecision(lat, lon, GEOHASH_PRECISION);

		// 상하좌우 4개
		neighbors.add(centerGeoHash.getNorthernNeighbour().toBase32());
		neighbors.add(centerGeoHash.getSouthernNeighbour().toBase32());
		neighbors.add(centerGeoHash.getEasternNeighbour().toBase32());
		neighbors.add(centerGeoHash.getWesternNeighbour().toBase32());

		// 대각선 4개
		GeoHash north = centerGeoHash.getNorthernNeighbour();
		neighbors.add(north.getEasternNeighbour().toBase32());
		neighbors.add(north.getWesternNeighbour().toBase32());

		GeoHash south = centerGeoHash.getSouthernNeighbour();
		neighbors.add(south.getEasternNeighbour().toBase32());
		neighbors.add(south.getWesternNeighbour().toBase32());

		return neighbors;
	}

	/**
	 * 중심 격자만 빠르게 체크 (캐시 존재 여부만 확인)
	 *
	 * @param userLat 사용자 위도
	 * @param userLon 사용자 경도
	 * @return 중심 격자 캐시 존재 여부
	 */
	public boolean isCenterGridCached(double userLat, double userLon) {
		String centerKey = getGeohash(userLat, userLon);
		String cacheKey = CACHE_KEY_PREFIX + centerKey;
		return Boolean.TRUE.equals(redisTemplate.hasKey(cacheKey));
	}

	/**
	 * 캐시 우선 조회 (웜 캐시용) - Pipeline 최적화
	 * 캐시에서 9개 격자를 한 번에 조회하고, 모든 격자가 캐시되어 있으면 반환
	 * 하나라도 캐시 미스면 null 반환
	 *
	 * @param userLat 사용자 위도
	 * @param userLon 사용자 경도
	 * @return 캐시된 병원 리스트 (모든 격자 HIT) 또는 null (하나라도 MISS)
	 */
	public List<HospitalWebResponse> getFromCacheIfAllHit(double userLat, double userLon) {
		long startTime = System.currentTimeMillis();

		// 1. 중심 격자 + 인접 8개 격자 키 생성
		String centerKey = getGeohash(userLat, userLon);
		Set<String> neighborKeys = getNeighborGeohashes(userLat, userLon);

		List<String> allGeohashKeys = new ArrayList<>();
		allGeohashKeys.add(centerKey);
		allGeohashKeys.addAll(neighborKeys);

		// 2. mget으로 한 번에 조회
		List<String> cacheKeys = allGeohashKeys.stream()
			.map(key -> CACHE_KEY_PREFIX + key)
			.collect(Collectors.toList());

		List<Object> pipelineResults = redisTemplate.opsForValue().multiGet(cacheKeys);

		long pipelineTime = System.currentTimeMillis() - startTime;
		log.debug("Redis MGET 조회 완료 ({}ms, {}개 키)", pipelineTime, allGeohashKeys.size());

		// 3. 결과 처리
		List<HospitalWebResponse> cachedHospitals = new ArrayList<>();
		boolean allHit = true;

		for (int i = 0; i < allGeohashKeys.size(); i++) {
			String cacheKey = cacheKeys.get(i);
			Object cached = pipelineResults != null ? pipelineResults.get(i) : null;

			if (cached != null && cached instanceof List) {
				@SuppressWarnings("unchecked")
				List<HospitalWebResponse> hospitals = (List<HospitalWebResponse>) cached;
				cachedHospitals.addAll(hospitals);
				log.debug("Redis GET: {} → HIT ({}개 병원)", cacheKey, hospitals.size());
			} else {
				log.debug("Redis GET: {} → MISS", cacheKey);
				allHit = false;
				// 미스가 있어도 계속 진행 (로깅 목적)
			}
		}

		long totalTime = System.currentTimeMillis() - startTime;

		if (allHit) {
			log.info("캐시 완전 HIT: {}개 병원 ({}ms)", cachedHospitals.size(), totalTime);
			return cachedHospitals;
		} else {
			log.info("캐시 MISS 발생 ({}ms)", totalTime);
			return null;
		}
	}

	/**
	 * MISS된 격자만 추적 (백그라운드 캐싱 최적화용)
	 *
	 * @param userLat 사용자 위도
	 * @param userLon 사용자 경도
	 * @return MISS된 격자 키 Set (이미 캐시된 격자는 제외)
	 */
	public Set<String> getMissedGrids(double userLat, double userLon) {
		// 1. 중심 격자 + 인접 8개 격자 키 생성
		String centerKey = getGeohash(userLat, userLon);
		Set<String> neighborKeys = getNeighborGeohashes(userLat, userLon);

		List<String> allGeohashKeys = new ArrayList<>();
		allGeohashKeys.add(centerKey);
		allGeohashKeys.addAll(neighborKeys);

		// 2. mget으로 한 번에 조회
		List<String> cacheKeys = allGeohashKeys.stream()
			.map(key -> CACHE_KEY_PREFIX + key)
			.collect(Collectors.toList());

		List<Object> pipelineResults = redisTemplate.opsForValue().multiGet(cacheKeys);

		// 3. MISS된 격자만 추출
		Set<String> missedGrids = new HashSet<>();
		for (int i = 0; i < allGeohashKeys.size(); i++) {
			Object cached = pipelineResults != null ? pipelineResults.get(i) : null;
			if (cached == null) {
				missedGrids.add(allGeohashKeys.get(i));
			}
		}

		log.debug("캐시 상태: {}개 HIT, {}개 MISS", 9 - missedGrids.size(), missedGrids.size());
		return missedGrids;
	}

	/**
	 * MISS된 격자들을 각각 독립적으로 조회하여 비동기 캐싱
	 *
	 * @param userLat 사용자 위도
	 * @param userLon 사용자 경도
	 */
	@Async("hospitalTaskExecutor")
	public void cacheHospitalsByGridAsync(double userLat, double userLon) {
		long startTime = System.currentTimeMillis();
		log.info("=== 백그라운드 격자 캐싱 시작 ===");

		// 1. MISS된 격자만 가져오기 (이미 캐시된 격자는 제외)
		Set<String> missedGrids = getMissedGrids(userLat, userLon);

		if (missedGrids.isEmpty()) {
			log.info("모든 격자가 이미 캐시되어 있음 - 캐싱 작업 스킵 ✅");
			return;
		}

		log.info("캐싱 대상: MISS된 {}개 격자 (9개 중 {}개는 이미 캐시됨)",
			missedGrids.size(), 9 - missedGrids.size());

		// 2. 각 격자별로 독립적으로 DB 조회 및 캐싱 (병렬 처리)
		List<CompletableFuture<Void>> cachingFutures = missedGrids.stream()
			.map(geohashKey -> CompletableFuture.runAsync(() -> {
				try {
					// 각 격자의 MBR 범위로 독립적으로 DB 조회
					List<HospitalWebResponse> hospitals = fetchHospitalsForGrid(geohashKey);

					// Redis 캐싱
					String cacheKey = CACHE_KEY_PREFIX + geohashKey;
					if (!hospitals.isEmpty()) {
						redisTemplate.opsForValue().set(cacheKey, hospitals, CACHE_TTL_HOURS, TimeUnit.HOURS);
						log.info("격자 캐싱 완료: {} ({}개 병원)", cacheKey, hospitals.size());
					} else {
						redisTemplate.opsForValue().set(cacheKey, List.of(), CACHE_TTL_HOURS, TimeUnit.HOURS);
						log.debug("빈 격자 캐싱: {}", cacheKey);
					}
				} catch (Exception e) {
					log.error("격자 {} 캐싱 실패: {}", geohashKey, e.getMessage());
				}
			}, hospitalTaskExecutor))
			.collect(Collectors.toList());

		// 3. 모든 캐싱 완료 대기
		CompletableFuture.allOf(cachingFutures.toArray(new CompletableFuture[0])).join();

		long totalTime = System.currentTimeMillis() - startTime;
		log.info("=== 백그라운드 격자 캐싱 완료: {}개 격자, {}ms ===", missedGrids.size(), totalTime);
	}

	/**
	 * 단일 지오해시 생성
	 */
	private String getGeohash(double lat, double lon) {
		return GeoHash.withCharacterPrecision(lat, lon, GEOHASH_PRECISION).toBase32();
	}

	/**
	 * 캐시 결과를 담는 DTO
	 */
	public static class CacheResult {
		private final List<HospitalWebResponse> cachedHospitals;
		private final Set<String> missedCenterKey;
		private final Set<String> missedNeighborKeys;

		public CacheResult(List<HospitalWebResponse> cachedHospitals,
						   Set<String> missedCenterKey,
						   Set<String> missedNeighborKeys) {
			this.cachedHospitals = cachedHospitals;
			this.missedCenterKey = missedCenterKey;
			this.missedNeighborKeys = missedNeighborKeys;
		}

		public List<HospitalWebResponse> getCachedHospitals() {
			return cachedHospitals;
		}

		public Set<String> getMissedCenterKey() {
			return missedCenterKey;
		}

		public Set<String> getMissedNeighborKeys() {
			return missedNeighborKeys;
		}

		public Set<String> getAllMissedKeys() {
			Set<String> allKeys = new HashSet<>();
			allKeys.addAll(missedCenterKey);
			allKeys.addAll(missedNeighborKeys);
			return allKeys;
		}

		public boolean hasMisses() {
			return !missedCenterKey.isEmpty() || !missedNeighborKeys.isEmpty();
		}
	}

}
