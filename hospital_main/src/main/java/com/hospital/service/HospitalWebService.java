package com.hospital.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.dto.HospitalWebResponse;
import com.hospital.repository.HospitalJdbcRepository;
import com.hospital.util.DistanceCalculator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
public class HospitalWebService {

	private final HospitalJdbcRepository hospitalJdbcRepository;
	private final DistanceCalculator distanceCalculator;
	private final GeohashCacheService geohashCacheService;

	private static final double KM_PER_DEGREE_LAT = 110.0;

	@Autowired
	public HospitalWebService(
		HospitalJdbcRepository hospitalJdbcRepository,
		DistanceCalculator distanceCalculator,
		GeohashCacheService geohashCacheService) {

		this.hospitalJdbcRepository = hospitalJdbcRepository;
		this.distanceCalculator = distanceCalculator;
		this.geohashCacheService = geohashCacheService;
	}

	/**
	 * 병원 검색 V2 - 캐시 우선 최적화 버전
	 * 1. 캐시 우선 조회 시도 -> 모든 격자 HIT시 즉시 반환 (빠름)
	 * 2. 하나라도 MISS시 MBR DB 조회 + 백그라운드 캐싱 (정확함)
	 */
	public List<HospitalWebResponse> getOptimizedHospitalsV2(double userLat, double userLng, double radius) {
		long totalStartTime = System.currentTimeMillis();
		log.info("=== 병원 검색 시작 (위치: {}, {}, 반경: {}km) ===", userLat, userLng, radius);

		// 1. 캐시 우선 조회 시도
		long cacheStartTime = System.currentTimeMillis();
		List<HospitalWebResponse> cachedResult = geohashCacheService.getFromCacheIfAllHit(userLat, userLng);
		long cacheTime = System.currentTimeMillis() - cacheStartTime;

		if (cachedResult != null) {
			// 캐시 완전 HIT - MBR 필터링 후 즉시 반환
			long filterStartTime = System.currentTimeMillis();
			double deltaDegreeY = radius / KM_PER_DEGREE_LAT;
			double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(userLat));
			double deltaDegreeX = radius / kmPerDegreeLon;

			double minLon = userLng - deltaDegreeX;
			double maxLon = userLng + deltaDegreeX;
			double minLat = userLat - deltaDegreeY;
			double maxLat = userLat + deltaDegreeY;

			// 중복 제거
			Map<String, HospitalWebResponse> uniqueHospitals = new HashMap<>();
			cachedResult.forEach(h -> uniqueHospitals.put(h.getHospitalCode(), h));

			// MBR 필터링
			List<HospitalWebResponse> result = uniqueHospitals.values().stream()
				.filter(h -> h.getCoordinateX() >= minLon && h.getCoordinateX() <= maxLon
						  && h.getCoordinateY() >= minLat && h.getCoordinateY() <= maxLat)
				.collect(Collectors.toList());

			long filterTime = System.currentTimeMillis() - filterStartTime;
			long totalTime = System.currentTimeMillis() - totalStartTime;

			log.info("⏱️ 캐시 HIT 경로: 캐시 조회 {}ms | MBR 필터링 {}ms | 총 {}ms",
				cacheTime, filterTime, totalTime);
			log.info("최종 출력: {}개 (캐시에서 반환)", result.size());

			return result;
		}

		// 2. 캐시 MISS - MBR DB 조회
		long mbrStartTime = System.currentTimeMillis();
		double deltaDegreeY = radius / KM_PER_DEGREE_LAT;
		double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(userLat));
		double deltaDegreeX = radius / kmPerDegreeLon;

		double minLon = userLng - deltaDegreeX;
		double maxLon = userLng + deltaDegreeX;
		double minLat = userLat - deltaDegreeY;
		double maxLat = userLat + deltaDegreeY;
		long mbrTime = System.currentTimeMillis() - mbrStartTime;

		long dbStartTime = System.currentTimeMillis();
		List<HospitalWebResponse> hospitals = hospitalJdbcRepository.findByMBRDirect(
			minLon, maxLon, minLat, maxLat
		);
		long dbTime = System.currentTimeMillis() - dbStartTime;

		log.info("캐시 MISS - MBR 직접 조회 완료: {}개", hospitals.size());

		// 3. 백그라운드에서 MISS된 격자만 독립적으로 DB 조회 및 캐싱
		geohashCacheService.cacheHospitalsByGridAsync(userLat, userLng);

		long totalTime = System.currentTimeMillis() - totalStartTime;

		log.info("⏱️ 캐시 MISS 경로: 캐시 조회 {}ms | MBR 계산 {}ms | DB 조회 {}ms | 총 {}ms (격자 캐싱은 백그라운드 진행 중)",
			cacheTime, mbrTime, dbTime, totalTime);
		log.info("최종 출력: {}개", hospitals.size());

		return hospitals;
	}


	// 진료과 필터링 + limit 적용 버전 (챗봇용 - 캐싱 없음)
	public List<HospitalWebResponse> getOptimizedHospitalsV2(
			double userLat,
			double userLng,
			double radius,
			List<String> departments,
			Integer limit) {

		long startTime = System.currentTimeMillis();
		log.info("=== 챗봇 병원 검색 (진료과: {}, limit: {}) ===", departments, limit);

		// 1. MBR 계산 및 DB 직접 조회
		double deltaDegreeY = radius / KM_PER_DEGREE_LAT;
		double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(userLat));
		double deltaDegreeX = radius / kmPerDegreeLon;

		double minLon = userLng - deltaDegreeX;
		double maxLon = userLng + deltaDegreeX;
		double minLat = userLat - deltaDegreeY;
		double maxLat = userLat + deltaDegreeY;

		List<HospitalWebResponse> hospitals = hospitalJdbcRepository.findByMBRDirect(
			minLon, maxLon, minLat, maxLat
		);

		log.info("MBR 조회 완료: {}개", hospitals.size());

		// 2. 진료과 필터링
		if (departments != null && !departments.isEmpty()) {
			hospitals = filterByDepartments(hospitals, departments);
			log.info("진료과 필터링 완료: {}개", hospitals.size());
		}

		// 3. 거리순 정렬
		hospitals = sortByDistance(hospitals, userLat, userLng);
		log.info("거리순 정렬 완료");

		// 4. limit 적용
		hospitals = applyLimit(hospitals, limit);

		log.info("총 소요시간: {}ms", System.currentTimeMillis() - startTime);
		return hospitals;
	}

	// 진료과 필터링 (재사용 가능)
	private List<HospitalWebResponse> filterByDepartments(
			List<HospitalWebResponse> hospitals,
			List<String> departments) {

		return hospitals.stream()
			.filter(hospital -> {
				List<String> hospitalDepts = hospital.getMedicalSubjects();
				if (hospitalDepts == null || hospitalDepts.isEmpty()) {
					return false;
				}
				return departments.stream()
					.anyMatch(dept -> hospitalDepts.contains(dept));
			})
			.collect(Collectors.toList());
	}

	// 거리순 정렬 (재사용 가능)
	private List<HospitalWebResponse> sortByDistance(
			List<HospitalWebResponse> hospitals,
			double userLat,
			double userLng) {

		hospitals.sort((h1, h2) -> {
			double dist1 = distanceCalculator.calculateDistance(
				userLat, userLng, h1.getCoordinateY(), h1.getCoordinateX()
			);
			double dist2 = distanceCalculator.calculateDistance(
				userLat, userLng, h2.getCoordinateY(), h2.getCoordinateX()
			);
			return Double.compare(dist1, dist2);
		});
		return hospitals;
	}

	// limit 적용 (재사용 가능)
	private List<HospitalWebResponse> applyLimit(
			List<HospitalWebResponse> hospitals,
			Integer limit) {

		if (limit != null && limit > 0 && hospitals.size() > limit) {
			return hospitals.subList(0, limit);
		}
		return hospitals;
	}


}
