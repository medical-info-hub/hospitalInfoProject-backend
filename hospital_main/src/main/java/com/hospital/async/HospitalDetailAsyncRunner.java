package com.hospital.async;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.google.common.util.concurrent.RateLimiter;
import com.hospital.caller.HospitalDetailApiCaller;
import com.hospital.dto.HospitalDetailApiItem;
import com.hospital.dto.HospitalDetailApiResponse;
import com.hospital.parser.HospitalDetailApiParser;
import com.hospital.repository.HospitalDetailJdbcRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class HospitalDetailAsyncRunner {
	private final RateLimiter rateLimiter = RateLimiter.create(20); // 초당 8건 제한
	private final Executor executor; // 병렬 실행을 위한 스레드풀

	private final HospitalDetailApiCaller apiCaller;
	private final HospitalDetailApiParser parser;
	private final HospitalDetailJdbcRepository hospitalDetailJdbcRepository;

	private final AtomicInteger completedCount = new AtomicInteger(0);
	private final AtomicInteger failedCount = new AtomicInteger(0);
	private final AtomicInteger insertedCount = new AtomicInteger(0);
	private final AtomicInteger updatedCount = new AtomicInteger(0);

	// 재시도를 위한 실패 코드 추적
	private final Set<String> failedCodes = ConcurrentHashMap.newKeySet();

	private static final int BATCH_SIZE = 100;
	private static final int CHUNK_SIZE = 100; // 병원코드 청크 단위

	@Autowired
	public HospitalDetailAsyncRunner(HospitalDetailApiCaller apiCaller, HospitalDetailApiParser parser,
			HospitalDetailJdbcRepository hospitalDetailJdbcRepository,
			@Qualifier("apiExecutor") Executor executor) {
		this.apiCaller = apiCaller;
		this.parser = parser;
		this.hospitalDetailJdbcRepository = hospitalDetailJdbcRepository;
		this.executor = executor;
	}

	@Async("apiExecutor")
	public CompletableFuture<Void> runBatchAsync(List<String> hospitalCodes) {
		log.info("멀티스레드 배치 시작: {}건", hospitalCodes.size());

		try {
			List<List<String>> partitions = partitionList(hospitalCodes, CHUNK_SIZE);

			// thread-safe한 Set 사용
			Set<String> processedCodes = ConcurrentHashMap.newKeySet();

			List<CompletableFuture<Void>> futures = partitions.stream().map(chunk -> CompletableFuture
					.runAsync(() -> processChunk(chunk, processedCodes), executor)).toList();

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

			log.info("모든 청크 처리 완료: 완료 {}, 실패 {}, 신규 {}, 수정 {}", completedCount.get(), failedCount.get(),
					insertedCount.get(), updatedCount.get());

			// 삭제 처리
			deleteObsoleteDetails(processedCodes);

			return CompletableFuture.completedFuture(null);

		} catch (Exception e) {
			failedCount.addAndGet(hospitalCodes.size());
			log.error("전체 배치 실패: {}", e.getMessage(), e);
			return CompletableFuture.failedFuture(e);
		}
	}

	private void processChunk(List<String> chunk, Set<String> processedCodes) {
		List<HospitalDetailApiItem> toInsert = new ArrayList<>();
		List<HospitalDetailApiItem> toUpdate = new ArrayList<>();

		// 청크 단위로 기존 데이터 조회
		Map<String, HospitalDetailApiItem> existingMap = loadExistingDetails(chunk);

		for (String hospitalCode : chunk) {
			rateLimiter.acquire();

			try {
				// API 호출
				String queryParams = "ykiho=" + hospitalCode;
				HospitalDetailApiResponse response = apiCaller.callApi(queryParams);

				// 파싱 (엔티티 변환 없이 ApiItem 직접 반환)
				List<HospitalDetailApiItem> parsed = parser.parse(response, hospitalCode);
				if (!parsed.isEmpty()) {
					for (HospitalDetailApiItem newItem : parsed) {
						HospitalDetailApiItem existing = existingMap.get(hospitalCode);
						if (existing != null) {
							// 기존 데이터가 있으면 업데이트 대상
							updateDetailFields(existing, newItem);
							toUpdate.add(existing);
						} else {
							// 신규 데이터
							toInsert.add(newItem);
						}
					}
				}

				completedCount.incrementAndGet();
				processedCodes.add(hospitalCode); // 성공 처리된 코드 기록

			} catch (Exception e) {
				failedCount.incrementAndGet();
				failedCodes.add(hospitalCode); // 실패한 코드 추적
				log.error("API 호출 실패: {}", hospitalCode, e);
			}

			if (toInsert.size() + toUpdate.size() >= BATCH_SIZE) {
				saveBatchAndClear(toInsert, toUpdate);
			}
		}

		if (!toInsert.isEmpty() || !toUpdate.isEmpty()) {
			saveBatchAndClear(toInsert, toUpdate);
		}
	}

	private void deleteObsoleteDetails(Set<String> processedCodes) {
		List<String> allDbCodes = hospitalDetailJdbcRepository.findAllDistinctHospitalCodes();
		List<String> toDelete = allDbCodes.stream().filter(code -> !processedCodes.contains(code)).toList();

		if (!toDelete.isEmpty()) {
			hospitalDetailJdbcRepository.deleteByHospitalCodeIn(toDelete);
			log.info("폐업/삭제 처리 완료: {}건 삭제", toDelete.size());
		}
	}

	private synchronized int[] saveBatchAndClear(List<HospitalDetailApiItem> toInsert, List<HospitalDetailApiItem> toUpdate) {
		int inserted = 0, updated = 0;

		if (!toInsert.isEmpty()) {
			hospitalDetailJdbcRepository.batchInsert(toInsert);
			inserted = toInsert.size();
			insertedCount.addAndGet(inserted);
			toInsert.clear();
		}

		if (!toUpdate.isEmpty()) {
			hospitalDetailJdbcRepository.batchUpdate(toUpdate);
			updated = toUpdate.size();
			updatedCount.addAndGet(updated);
			toUpdate.clear();
		}

		return new int[] { inserted, updated };
	}

	private Map<String, HospitalDetailApiItem> loadExistingDetails(List<String> hospitalCodes) {
		return hospitalDetailJdbcRepository.findByHospitalCodeInAsMap(hospitalCodes);
	}

	private List<List<String>> partitionList(List<String> list, int size) {
		List<List<String>> partitions = new ArrayList<>();
		for (int i = 0; i < list.size(); i += size) {
			partitions.add(list.subList(i, Math.min(i + size, list.size())));
		}
		return partitions;
	}

	// 필드별 업데이트
	private void updateDetailFields(HospitalDetailApiItem existing, HospitalDetailApiItem newData) {

		if (!Objects.equals(existing.getParkQty(), newData.getParkQty())) {
			existing.setParkQty(newData.getParkQty());
		}
		if (!Objects.equals(existing.getParkXpnsYn(), newData.getParkXpnsYn())) {
			existing.setParkXpnsYn(newData.getParkXpnsYn());
		}

		if (!Objects.equals(existing.getLunchWeek(), newData.getLunchWeek())) {
			existing.setLunchWeek(newData.getLunchWeek());
		}

		if (!Objects.equals(existing.getNoTrmtHoli(), newData.getNoTrmtHoli())) {
			existing.setNoTrmtHoli(newData.getNoTrmtHoli());
		}

		if (!Objects.equals(existing.getTrmtMonStart(), newData.getTrmtMonStart())) {
			existing.setTrmtMonStart(newData.getTrmtMonStart());
		}
		if (!Objects.equals(existing.getTrmtMonEnd(), newData.getTrmtMonEnd())) {
			existing.setTrmtMonEnd(newData.getTrmtMonEnd());
		}
		if (!Objects.equals(existing.getTrmtTueStart(), newData.getTrmtTueStart())) {
			existing.setTrmtTueStart(newData.getTrmtTueStart());
		}
		if (!Objects.equals(existing.getTrmtTueEnd(), newData.getTrmtTueEnd())) {
			existing.setTrmtTueEnd(newData.getTrmtTueEnd());
		}
		if (!Objects.equals(existing.getTrmtWedStart(), newData.getTrmtWedStart())) {
			existing.setTrmtWedStart(newData.getTrmtWedStart());
		}
		if (!Objects.equals(existing.getTrmtWedEnd(), newData.getTrmtWedEnd())) {
			existing.setTrmtWedEnd(newData.getTrmtWedEnd());
		}
		if (!Objects.equals(existing.getTrmtThurStart(), newData.getTrmtThurStart())) {
			existing.setTrmtThurStart(newData.getTrmtThurStart());
		}
		if (!Objects.equals(existing.getTrmtThurEnd(), newData.getTrmtThurEnd())) {
			existing.setTrmtThurEnd(newData.getTrmtThurEnd());
		}
		if (!Objects.equals(existing.getTrmtFriStart(), newData.getTrmtFriStart())) {
			existing.setTrmtFriStart(newData.getTrmtFriStart());
		}
		if (!Objects.equals(existing.getTrmtFriEnd(), newData.getTrmtFriEnd())) {
			existing.setTrmtFriEnd(newData.getTrmtFriEnd());
		}
		if (!Objects.equals(existing.getTrmtSatStart(), newData.getTrmtSatStart())) {
			existing.setTrmtSatStart(newData.getTrmtSatStart());
		}
		if (!Objects.equals(existing.getTrmtSatEnd(), newData.getTrmtSatEnd())) {
			existing.setTrmtSatEnd(newData.getTrmtSatEnd());
		}
		if (!Objects.equals(existing.getTrmtSunStart(), newData.getTrmtSunStart())) {
			existing.setTrmtSunStart(newData.getTrmtSunStart());
		}
		if (!Objects.equals(existing.getTrmtSunEnd(), newData.getTrmtSunEnd())) {
			existing.setTrmtSunEnd(newData.getTrmtSunEnd());
		}
	}

	// 상태 관리 메서드들
	public void resetCounter() {
		completedCount.set(0);
		failedCount.set(0);
		insertedCount.set(0);
		updatedCount.set(0);
	}

	public void setTotalCount(int totalCount) {
		completedCount.set(0);
		failedCount.set(0);
		insertedCount.set(0);
		updatedCount.set(0);
	}

	public int getCompletedCount() {
		return completedCount.get();
	}

	public int getFailedCount() {
		return failedCount.get();
	}

	public int getInsertedCount() {
		return insertedCount.get();
	}

	public int getUpdatedCount() {
		return updatedCount.get();
	}

	public Set<String> getFailedCodes() {
		return new HashSet<>(failedCodes);
	}

	/**
	 * 실패한 병원코드만 재시도 (Exponential Backoff 적용)
	 * @param maxRetries 최대 재시도 횟수
	 * @return CompletableFuture로 감싼 최종 실패 코드 목록
	 */
	public CompletableFuture<Set<String>> retryFailedCodesAsync(int maxRetries) {
		return CompletableFuture.supplyAsync(() -> {
			if (failedCodes.isEmpty()) {
				log.info("재시도할 실패 건이 없습니다.");
				return Collections.emptySet();
			}

			List<String> toRetry = new ArrayList<>(failedCodes);
			int retryAttempt = 1;

			while (!toRetry.isEmpty() && retryAttempt <= maxRetries) {
				int waitSeconds = (int) Math.pow(2, retryAttempt - 1); // 1초 → 2초 → 4초
				log.info("{}차 재시도 시작: {}건 ({}초 대기 후)", retryAttempt, toRetry.size(), waitSeconds);

				try {
					// Exponential Backoff 대기
					Thread.sleep(waitSeconds * 1000);

					// 이전 실패 코드 초기화
					failedCodes.clear();

					// 재시도 실행 (CompletableFuture로 병렬 처리)
					List<List<String>> partitions = partitionList(toRetry, CHUNK_SIZE);
					Set<String> processedCodes = ConcurrentHashMap.newKeySet();

					List<CompletableFuture<Void>> futures = partitions.stream()
						.map(chunk -> CompletableFuture.runAsync(() -> processChunk(chunk, processedCodes), executor))
						.toList();

					CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

					log.info("{}차 재시도 완료: 성공 {}, 실패 {}",
						retryAttempt, toRetry.size() - failedCodes.size(), failedCodes.size());

					// 다시 실패한 코드만 추출
					toRetry = new ArrayList<>(failedCodes);
					retryAttempt++;

				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.error("재시도 중 인터럽트 발생", e);
					break;
				}
			}

			if (!failedCodes.isEmpty()) {
				log.warn("최종 실패: {}건 - {}", failedCodes.size(), failedCodes);
			} else {
				log.info("모든 재시도 완료: 최종 실패 0건");
			}

			return new HashSet<>(failedCodes);
		}, executor);
	}

	/**
	 * 카운터 및 실패 코드 완전 초기화
	 */
	public void resetAll() {
		resetCounter();
		failedCodes.clear();
	}
}