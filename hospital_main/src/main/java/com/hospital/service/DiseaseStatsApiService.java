package com.hospital.service;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hospital.async.DiseaseStatsAsyncRunner;
import com.hospital.repository.DiseaseStatsRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DiseaseStatsApiService {

	private final DiseaseStatsAsyncRunner diseaseStatsAsyncRunner;
	private final DiseaseStatsRepository diseaseStatsRepository;

	@Autowired
	public DiseaseStatsApiService(DiseaseStatsAsyncRunner diseaseStatsAsyncRunner,
	                           DiseaseStatsRepository diseaseStatsRepository) {
		this.diseaseStatsAsyncRunner = diseaseStatsAsyncRunner;
		this.diseaseStatsRepository = diseaseStatsRepository;
	}

	public void updateDiseaseStats(int startYear, int endYear) {
		log.info("질병 통계 데이터 수집 시작 - 기간: {}-{}", startYear, endYear);

		// 기존 데이터 삭제
		diseaseStatsRepository.deleteAllInBatch();

		// AsyncRunner 카운터 초기화
		diseaseStatsAsyncRunner.resetCounter();

		// 비동기 수집 시작
		diseaseStatsAsyncRunner.runAsync(startYear, endYear);

		log.info("질병 통계 데이터 수집 시작됨");
	}

	public int getCompletedCount() {
		return diseaseStatsAsyncRunner.getCompletedCount();
	}

	public int getFailedCount() {
		return diseaseStatsAsyncRunner.getFailedCount();
	}

	public int getInsertedCount() {
		return diseaseStatsAsyncRunner.getInsertedCount();
	}

}
