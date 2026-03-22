package com.hospital.async;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.hospital.caller.DiseaseStatsApiCaller;
import com.hospital.dto.DiseaseStatsApiResponse;
import com.hospital.entity.DiseaseStats;
import com.hospital.parser.DiseaseStatsApiParser;
import com.hospital.repository.DiseaseStatsRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DiseaseStatsAsyncRunner {

    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger insertedCount = new AtomicInteger(0);

    private final DiseaseStatsApiCaller apiCaller;
    private final DiseaseStatsApiParser parser;
    private final DiseaseStatsRepository diseaseStatsRepository;

    private static final int BATCH_SIZE = 100;

    @Autowired
    public DiseaseStatsAsyncRunner(DiseaseStatsApiCaller apiCaller,
                                    DiseaseStatsApiParser parser,
                                    DiseaseStatsRepository diseaseStatsRepository) {
        this.apiCaller = apiCaller;
        this.parser = parser;
        this.diseaseStatsRepository = diseaseStatsRepository;
    }

    @Async("apiExecutor")
    public void runAsync(int startYear, int endYear) {
        try {
            log.info("질병 통계 수집 시작 - 기간: {}-{}", startYear, endYear);

            List<DiseaseStats> allDiseaseStats = new ArrayList<>();
            int pageNo = 1;
            boolean hasMorePages = true;

            while (hasMorePages) {
                log.debug("페이지 {} 호출 중...", pageNo);

                DiseaseStatsApiResponse response = apiCaller.callApi(startYear, endYear, pageNo);
                List<DiseaseStats> diseaseStats = parser.parseDiseaseStats(response);

                if (diseaseStats.isEmpty()) {
                    log.info("페이지 {}: 더 이상 데이터 없음", pageNo);
                    break;
                }

                allDiseaseStats.addAll(diseaseStats);
                log.info("페이지 {} 수집 완료: {}건", pageNo, diseaseStats.size());

                pageNo++;

                // 100건보다 적으면 마지막 페이지로 간주
                hasMorePages = diseaseStats.size() >= 100;
            }

            // 배치 저장
            int insertedTotal = 0;
            for (int i = 0; i < allDiseaseStats.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, allDiseaseStats.size());
                List<DiseaseStats> batch = allDiseaseStats.subList(i, end);
                diseaseStatsRepository.saveAll(batch);
                insertedTotal += batch.size();
                log.info("배치 저장: {}건 완료 (누적: {}건)", batch.size(), insertedTotal);
            }

            insertedCount.addAndGet(insertedTotal);
            completedCount.incrementAndGet();

            log.info("질병 통계 수집 완료 - 기간: {}-{}, 총 {}건 저장", startYear, endYear, insertedTotal);

        } catch (Exception e) {
            failedCount.incrementAndGet();
            log.error("질병 통계 수집 실패 - 기간: {}-{}, 오류: {}", startYear, endYear, e.getMessage(), e);
        }
    }

    // 상태 관리 메서드
    public int getCompletedCount() {
        return completedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public int getInsertedCount() {
        return insertedCount.get();
    }

    public void resetCounter() {
        completedCount.set(0);
        failedCount.set(0);
        insertedCount.set(0);
    }
}
