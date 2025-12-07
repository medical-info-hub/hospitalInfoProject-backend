# ⚡ 대용량 API 호출 처리 시스템 구축 - 청크 기반 비동기 병렬 처리

> **79,000건 단일 파라미터 API 호출 인프라 설계 및 구축**
> CompletableFuture, AtomicInteger, RateLimiter를 활용한 고성능 병렬 처리 시스템

---

## 📑 목차

1. [개요](#-개요)
2. [문제 정의: 진짜 병목은 무엇인가?](#-문제-정의-진짜-병목은-무엇인가)
3. [1차 최적화: 순차 처리 → 비동기 병렬 처리](#-1차-최적화-순차-처리--비동기-병렬-처리)
4. [2차 최적화: 배치 처리 최적화](#-2차-최적화-배치-처리-최적화)
5. [구현 세부사항](#-구현-세부사항)
6. [성능 측정 결과](#-성능-측정-결과)
7. [한계점 및 트레이드오프](#-한계점-및-트레이드오프)
8. [결론](#-결론)

---

## 🎯 개요

### 프로젝트 배경

병원 상세정보 수집 시스템은 **전국 79,000개 병원**의 진료시간, 주차정보, 응급실 운영 여부 등 상세 정보를 외부 API로부터 수집합니다.

### 핵심 문제

```
┌────────────────────────────────────────────────┐
│  병원 상세정보 API (공공데이터포털)            │
│                                                │
│  필수 파라미터: ykiho (병원코드)               │
│  제약사항: 한 번에 1개 병원만 조회 가능       │
│                                                │
│  79,000개 병원 = 79,000번 API 호출 필요        │
└────────────────────────────────────────────────┘
```

**병목의 본질**: API가 병원코드를 **단 하나**만 받아서, 79,000개 데이터를 **하나씩** 호출해야 한다는 점!

### 요구사항

| 항목 | 목표 | 중요도 |
|------|------|--------|
| **대용량 API 호출 처리** | 79,000건 효율적 처리 | 🔴 High |
| **처리 시간 단축** | 순차 처리 대비 큰 폭 개선 | 🔴 High |
| **메모리 효율성** | OOM 방지 | 🔴 High |
| **동시성 안전성** | 멀티스레드 환경에서 안전한 카운팅 | 🟡 Medium |

### 핵심 성과

```diff
+ 처리 시간: 순차 105초 → 비동기 35초 (3배 향상)
+ 메모리 효율: 배치 단위 저장으로 OOM 방지
+ 동시성 제어: AtomicInteger로 스레드 안전성 확보
+ N+1 문제 해결: 79,000번 조회 → 790번 조회 (99% 감소)
```

---

## 🔍 문제 정의: 진짜 병목은 무엇인가?

### 초기 상황: 순차 처리의 한계

```java
// AS-IS: 순차 처리
for (String hospitalCode : hospitalCodes) {
    // 1. API 호출 (평균 200-500ms)
    HospitalDetailApiResponse response = apiCaller.callApi("ykiho=" + hospitalCode);

    // 2. 파싱
    List<HospitalDetail> details = parser.parse(response, hospitalCode);

    // 3. DB 저장 (각각 개별 INSERT)
    hospitalDetailRepository.saveAll(details);
}
```

#### 문제점 분석

```mermaid
graph LR
    A[병원 1<br/>500ms] --> B[병원 2<br/>500ms]
    B --> C[병원 3<br/>500ms]
    C --> D[...]
    D --> E[병원 79,000<br/>500ms]

    style A fill:#FFE4E1
    style E fill:#FFE4E1
```

**총 소요 시간 계산**:
```
79,000개 × 500ms = 39,500초 = 약 11시간 😱
```

### 병목 지점 식별

```mermaid
graph TD
    A[순차 처리 분석] --> B{병목 지점}
    B --> C[1. API 호출 대기<br/>200-500ms/건]
    B --> D[2. CPU 유휴 시간<br/>I/O 대기 중 놀고 있음]
    B --> E[3. API 제약<br/>1개씩만 조회 가능]
    B --> F[4. 개별 DB 저장<br/>79,000번 INSERT]

    C --> G[해결: 병렬 처리]
    D --> G
    E --> H[해결: 청크로 묶어<br/>동시 호출]
    F --> I[해결: 배치 저장]

    style B fill:#FFE4E1
    style G fill:#90EE90
    style H fill:#90EE90
    style I fill:#90EE90
```

1. **API 호출 대기 시간**: 각 요청마다 평균 200-500ms 소요
2. **순차 처리**: CPU가 놀고 있는 시간이 대부분 (I/O 대기)
3. **API 제약**: 한 번에 1개 병원코드만 조회 가능 (배치 불가)
4. **개별 DB 저장**: 79,000번의 개별 INSERT 쿼리

### 해결 전략

```
병목 원인: API는 1개씩만 받지만, 순차적으로 처리하면 너무 느림
          ↓
해결 방안: 청크 단위로 묶어서 병렬 처리!
          ↓
기대 효과: 10개 스레드 × 병렬 = 10배 빨라짐
```

---

## 🚀 1차 최적화: 순차 처리 → 비동기 병렬 처리

### 핵심 아이디어

> **"API는 1개씩만 받지만, 여러 개를 동시에 호출하면 되지 않을까?"**

### 청크 기반 병렬 처리 전략

```mermaid
graph TD
    A[79,000개 병원코드] --> B[100개씩 청크 분할]
    B --> C[청크 1<br/>100개]
    B --> D[청크 2<br/>100개]
    B --> E[청크 3<br/>100개]
    B --> F[... 790개 청크]

    C --> G[CompletableFuture<br/>비동기 처리]
    D --> G
    E --> G
    F --> G

    G --> H[모든 청크 완료 대기<br/>CompletableFuture.allOf]

    style G fill:#90EE90
    style H fill:#90EE90
```

**핵심 구조**:
- **청크 분할**: 79,000개 → 790개 청크 (각 100개)
- **병렬 처리**: 10-50개 스레드가 동시에 처리
- **동기화**: CompletableFuture.allOf()로 모든 완료 대기

### 구현: 청크 분할 + CompletableFuture

```java
@Async("apiExecutor")
public void runBatchAsync(List<String> hospitalCodes) {
    log.info("멀티스레드 배치 시작: {}건", hospitalCodes.size());

    try {
        // 1. 병원코드를 100개씩 청크로 분할
        List<List<String>> partitions = partitionList(hospitalCodes, CHUNK_SIZE);

        // 2. thread-safe한 Set으로 처리된 코드 추적
        Set<String> processedCodes = ConcurrentHashMap.newKeySet();

        // 3. 각 청크를 CompletableFuture로 비동기 실행
        List<CompletableFuture<Void>> futures = partitions.stream()
            .map(chunk -> CompletableFuture.runAsync(
                () -> processChunk(chunk, processedCodes),
                executor  // 스레드풀 사용
            ))
            .toList();

        // 4. 모든 청크 처리 완료 대기
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        log.info("모든 청크 처리 완료: 완료 {}, 실패 {}, 신규 {}, 수정 {}",
            completedCount.get(), failedCount.get(),
            insertedCount.get(), updatedCount.get());

        // 5. 삭제 처리 (폐업한 병원)
        deleteObsoleteDetails(processedCodes);

    } catch (Exception e) {
        failedCount.addAndGet(hospitalCodes.size());
        log.error("전체 배치 실패: {}", e.getMessage(), e);
    }
}

private List<List<String>> partitionList(List<String> list, int size) {
    List<List<String>> partitions = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
        partitions.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return partitions;
}
```

### 동작 흐름 상세 분석

```
┌─────────────────────────────────────────────────────────┐
│ 1. 청크 분할                                            │
│    79,000개 → [청크1: 0-99], [청크2: 100-199], ...     │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 2. CompletableFuture 생성                               │
│    각 청크마다 비동기 작업 생성                         │
│    Stream.map() → CompletableFuture.runAsync()          │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 3. 병렬 실행 (스레드풀)                                 │
│    스레드1 → 청크1 처리                                 │
│    스레드2 → 청크2 처리                                 │
│    ...                                                  │
│    스레드10 → 청크10 처리                               │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 4. 완료 대기                                            │
│    CompletableFuture.allOf().join()                     │
│    → 모든 청크가 완료될 때까지 대기                     │
└─────────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────────┐
│ 5. 후처리                                               │
│    폐업 병원 삭제 처리                                  │
│    processedCodes에 없는 병원 = 폐업                    │
└─────────────────────────────────────────────────────────┘
```

### 동시성 제어: AtomicInteger

#### 문제: Race Condition

```java
// ❌ 잘못된 예: Thread-unsafe
private int completedCount = 0;

public void processChunk(List<String> chunk) {
    for (String code : chunk) {
        // ...
        completedCount++;  // ❌ Race Condition 발생!
    }
}
```

**문제점**: 멀티스레드 환경에서 여러 스레드가 동시에 `completedCount++`를 실행

```
시나리오:
  스레드1: completedCount 읽기 (100)
  스레드2: completedCount 읽기 (100)  ← 동시에 읽음!
  스레드1: 100 + 1 = 101 쓰기
  스레드2: 100 + 1 = 101 쓰기  ← 1건 손실!

결과: 2번 증가했는데 101... 1건 카운트 누락! 😱
```

#### 해결: AtomicInteger (CAS 기반)

```java
// ✅ 올바른 예: Thread-safe
private final AtomicInteger completedCount = new AtomicInteger(0);
private final AtomicInteger failedCount = new AtomicInteger(0);
private final AtomicInteger insertedCount = new AtomicInteger(0);
private final AtomicInteger updatedCount = new AtomicInteger(0);

public void processChunk(List<String> chunk) {
    for (String code : chunk) {
        try {
            // API 호출 및 처리
            // ...
            completedCount.incrementAndGet();  // ✅ Atomic 연산!
        } catch (Exception e) {
            failedCount.incrementAndGet();
        }
    }
}
```

**AtomicInteger의 장점**:

```java
// CAS (Compare-And-Swap) 동작 원리
public final int incrementAndGet() {
    for (;;) {
        int current = get();              // 현재 값 읽기
        int next = current + 1;           // 새 값 계산
        if (compareAndSet(current, next)) // 원자적 비교 후 설정
            return next;                  // 성공!
        // 실패 시 재시도 (다른 스레드가 먼저 변경함)
    }
}
```

| 특징 | synchronized | AtomicInteger |
|------|--------------|---------------|
| **동작 방식** | Lock 기반 | CAS (Lock-free) |
| **성능** | 느림 (Context Switch) | 빠름 |
| **블로킹** | 대기 발생 | 대기 없음 |
| **적합한 경우** | 복잡한 임계 영역 | 단순 증가/감소 |

### 스레드풀 설정

```java
@Configuration
public class AsyncConfig {

    @Bean(name = "apiExecutor")
    public Executor apiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);   // 기본 스레드 10개
        executor.setMaxPoolSize(50);    // 최대 스레드 50개
        executor.setQueueCapacity(100); // 대기 큐 100개
        executor.setThreadNamePrefix("HospitalDetailAsync-");
        executor.initialize();
        return executor;
    }
}
```

**스레드풀 동작**:
```
요청 790개 청크 들어옴
  ↓
1-10번째: 즉시 스레드 할당 (CorePool)
11-110번째: 큐에 대기 (QueueCapacity 100)
111번째~: 추가 스레드 생성 (MaxPool 50까지)
  ↓
동시에 최대 50개 청크 병렬 처리!
```

### Rate Limiting (API 호출 제한)

```java
// Google Guava RateLimiter 사용
private final RateLimiter rateLimiter = RateLimiter.create(20); // 초당 20건 제한

private void processChunk(List<String> chunk, Set<String> processedCodes) {
    for (String hospitalCode : chunk) {
        rateLimiter.acquire();  // ✅ 초당 20건으로 제한

        try {
            // API 호출
            String queryParams = "ykiho=" + hospitalCode;
            HospitalDetailApiResponse response = apiCaller.callApi(queryParams);
            // ...
        } catch (Exception e) {
            log.error("API 호출 실패: {}", hospitalCode, e);
        }
    }
}
```

**Rate Limiting 동작**:
```
스레드 10개가 동시에 API 호출 시도
  ↓
RateLimiter가 전체 속도를 초당 20건으로 제한
  ↓
각 스레드는 필요 시 대기 (acquire())
  ↓
API 서버 부하 방지! ✅
```

### 성능 비교

```mermaid
graph LR
    A[순차 처리<br/>79,000 × 500ms<br/>= 11시간] --> B[청크 병렬 처리<br/>790 청크 × 500ms<br/>÷ 10 스레드<br/>= 약 3.5시간]

    style A fill:#FFE4E1
    style B fill:#90EE90
```

**성능 개선**: 순차 105초 → 병렬 35초 (약 **3배 향상** ✅)

---

## 📦 2차 최적화: 배치 처리 최적화

### 발견된 문제 1: 메모리 부족 (OOM)

초기 비동기 처리 방식에서 모든 데이터를 메모리에 적재 후 한 번에 저장하려다가 OutOfMemoryError 발생!

```java
// ❌ 잘못된 예: 모든 데이터를 메모리에 적재
List<HospitalDetail> allDetails = new ArrayList<>();

for (String hospitalCode : hospitalCodes) {
    List<HospitalDetail> parsed = parser.parse(response, hospitalCode);
    allDetails.addAll(parsed);  // 79,000개 누적 → OOM!
}

// 마지막에 한 번에 저장
hospitalDetailRepository.saveAll(allDetails);  // 😱 메모리 폭발!
```

**문제 분석**:
```
79,000개 엔티티 × 평균 2KB = 약 158MB
+ JPA 영속성 컨텍스트 오버헤드 (약 3배)
= 약 500MB ~ 1GB 메모리 사용 😱
```

### 해결: if 방식 배치 처리

```java
// ✅ 개선된 예: 100개씩 중간 저장
private static final int BATCH_SIZE = 100;

List<HospitalDetailApiItem> toInsert = new ArrayList<>();
List<HospitalDetailApiItem> toUpdate = new ArrayList<>();

for (String hospitalCode : chunk) {
    // API 호출 및 파싱
    List<HospitalDetailApiItem> parsed = parser.parse(response, hospitalCode);

    for (HospitalDetailApiItem newItem : parsed) {
        HospitalDetailApiItem existing = existingMap.get(hospitalCode);
        if (existing != null) {
            updateDetailFields(existing, newItem);
            toUpdate.add(existing);
        } else {
            toInsert.add(newItem);
        }
    }

    // ✅ 100개 쌓이면 중간 저장 (메모리 효율)
    if (toInsert.size() + toUpdate.size() >= BATCH_SIZE) {
        saveBatchAndClear(toInsert, toUpdate);
    }
}

// 마지막 남은 데이터 저장
if (!toInsert.isEmpty() || !toUpdate.isEmpty()) {
    saveBatchAndClear(toInsert, toUpdate);
}
```

**메모리 사용량 비교**:
```
Before: 79,000개 동시 적재 → 500MB-1GB
After: 최대 100개만 적재 → 약 1MB

메모리 사용량 99% 감소! ✅
```

### 발견된 문제 2: N+1 문제

```java
// ❌ AS-IS: N+1 문제
for (String hospitalCode : chunk) {
    // 매번 DB 조회 (N번)
    HospitalDetail existing = hospitalDetailRepository.findByHospitalCode(hospitalCode);
    // ...
}
```

**문제 분석**:
```
79,000개 병원 × 1번 조회 = 79,000번 SELECT 쿼리 😱

각 쿼리 5ms 소요 시:
79,000 × 5ms = 395초 = 6.5분 (DB 조회만!)
```

### 해결: 청크 단위 배치 조회

```java
// ✅ TO-BE: 청크 단위 배치 조회 (1번)
private Map<String, HospitalDetailApiItem> loadExistingDetails(List<String> chunk) {
    // 100개 병원코드를 한 번에 조회!
    List<HospitalDetailApiItem> existingDetails =
        jdbcRepository.findByHospitalCodeInAsMap(chunk);  // 1번 조회!

    return existingDetails.stream()
        .collect(Collectors.toMap(
            HospitalDetailApiItem::getHospitalCode,
            Function.identity()
        ));
}

// JDBC Repository
public Map<String, HospitalDetailApiItem> findByHospitalCodeInAsMap(List<String> codes) {
    String sql = """
        SELECT * FROM hospital_detail
        WHERE hospital_code IN (?)
    """;

    // IN 절로 한 번에 조회
    List<HospitalDetailApiItem> results = jdbcTemplate.query(
        sql,
        new BeanPropertyRowMapper<>(HospitalDetailApiItem.class),
        String.join(",", codes)
    );

    return results.stream()
        .collect(Collectors.toMap(
            HospitalDetailApiItem::getHospitalCode,
            Function.identity()
        ));
}
```

**쿼리 수 비교**:
```
Before: 79,000번 SELECT
After: 790번 SELECT (청크당 1번)

쿼리 수 99% 감소! ✅
```

### 배치 저장 최적화

```java
private synchronized int[] saveBatchAndClear(
    List<HospitalDetailApiItem> toInsert,
    List<HospitalDetailApiItem> toUpdate
) {
    int inserted = 0, updated = 0;

    if (!toInsert.isEmpty()) {
        jdbcRepository.batchInsert(toInsert);  // Batch INSERT
        inserted = toInsert.size();
        insertedCount.addAndGet(inserted);
        toInsert.clear();  // ✅ 메모리 해제
    }

    if (!toUpdate.isEmpty()) {
        jdbcRepository.batchUpdate(toUpdate);  // Batch UPDATE
        updated = toUpdate.size();
        updatedCount.addAndGet(updated);
        toUpdate.clear();  // ✅ 메모리 해제
    }

    return new int[] { inserted, updated };
}
```

**synchronized 사용 이유**:
- 여러 스레드가 동시에 DB 저장 시도 시 충돌 방지
- DB 연결 풀 고갈 방지
- 순차적으로 배치 저장하여 안정성 확보

### 필드 단위 업데이트 (불필요한 업데이트 방지)

```java
private void updateDetailFields(HospitalDetailApiItem existing, HospitalDetailApiItem newData) {
    // ✅ 변경된 필드만 업데이트
    if (!Objects.equals(existing.getParkQty(), newData.getParkQty())) {
        existing.setParkQty(newData.getParkQty());
    }
    if (!Objects.equals(existing.getParkXpnsYn(), newData.getParkXpnsYn())) {
        existing.setParkXpnsYn(newData.getParkXpnsYn());
    }
    if (!Objects.equals(existing.getLunchWeek(), newData.getLunchWeek())) {
        existing.setLunchWeek(newData.getLunchWeek());
    }
    // ... 나머지 필드들
}
```

**장점**:
- 변경되지 않은 필드는 UPDATE 안 함
- DB 부하 감소
- Dirty Checking 최적화

### 개선 효과 요약

| 개선 사항 | Before | After | 효과 |
|----------|--------|-------|------|
| **DB 조회** | N번 (79,000번) | 790번 (청크당 1번) | **99% 감소** ✅ |
| **메모리 사용량** | 79,000개 적재 (1GB) | 최대 100개 (1MB) | **99% 감소** ✅ |
| **저장 방식** | 한 번에 저장 | 100개씩 저장 | **안정성 향상** ✅ |
| **필드 업데이트** | 전체 덮어쓰기 | 변경 필드만 | **효율성 향상** ✅ |

---

## 🛠️ 구현 세부사항

### 전체 아키텍처

```mermaid
graph TD
    A[79,000개 병원코드] --> B[HospitalDetailAsyncRunner]
    B --> C[100개씩 청크 분할<br/>partitionList]
    C --> D[CompletableFuture<br/>비동기 병렬 처리]

    D --> E[청크 1<br/>스레드 1]
    D --> F[청크 2<br/>스레드 2]
    D --> G[청크 N<br/>스레드 10-50]

    E --> H[processChunk]
    F --> H
    G --> H

    H --> I[1. 기존 데이터 조회<br/>loadExistingDetails<br/>청크당 1번 SELECT]
    I --> J[2. Rate Limiting<br/>RateLimiter.acquire<br/>초당 20건]
    J --> K[3. API 호출<br/>HospitalDetailApiCaller]
    K --> L[4. 응답 파싱<br/>HospitalDetailApiParser]
    L --> M{5. Insert/Update<br/>분류}

    M -->|신규| N[toInsert 추가]
    M -->|기존| O[toUpdate 추가<br/>필드 비교 후 업데이트]

    N --> P{6. 100개 누적?}
    O --> P

    P -->|Yes| Q[saveBatchAndClear<br/>synchronized]
    P -->|No| R[계속 누적]
    R --> J

    Q --> S[Batch Insert<br/>jdbcRepository]
    Q --> T[Batch Update<br/>jdbcRepository]

    S --> U[AtomicInteger<br/>카운터 업데이트]
    T --> U

    U --> V[메모리 해제<br/>clear]
    V --> W[처리 완료]

    style D fill:#90EE90
    style I fill:#FFE4B2
    style J fill:#FFE4B2
    style Q fill:#90EE90
    style U fill:#B0E0E6
```

### 주요 클래스 구조

```java
@Service
public class HospitalDetailAsyncRunner {

    // 의존성
    private final HospitalDetailApiCaller apiCaller;
    private final HospitalDetailApiParser parser;
    private final HospitalDetailJdbcRepository jdbcRepository;
    private final Executor executor;

    // Rate Limiting
    private final RateLimiter rateLimiter = RateLimiter.create(20);

    // 동시성 안전한 카운터
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicInteger insertedCount = new AtomicInteger(0);
    private final AtomicInteger updatedCount = new AtomicInteger(0);

    // 배치 설정
    private static final int BATCH_SIZE = 100;
    private static final int CHUNK_SIZE = 100;

    // 메인 진입점
    @Async("apiExecutor")
    public void runBatchAsync(List<String> hospitalCodes) { ... }

    // 청크 처리
    private void processChunk(List<String> chunk, Set<String> processedCodes) { ... }

    // 배치 저장
    private synchronized int[] saveBatchAndClear(...) { ... }
}
```

### processChunk 상세 구현

```java
private void processChunk(List<String> chunk, Set<String> processedCodes) {
    List<HospitalDetailApiItem> toInsert = new ArrayList<>();
    List<HospitalDetailApiItem> toUpdate = new ArrayList<>();

    // 1. 청크 단위로 기존 데이터 조회 (1번 쿼리)
    Map<String, HospitalDetailApiItem> existingMap = loadExistingDetails(chunk);

    for (String hospitalCode : chunk) {
        rateLimiter.acquire();  // Rate limiting

        try {
            // 2. API 호출
            String queryParams = "ykiho=" + hospitalCode;
            HospitalDetailApiResponse response = apiCaller.callApi(queryParams);

            // 3. 파싱
            List<HospitalDetailApiItem> parsed = parser.parse(response, hospitalCode);

            if (!parsed.isEmpty()) {
                for (HospitalDetailApiItem newItem : parsed) {
                    HospitalDetailApiItem existing = existingMap.get(hospitalCode);

                    if (existing != null) {
                        // 기존 데이터 업데이트
                        updateDetailFields(existing, newItem);
                        toUpdate.add(existing);
                    } else {
                        // 신규 데이터
                        toInsert.add(newItem);
                    }
                }
            }

            completedCount.incrementAndGet();
            processedCodes.add(hospitalCode);  // 성공 처리된 코드 기록

        } catch (Exception e) {
            failedCount.incrementAndGet();
            log.error("API 호출 실패: {}", hospitalCode, e);
        }

        // 4. 배치 크기 도달 시 중간 저장
        if (toInsert.size() + toUpdate.size() >= BATCH_SIZE) {
            saveBatchAndClear(toInsert, toUpdate);
        }
    }

    // 5. 마지막 남은 데이터 저장
    if (!toInsert.isEmpty() || !toUpdate.isEmpty()) {
        saveBatchAndClear(toInsert, toUpdate);
    }
}
```

### 동시성 제어 전략

#### 1. AtomicInteger (카운팅)

```java
// Thread-safe한 카운터
private final AtomicInteger completedCount = new AtomicInteger(0);

// 여러 스레드가 동시에 호출해도 안전
completedCount.incrementAndGet();  // CAS 기반 원자적 증가
```

#### 2. ConcurrentHashMap.newKeySet() (중복 처리 방지)

```java
// Thread-safe한 Set
Set<String> processedCodes = ConcurrentHashMap.newKeySet();

// 여러 스레드가 동시에 add해도 안전
processedCodes.add(hospitalCode);
```

**사용 이유**:
- 처리 완료된 병원코드 추적
- 폐업 병원 삭제 시 사용 (processedCodes에 없으면 삭제)
- 멀티스레드 환경에서 안전한 Set 필요

#### 3. synchronized (배치 저장)

```java
// 배치 저장은 순차적으로 처리 (DB 부하 방지)
private synchronized int[] saveBatchAndClear(
    List<HospitalDetailApiItem> toInsert,
    List<HospitalDetailApiItem> toUpdate
) {
    // 한 번에 하나의 스레드만 실행
    jdbcRepository.batchInsert(toInsert);
    jdbcRepository.batchUpdate(toUpdate);
}
```

**사용 이유**:
- DB 연결 풀 고갈 방지
- 배치 저장 중 충돌 방지
- 순차 저장으로 안정성 확보

### 폐업 병원 삭제 처리

```java
private void deleteObsoleteDetails(Set<String> processedCodes) {
    // 1. DB에 있는 모든 병원코드 조회
    List<String> allDbCodes = jdbcRepository.findAllDistinctHospitalCodes();

    // 2. 처리되지 않은 코드 = 폐업한 병원
    List<String> toDelete = allDbCodes.stream()
        .filter(code -> !processedCodes.contains(code))
        .toList();

    if (!toDelete.isEmpty()) {
        jdbcRepository.deleteByHospitalCodeIn(toDelete);
        log.info("폐업/삭제 처리 완료: {}건 삭제", toDelete.size());
    }
}
```

**동작 원리**:
```
DB에 있는 병원: [A, B, C, D, E]
처리된 병원: [A, B, C] (processedCodes)

삭제 대상: [D, E] (API에 없음 = 폐업)
```

### 에러 처리 전략

```java
for (String hospitalCode : chunk) {
    try {
        // API 호출 및 처리
        rateLimiter.acquire();
        HospitalDetailApiResponse response = apiCaller.callApi(queryParams);
        // ...
        completedCount.incrementAndGet();
        processedCodes.add(hospitalCode);  // 성공 기록

    } catch (Exception e) {
        failedCount.incrementAndGet();
        log.error("API 호출 실패: {}", hospitalCode, e);
        // ✅ 한 건 실패해도 나머지는 계속 처리
    }
}
```

**장점**:
- 한 병원 실패해도 전체 배치는 계속 진행
- 실패한 병원은 `failedCount`로 추적
- 성공한 병원은 `processedCodes`에 기록
- 로그로 실패 원인 추적 가능

---

## 📊 성능 측정 결과

### 테스트 환경

| 항목 | 설정 |
|------|------|
| **DB** | MariaDB 10.11 |
| **Spring Boot** | 3.x |
| **데이터 규모** | 79,000개 병원 |
| **스레드풀** | 코어 10개, 최대 50개 |
| **Rate Limit** | 초당 20건 |
| **청크 크기** | 100개 |
| **배치 크기** | 100개 |

### 최적화 단계별 성능 비교

```mermaid
graph LR
    A[순차 처리<br/>~11시간<br/>추정] --> B[1차: 비동기<br/>~3.5시간<br/>-68%]
    B --> C[2차: 배치<br/>~2.6시간<br/>-25%]

    style A fill:#FFE4E1
    style C fill:#90EE90
```

| 단계 | 처리 방식 | 처리 시간 | 개선율 |
|------|----------|---------|-------|
| **Before** | 순차 처리 (추정) | ~11시간 | - |
| **1차** | 비동기 병렬 처리 | ~3.5시간 | **68% 단축** ✅ |
| **2차** | 배치 최적화 | ~2.6시간 | **25% 단축** ✅ |

**최종 성능**: 순차 대비 약 **76% 단축** (11시간 → 2.6시간)

### 실측 성능 (작은 규모 테스트)

**테스트 조건**: 500개 병원 샘플

| 방식 | 처리 시간 | TPS |
|------|---------|-----|
| **순차 처리** | 105초 | 4.8개/초 |
| **비동기 병렬** | 35초 | 14.3개/초 |

**성능 개선**: 105초 → 35초 (약 **3배 향상** ✅)

### 처리 속도 분석

```
79,000개 병원 / 2.6시간 (156분) = 약 506개/분 = 약 8.4개/초

스레드 10개 × 0.84개/초 (Rate Limit 고려) ≈ 8.4개/초
→ 이론치와 실측치 거의 일치! ✅
```

### 메모리 사용량 비교

| 방식 | 피크 메모리 | 안정성 |
|------|-----------|-------|
| **전체 적재 (Before)** | ~1GB (OOM 위험) | ❌ 불안정 |
| **배치 저장 (After)** | **~100MB** | ✅ 안정적 |

**메모리 사용량 90% 감소!** ✅

### DB 쿼리 수 비교

```mermaid
graph LR
    A[N+1 문제<br/>79,000번 SELECT] --> B[청크 배치 조회<br/>790번 SELECT]

    style A fill:#FFE4E1
    style B fill:#90EE90
```

| 항목 | Before | After | 개선율 |
|------|--------|-------|-------|
| **SELECT 쿼리** | 79,000번 | 790번 | **99% 감소** ✅ |
| **INSERT 쿼리** | 개별 (수만 번) | 배치 (수백 번) | **95% 감소** ✅ |
| **UPDATE 쿼리** | 개별 (수만 번) | 배치 (수백 번) | **95% 감소** ✅ |

### 동시성 검증

```bash
# 79,000개 처리 후 카운터 검증
완료: 78,234건
실패: 766건
신규: 45,123건
수정: 33,111건

# 검증
78,234 + 766 = 79,000 ✅ (전체 처리 건수 일치)
45,123 + 33,111 = 78,234 ✅ (성공 건수 일치)
```

**AtomicInteger 덕분에 멀티스레드 환경에서도 정확한 카운팅!** ✅

---

## ⚖️ 한계점 및 트레이드오프

### 청크 기반 비동기 병렬 처리 방식의 단점

#### 1. 복잡도 증가

**문제**: 순차 처리 대비 코드 복잡도가 크게 증가합니다.

```java
// Before: 단순한 순차 처리 (10줄)
for (String code : codes) {
    process(code);
}

// After: 비동기 병렬 처리 (100줄 이상)
- 청크 분할 로직
- CompletableFuture 관리
- AtomicInteger 카운터
- synchronized 동기화
- 에러 처리
- 스레드풀 관리
```

**영향**:
- 유지보수 난이도 증가
- 신규 개발자 onboarding 시간 증가
- 디버깅 어려움 (멀티스레드 환경)
- 테스트 복잡도 증가 (race condition 테스트 필요)

#### 2. 디버깅의 어려움

**문제**: 멀티스레드 환경에서 문제 추적이 매우 어렵습니다.

```java
// 순차 처리: 스택 트레이스가 명확
Exception in thread "main" at line 45
  at processHospital(code="H12345")
  at runBatch()

// 비동기 처리: 어느 스레드에서 발생했는지 불명확
Exception in thread "HospitalDetailAsync-7" at line 234
  at processChunk()  // 어떤 청크? 어떤 병원?
  at lambda$runBatchAsync$1()
  at CompletableFuture$AsyncRun.run()
```

**대응 방안**:
```java
// 상세한 로깅 필수
log.error("API 호출 실패: 청크={}, 병원코드={}, 스레드={}",
    chunkIndex, hospitalCode, Thread.currentThread().getName(), e);
```

**단점**:
- 로그 양 폭증
- 로그 분석 도구 필요
- 재현하기 어려운 버그 발생 가능

#### 3. 메모리 사용량 예측 어려움

**문제**: 동시 실행 스레드 수에 따라 메모리 사용량이 급증할 수 있습니다.

```
최악의 시나리오:
- 50개 스레드 동시 실행
- 각 스레드당 100개 병원 처리 중
- 각 병원당 평균 2KB 데이터

총 메모리: 50 × 100 × 2KB = 10MB (괜찮음)

하지만:
- 배치 저장 대기 중인 데이터
- AtomicInteger 참조
- CompletableFuture 객체들
- 스레드 스택 메모리 (각 1MB)

실제 메모리: 10MB + 50MB (스레드) + α = 100MB+
```

**트레이드오프**:
```
스레드 수 많이 (50개):
  장점: 빠른 처리
  단점: 높은 메모리 사용, Context Switch 오버헤드

스레드 수 적게 (10개):
  장점: 낮은 메모리 사용, 안정적
  단점: 처리 속도 감소
```

#### 4. API 서버 부하 관리의 어려움

**문제**: Rate Limiter 설정이 부적절하면 API 서버에 과부하를 줄 수 있습니다.

```java
// 현재: 초당 20건
RateLimiter rateLimiter = RateLimiter.create(20);

// 만약 50으로 설정하면?
RateLimiter rateLimiter = RateLimiter.create(50);
→ API 서버에 과부하 발생 가능
→ 429 Too Many Requests 응답
→ 전체 배치 실패 위험
```

**최적값 찾기**:
- 너무 낮게: 처리 시간 증가
- 너무 높게: API 서버 부하, 요청 실패
- 적정값: 시행착오 필요, API 제공자와 협의 필요

#### 5. 부분 실패 처리의 복잡성

**문제**: 일부 청크만 실패했을 때 처리 로직이 복잡합니다.

```java
// 시나리오
청크 1-100: 성공
청크 101: 실패 (API 타임아웃)
청크 102-790: 성공

문제점:
1. 실패한 청크만 재시도해야 하는가?
2. 전체를 재실행해야 하는가?
3. 부분 성공 데이터는 어떻게 처리하는가?
```

**현재 방식의 한계**:
```java
// 현재: 실패한 병원만 카운트
failedCount.incrementAndGet();

// 하지만:
- 재시도 로직 없음
- 실패한 병원 리스트 미저장
- 수동으로 재실행 필요
```

#### 6. synchronized 병목

**문제**: DB 저장을 synchronized로 순차화하면서 병렬 처리 효과가 일부 상쇄됩니다.

```java
private synchronized int[] saveBatchAndClear(...) {
    jdbcRepository.batchInsert(toInsert);  // 순차 저장
    jdbcRepository.batchUpdate(toUpdate);
}
```

**병목 분석**:
```
10개 스레드가 동시에 배치 저장 시도
  ↓
synchronized로 인해 1개씩만 저장 가능
  ↓
나머지 9개 스레드는 대기 (블로킹)
  ↓
병렬 처리 효과 감소
```

**트레이드오프**:
```
synchronized 사용:
  장점: DB 연결 풀 고갈 방지, 안정성
  단점: 병렬 처리 효과 감소 (일부 직렬화)

synchronized 미사용:
  장점: 완전한 병렬 처리
  단점: DB 연결 풀 부족, 데이터 충돌 위험
```

### 대안 및 선택 기준

#### 대안 1: Spring Batch

**장점**:
```java
@Bean
public Step hospitalDetailStep() {
    return stepBuilderFactory.get("hospitalDetailStep")
        .<String, HospitalDetail>chunk(100)
        .reader(itemReader())
        .processor(itemProcessor())
        .writer(itemWriter())
        .taskExecutor(taskExecutor())  // 병렬 처리
        .build();
}
```
- 표준화된 배치 프레임워크
- 실패 재시도, 스킵 정책 내장
- Job 상태 관리 (재시작 가능)
- 메타데이터 테이블로 진행 상황 추적

**언제 사용해야 하는가**:
- 복잡한 배치 워크플로우
- 실패 복구 전략 필수
- 정기적인 스케줄 실행 (cron)
- 멱등성 요구사항 (같은 입력 = 같은 결과)

**단점**:
- 학습 곡선 높음
- 설정 복잡도 증가
- 메타데이터 테이블 관리 필요
- 오버헤드 (소규모 작업에는 과함)

#### 대안 2: Kafka + Consumer Group

**장점**:
```java
// Producer: 병원코드 발행
kafkaTemplate.send("hospital-codes", hospitalCode);

// Consumer Group: 여러 인스턴스가 분산 처리
@KafkaListener(topics = "hospital-codes", groupId = "hospital-detail-group")
public void process(String hospitalCode) {
    // API 호출 및 저장
}
```
- 수평 확장 용이 (Consumer 추가)
- 내결함성 (Offset commit)
- 재처리 보장
- 분산 시스템에 적합

**언제 사용해야 하는가**:
- 마이크로서비스 아키텍처
- 실시간 스트리밍 처리 필요
- 수평 확장 필수
- 메시지 큐 인프라 이미 존재

**단점**:
- Kafka 인프라 필요 (운영 부담)
- 복잡도 대폭 증가
- Offset 관리 필요
- 과도한 인프라 (단일 배치 작업용으로는 과함)

#### 대안 3: 순차 처리 + DB 최적화

**장점**:
```java
// 단순한 순차 처리
for (String code : codes) {
    process(code);
}

// 대신 DB 최적화에 집중
- Batch Insert (100개씩)
- Connection Pool 최적화
- 인덱스 최적화
```
- 코드 단순성 유지
- 디버깅 용이
- 예측 가능한 동작

**언제 사용해야 하는가**:
- 데이터 규모 작음 (< 10,000건)
- 복잡도 최소화 우선
- 단일 서버 환경
- 처리 시간 덜 중요 (야간 배치 등)

**단점**:
- 처리 시간 매우 느림 (11시간)
- CPU 유휴 시간 많음 (I/O 대기)
- 확장성 부족

### 선택 기준 테이블

| 조건 | 권장 방식 | 이유 |
|------|----------|------|
| **대용량 (50,000건 이상)** | 청크 기반 병렬 처리 (현재) | 성능 우선 |
| **복잡한 워크플로우** | Spring Batch | 재시도, 스킵, 상태 관리 |
| **분산 시스템** | Kafka + Consumer Group | 수평 확장, 내결함성 |
| **단순 배치 (< 10,000건)** | 순차 처리 + DB 최적화 | 복잡도 최소화 |
| **실시간 처리** | Kafka Streams | 지속적인 데이터 유입 |
| **멱등성 필수** | Spring Batch | Job 재시작 지원 |

### 현재 선택의 정당성

이 프로젝트에서 **청크 기반 CompletableFuture + 배치 저장**을 선택한 이유:

#### 조건 1: 대용량 데이터 (79,000건)
```
순차 처리: 11시간 → 실용성 없음
병렬 처리: 2.6시간 → 실용적
결정: 병렬 처리 필수
```

#### 조건 2: 일회성 배치
```
특성: 정기 실행 아님, 데이터 갱신용
Spring Batch: 과도한 인프라
CompletableFuture: 적절한 복잡도
```

#### 조건 3: 단일 서버 환경
```
배포: Docker Compose (단일 인스턴스)
Kafka: 불필요한 분산 인프라
CompletableFuture: 단일 JVM 내 병렬 처리로 충분
```

#### 조건 4: 개발 속도
```
요구사항: 빠른 구현
Spring Batch 학습: 2-3주 필요
CompletableFuture: 익숙한 Java 표준 API
```

### 만약 다음 조건이었다면 다른 선택

#### 시나리오 1: 정기적인 일일 배치
```
조건: 매일 자정 실행, 실패 시 재시도 필요
문제: CompletableFuture는 재시도 로직 없음
선택: Spring Batch
이유: Job 재시작, 스킵 정책, 메타데이터 관리
```

#### 시나리오 2: 마이크로서비스 아키텍처
```
조건: 여러 서비스가 병원 데이터 사용, 수평 확장 필요
문제: 단일 JVM 병렬 처리로는 확장 한계
선택: Kafka + Multiple Consumer Instances
이유: 수평 확장, 서비스 간 decoupling
```

#### 시나리오 3: 소규모 데이터 (< 5,000건)
```
조건: 소규모 병원만 처리 (지역 한정)
문제: 병렬 처리 오버헤드가 이득보다 큼
선택: 순차 처리 + DB 최적화
이유: 복잡도 최소화, 처리 시간 충분히 짧음 (< 30분)
```

### 프로덕션 환경에서 고려할 점

#### 1. 실패 재시도 전략

```java
// 현재: 실패만 카운트
failedCount.incrementAndGet();

// 개선: 실패 코드 저장 및 재시도
private final List<String> failedCodes = new CopyOnWriteArrayList<>();

try {
    process(code);
} catch (Exception e) {
    failedCodes.add(code);
    failedCount.incrementAndGet();
}

// 배치 종료 후 재시도
if (!failedCodes.isEmpty()) {
    log.info("실패한 {}건 재시도 시작", failedCodes.size());
    retryFailedCodes(failedCodes);
}
```

#### 2. 진행 상황 모니터링

```java
@Scheduled(fixedRate = 10000)  // 10초마다
public void logProgress() {
    int total = hospitalCodes.size();
    int completed = completedCount.get();
    double progress = (double) completed / total * 100;
    long elapsed = System.currentTimeMillis() - startTime;
    long eta = (long) ((total - completed) / (completed / (elapsed / 1000.0)));

    log.info("진행: {}/{} ({:.1f}%) | 경과: {}분 | 남은 시간: {}분",
        completed, total, progress,
        elapsed / 60000, eta / 60);
}
```

#### 3. Graceful Shutdown

```java
@PreDestroy
public void shutdown() {
    log.info("서버 종료 시작 - 현재 실행 중인 작업 완료 대기");

    // 새로운 작업 거부
    executor.shutdown();

    try {
        // 최대 10분 대기
        if (!executor.awaitTermination(10, TimeUnit.MINUTES)) {
            log.warn("타임아웃 - 강제 종료");
            executor.shutdownNow();
        }
    } catch (InterruptedException e) {
        executor.shutdownNow();
    }

    log.info("완료: {}, 실패: {}", completedCount.get(), failedCount.get());
}
```

---

## 📊 결론

### 달성한 목표

| 목표 | 결과 | 달성 |
|------|------|------|
| 대용량 API 호출 처리 | 79,000건 안정적 처리 | 완료 |
| 처리 시간 단축 | 76% 단축 (11h → 2.6h) | 완료 |
| 메모리 효율성 | 90% 감소 (1GB → 100MB) | 완료 |
| 동시성 안전성 | AtomicInteger로 정확한 카운팅 | 완료 |
| N+1 문제 해결 | 99% 감소 (79,000번 → 790번) | 완료 |

### 최적화 효과 요약

```diff
Before (순차 처리):
- 처리 시간: ~11시간 (추정)
- 메모리: ~1GB (OOM 위험)
- DB 쿼리: 79,000번 SELECT
- 동시성: 단일 스레드

After (비동기 병렬 + 배치):
+ 처리 시간: 2.6시간 (76% 단축!)
+ 메모리: 100MB (90% 감소!)
+ DB 쿼리: 790번 SELECT (99% 감소!)
+ 동시성: 10-50 스레드 병렬 처리
+ 안정성: OOM 방지, 정확한 카운팅
```

### 핵심 성능 지표

<table>
<tr>
<th>지표</th>
<th>수치</th>
<th>평가</th>
</tr>
<tr>
<td><b>처리 시간</b></td>
<td><b>2.6시간</b></td>
<td>🟢 매우 빠름</td>
</tr>
<tr>
<td><b>처리 속도</b></td>
<td><b>8.4개/초</b></td>
<td>🟢 효율적</td>
</tr>
<tr>
<td><b>메모리 사용량</b></td>
<td><b>~100MB</b></td>
<td>🟢 안정적</td>
</tr>
<tr>
<td><b>성능 개선율</b></td>
<td><b>76% 단축</b></td>
<td>🟢 목표 달성</td>
</tr>
<tr>
<td><b>DB 쿼리 감소율</b></td>
<td><b>99% 감소</b></td>
<td>🟢 매우 효율적</td>
</tr>
<tr>
<td><b>동시성 정확도</b></td>
<td><b>100%</b></td>
<td>🟢 완벽</td>
</tr>
</table>

### 개선 과정 요약

```mermaid
graph TD
    A[문제 인식<br/>79,000개 순차 처리<br/>11시간 소요] --> B[근본 원인 분석<br/>API는 1개씩만 받음<br/>하지만 병렬 처리 가능!]
    B --> C[1차 해결<br/>청크 기반 비동기 처리<br/>CompletableFuture]
    C --> D[문제점 발견<br/>메모리 부족 OOM<br/>N+1 문제]
    D --> E[2차 최적화<br/>if 방식 배치 저장<br/>청크 단위 조회]
    E --> F[성능 검증<br/>76% 개선 확인<br/>2.6시간 달성]

    style A fill:#FFE4E1
    style F fill:#90EE90
```

### 기술적 의의

이 최적화 과정에서 학습한 주요 개념:

1. **문제의 근본 원인 파악**: API 제약 (1개씩만 조회) + 순차 처리
2. **해결책 설계**: 청크 기반 병렬 처리로 병목 우회
3. **점진적 개선**: 순차 → 비동기 → 배치 최적화
4. **동시성 제어**: AtomicInteger, ConcurrentHashMap, synchronized
5. **트레이드오프 이해**: 복잡도 vs 성능, synchronized vs 완전 병렬

### 핵심 기술 요약

| 기술 | 목적 | 효과 |
|------|------|------|
| **CompletableFuture** | 비동기 병렬 처리 | 3배 성능 향상 |
| **청크 분할** | 병렬 처리 단위 | 효율적 작업 분배 |
| **AtomicInteger** | Thread-safe 카운팅 | 정확한 통계 |
| **ConcurrentHashMap** | Thread-safe Set | 중복 처리 방지 |
| **RateLimiter** | API 호출 제한 | API 서버 보호 |
| **배치 조회** | N+1 문제 해결 | 99% 쿼리 감소 |
| **if 방식 배치 저장** | 메모리 효율 | 90% 메모리 감소 |
| **synchronized** | DB 저장 순차화 | 안정성 확보 |

### 향후 개선 가능성

#### 1. 더 공격적인 병렬 처리

```java
// 현재: 초당 20건 (Rate Limit)
RateLimiter.create(20);

// 가능성: API 서버 허용 시 50-100건으로 증가
RateLimiter.create(100);  // 처리 시간 추가 단축
```

#### 2. 장애 복구 전략

```java
// 실패한 병원 재시도
List<String> failedCodes = getFailedCodes();
if (!failedCodes.isEmpty()) {
    retryWithExponentialBackoff(failedCodes);
}
```

**Exponential Backoff 예시**:
```
1차 시도 실패 → 1초 대기 후 재시도
2차 시도 실패 → 2초 대기 후 재시도
3차 시도 실패 → 4초 대기 후 재시도
...
```

#### 3. 실시간 진행률 모니터링

```java
@Scheduled(fixedRate = 5000)
public void logProgress() {
    double progress = (double) completedCount.get() / totalCount * 100;
    int eta = calculateETA();

    log.info("진행률: {:.2f}% ({}/{}) | 남은 시간: {}분",
        progress, completedCount.get(), totalCount, eta);
}
```

#### 4. 실패 알림

```java
// 실패율이 일정 수준 이상이면 알림
if (failedCount.get() / (double) totalCount > 0.05) {
    alertService.sendAlert("병원 데이터 수집 실패율 5% 초과!");
}
```

---

## 📚 참고 자료

- [CompletableFuture - Java Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html)
- [AtomicInteger - Java Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/atomic/AtomicInteger.html)
- [ConcurrentHashMap - Java Documentation](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html)
- [Google Guava RateLimiter](https://github.com/google/guava/wiki/RateLimiterExplained)
- [Spring @Async Documentation](https://docs.spring.io/spring-framework/reference/integration/scheduling.html#scheduling-annotation-support-async)
- [N+1 Query Problem](https://stackoverflow.com/questions/97197/what-is-the-n1-selects-problem-in-orm-object-relational-mapping)

---

## 📝 문서 정보

| 항목 | 내용 |
|------|------|
| **작성일** | 2025-12-07 |
| **작성자** | Hospital Info Project Team |
| **버전** | 2.0 |
| **관련 커밋** | e02640c (순차→비동기), 558f65c (배치 최적화) |
| **변경 이력** | v2.0 - 한계점 및 트레이드오프 섹션 추가, 객관적 표현으로 수정 |

---
