package com.hospital.service;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hospital.async.EmergencyLocationAsyncRunner;
import com.hospital.repository.EmergencyLocationRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class EmergencyLocationApiService {

	private final EmergencyLocationAsyncRunner emergencyLocationAsyncRunner;
	private final EmergencyLocationRepository emergencyLocationRepository;

	@Autowired
	public EmergencyLocationApiService(EmergencyLocationAsyncRunner emergencyLocationAsyncRunner,
			EmergencyLocationRepository emergencyLocationRepository) {
		this.emergencyLocationAsyncRunner = emergencyLocationAsyncRunner;
		this.emergencyLocationRepository = emergencyLocationRepository;
	}

	/**
	 * 응급실 코드 데이터 수집 및 저장
	 */
	public void saveEmergencyLocations() {
		log.info("응급실 위치 데이터 수집 시작");
		// 기존 데이터 삭제
		emergencyLocationRepository.deleteAllEmergencyLocations();

		// AsyncRunner 카운터 초기화
		emergencyLocationAsyncRunner.resetCounter();

		// 비동기 수집 시작
		emergencyLocationAsyncRunner.runAsync(1, 500);

		log.info("질병 통계 데이터 수집 시작됨");
	}

	public int getCompletedCount() {
		return emergencyLocationAsyncRunner.getCompletedCount();
	}

	public int getFailedCount() {
		return emergencyLocationAsyncRunner.getFailedCount();
	}

	public int getInsertedCount() {
		return emergencyLocationAsyncRunner.getInsertedCount();
	}
}
