package com.hospital.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.config.RegionConfig;
import com.hospital.converter.HospitalConverter;
import com.hospital.dto.HospitalWebResponse;
import com.hospital.entity.HospitalMain;
import com.hospital.repository.HospitalJdbcRepository;
import com.hospital.repository.HospitalMainApiRepository;
import com.hospital.util.DistanceCalculator;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@Transactional(readOnly = true)
public class HospitalWebService {

	private final HospitalJdbcRepository hospitalJdbcRepository;
	private final DistanceCalculator distanceCalculator;

	private static final double KM_PER_DEGREE_LAT = 110.0;

	@Autowired
	public HospitalWebService(HospitalJdbcRepository hospitalJdbcRepository, DistanceCalculator distanceCalculator) {

		this.hospitalJdbcRepository = hospitalJdbcRepository;
		this.distanceCalculator = distanceCalculator;
	}

	public List<HospitalWebResponse> getOptimizedHospitalsV2(double userLat, double userLng, double radius) {
		long startTime = System.currentTimeMillis();
		log.info("=== JDBC Optimized Query ===");

		// MBR 계산
		double deltaDegreeY = radius / KM_PER_DEGREE_LAT;
		double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(userLat));
		double deltaDegreeX = radius / kmPerDegreeLon;

		double minLon = userLng - deltaDegreeX;
		double maxLon = userLng + deltaDegreeX;
		double minLat = userLat - deltaDegreeY;
		double maxLat = userLat + deltaDegreeY;

		// JDBC로 조회
		List<HospitalWebResponse> result = hospitalJdbcRepository.findByMBRDirect(minLon, maxLon, minLat, maxLat);

		log.info("Total elapsed: {}ms", System.currentTimeMillis() - startTime);
		return result;
	}

	// 진료과 필터링 + limit 적용 버전
	public List<HospitalWebResponse> getOptimizedHospitalsV2(
			double userLat,
			double userLng,
			double radius,
			List<String> departments,
			Integer limit) {

		long startTime = System.currentTimeMillis();
		log.info("=== Hospital Search (departments: {}, limit: {}) ===", departments, limit);

		// 1. MBR 계산 및 조회
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


		log.info("Total elapsed: {}ms", System.currentTimeMillis() - startTime);
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
