# JPA에서 JDBC로 마이그레이션 - 대용량 배치 처리 최적화

> **79,081건 병원 데이터 수집의 안정성 확보**
> Entity 변환 오버헤드 제거를 통한 처리 속도 286% 개선 및 OOM 해결

---

## 📑 목차

1. [개요](#개요)
2. [문제 정의](#문제-정의)
3. [1차 시도: JPA DTO Projection](#1차-시도-jpa-dto-projection)
4. [DTO Projection의 한계](#dto-projection의-한계)
5. [2차 해결: JDBC Template 마이그레이션](#2차-해결-jdbc-template-마이그레이션)
6. [성능 측정 결과](#성능-측정-결과)
7. [한계점 및 트레이드오프](#한계점-및-트레이드오프)
8. [결론](#결론)

---

## 개요

### 프로젝트 배경

병원 상세정보 수집 시스템에서 79,081개 병원 데이터를 외부 API로부터 수집하고 DB에 저장하는 배치 작업을 진행했습니다.
공공데이터포털 API에서 주차 정보, 진료 시간 등 20개 필드를 수집하여 저장해야 했습니다.

### 핵심 문제

전체 데이터의 약 20% 처리 시점에서 컨테이너가 다운되는 현상이 발생했습니다.

```
초기 상황:
- 목표: 79,081개 병원 데이터 수집
- 현실: 20% 지점(약 16,000개)에서 컨테이너 다운
- 원인: JPA Entity 변환 오버헤드 및 영속성 컨텍스트 메모리 누적
```

### 기술 스택

| 항목 | 기술 |
|------|------|
| **Backend** | Java 21, Spring 6.0.13 |
| **ORM** | JPA 3.0.11 → Spring JDBC |
| **DB** | MariaDB 10.11 |
| **Connection Pool** | HikariCP |
| **동시성 제어** | Google Guava RateLimiter |

### 성능 개선 결과

| 지표 | Before (JPA) | After (JDBC) | 개선율 |
|------|-------------|--------------|-------|
| 처리 완료율 | 20% (실패) | 100% | - |
| 처리 속도 | 7건/초 | 20건/초 | 286% |
| 소요 시간 | 미완료 | 1시간 6분 | - |
| 메모리 사용 | OOM 발생 | 안정화 | - |

---

## 문제 정의

### 초기 구현

```java
@Service
public class HospitalDetailService {

    private final HospitalDetailRepository repository;  // JPA Repository
    private final HospitalDetailApiCaller apiCaller;
    private final HospitalDetailApiParser parser;

    public void collectHospitalDetails(List<String> hospitalCodes) {
        for (String hospitalCode : hospitalCodes) {
            // 1. API 호출
            HospitalDetailApiResponse response = apiCaller.callApi(hospitalCode);

            // 2. JSON 파싱 → DTO 생성
            List<HospitalDetailApiItem> items = parser.parse(response);

            // 3. DTO → Entity 변환
            List<HospitalDetail> entities = items.stream()
                .map(this::convertDtoToEntity)
                .collect(Collectors.toList());

            // 4. JPA 저장
            repository.saveAll(entities);
        }
    }

    private HospitalDetail convertDtoToEntity(HospitalDetailApiItem item) {
        HospitalDetail entity = new HospitalDetail();
        entity.setHospitalCode(item.getHospitalCode());
        entity.setParkingCapacity(item.getParkQty());
        // ... 총 20개 필드 매핑
        return entity;
    }
}
```

### 처리 흐름

```
API 호출 (79,081번)
    ↓
JSON 파싱 → DTO 생성
    ↓
DTO → Entity 변환 (20개 필드 × 79,081번)  ← 병목
    ↓
JPA 영속성 컨텍스트 (메모리 누적)        ← 병목
    ↓
DB 저장
```

### 발견된 문제점

#### 1. Entity 변환 오버헤드

```java
// 79,081번 반복:
HospitalDetail entity = new HospitalDetail();  // 객체 생성
entity.setHospitalCode(...);                   // 필드 1
entity.setParkingCapacity(...);                // 필드 2
// ... 20개 필드 복사
```

- 매 API 호출마다 새로운 Entity 객체 생성
- 20개 필드를 일일이 복사
- 79,081번 반복 시 누적 오버헤드 발생

#### 2. 영속성 컨텍스트 메모리 누적

```
시간 경과에 따른 메모리 증가:
- JPA 1차 캐시에 Entity 누적
- Dirty Checking 오버헤드
- 20% 지점에서 메모리 한계 도달
```

#### 3. 처리 속도 측정

```
초기: 약 7건/초
  ↓
점진적 속도 저하 (오버헤드 누적)
  ↓
20% 지점: Out of Memory / CPU 과부하
  ↓
컨테이너 다운
```

---

## 1차 시도: JPA DTO Projection

### 접근 방법

Entity 변환 오버헤드를 줄이기 위해 JPA의 DTO Projection 기능을 시도했습니다.

### 구현

#### Projection 인터페이스

```java
public interface HospitalDetailProjection {
    String getHospitalCode();
    Integer getParkQty();
    // ... 20개 Getter
}
```

#### Repository

```java
public interface HospitalDetailRepository extends JpaRepository<HospitalDetail, String> {
    List<HospitalDetailProjection> findByHospitalCodeIn(List<String> hospitalCodes);
}
```

#### Service

```java
public void loadExistingDetails(List<String> hospitalCodes) {
    List<HospitalDetailProjection> projections =
        repository.findByHospitalCodeIn(hospitalCodes);

    Map<String, HospitalDetailApiItem> existingMap = projections.stream()
        .collect(Collectors.toMap(
            HospitalDetailProjection::getHospitalCode,
            this::convertProjectionToApiItem
        ));
}

private HospitalDetailApiItem convertProjectionToApiItem(HospitalDetailProjection proj) {
    HospitalDetailApiItem item = new HospitalDetailApiItem();
    item.setHospitalCode(proj.getHospitalCode());
    item.setParkQty(proj.getParkQty() != null ? proj.getParkQty().toString() : null);
    // ... 20개 필드 매핑
    return item;
}
```

---

## DTO Projection의 한계

### 실제 결과

```
처리 속도: 7건/초 → 7.5건/초 (미미한 개선)
메모리: 여전히 누적 발생
결과: 20% 지점에서 여전히 컨테이너 다운
```

### 실패 원인 분석

#### 1. 복잡한 매핑 구조

```java
// Projection → ApiItem 변환에서 여전히 20개 필드 복사
HospitalDetailApiItem item = new HospitalDetailApiItem();
item.setHospitalCode(proj.getHospitalCode());  // 복사 1
item.setParkQty(proj.getParkQty()...);         // 복사 2
// ... 18개 더
```

Projection을 사용해도 ApiItem으로 재변환하는 과정에서 동일한 오버헤드가 발생했습니다.

#### 2. JPA 내부 동작

```
JPA Projection 내부 흐름:
1. DB 조회
2. ResultSet → Entity 임시 생성  ← Entity를 여전히 거침
3. Entity → Projection 변환
4. 영속성 컨텍스트 부분 관리   ← 오버헤드 존재
```

JPA는 Projection을 사용하더라도 내부적으로 Entity를 거치는 구조입니다.

#### 3. 영속성 컨텍스트

```java
@Transactional
public void processChunk(List<String> chunk) {
    // Projection 조회 시에도
    List<HospitalDetailProjection> projections =
        repository.findByHospitalCodeIn(chunk);

    // JPA는 1차 캐시에 보관
    // 메모리 누적 발생
}
```

---

## 2차 해결: JDBC Template 마이그레이션

### 설계 방향

JPA를 완전히 제거하고 JDBC Template을 사용하여 직접 제어합니다.

### 구현

#### 1. RowMapper 직접 매핑

```java
@Repository
public class HospitalDetailJdbcRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final RowMapper<HospitalDetailApiItem> ROW_MAPPER = (rs, rowNum) -> {
        HospitalDetailApiItem item = new HospitalDetailApiItem();
        item.setHospitalCode(rs.getString("hospital_code"));
        item.setParkQty(rs.getString("parking_capacity"));
        // ... 20개 필드 매핑
        return item;
    };
}
```

**개선점**:
- Entity 변환 단계 제거
- ResultSet → ApiItem 직접 매핑
- 영속성 컨텍스트 제거

#### 2. Batch Insert/Update

```java
@Transactional
public void batchInsert(List<HospitalDetailApiItem> items) {
    String sql = "INSERT INTO hospital_detail " +
            "(hospital_code, parking_capacity, ...) " +
            "VALUES (?, ?, ...)";

    List<Object[]> batchArgs = items.stream()
            .map(item -> new Object[]{
                item.getHospitalCode(),
                parseInteger(item.getParkQty()),
                // ... 18개 필드
            })
            .collect(Collectors.toList());

    jdbcTemplate.batchUpdate(sql, batchArgs);
}

@Transactional
public void batchUpdate(List<HospitalDetailApiItem> items) {
    String sql = "UPDATE hospital_detail SET " +
            "parking_capacity = ?, park_xpns_yn = ?, ... " +
            "WHERE hospital_code = ?";

    List<Object[]> batchArgs = items.stream()
            .map(item -> new Object[]{
                parseInteger(item.getParkQty()),
                // ... 18개 필드
                item.getHospitalCode()  // WHERE 조건
            })
            .collect(Collectors.toList());

    jdbcTemplate.batchUpdate(sql, batchArgs);
}
```

#### 3. 청크 단위 조회

```java
public Map<String, HospitalDetailApiItem> findByHospitalCodeInAsMap(
        List<String> hospitalCodes) {
    if (hospitalCodes == null || hospitalCodes.isEmpty()) {
        return Map.of();
    }

    String placeholders = hospitalCodes.stream()
            .map(code -> "?")
            .collect(Collectors.joining(","));

    String sql = "SELECT * FROM hospital_detail WHERE hospital_code IN ("
            + placeholders + ")";

    List<HospitalDetailApiItem> results = jdbcTemplate.query(
            sql, ROW_MAPPER, hospitalCodes.toArray());

    return results.stream()
            .collect(Collectors.toMap(
                    HospitalDetailApiItem::getHospitalCode,
                    item -> item
            ));
}
```

전체 79,000개가 아닌 청크(100개)씩만 메모리에 로드하여 처리합니다.

#### 4. HikariCP 최적화

```properties
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=10

# MariaDB Batch Rewrite 활성화
spring.datasource.hikari.jdbc-url=jdbc:mariadb://localhost:3306/hospital?rewriteBatchedStatements=true
```

`rewriteBatchedStatements=true` 설정으로 여러 개의 INSERT를 하나의 쿼리로 병합합니다:

```sql
-- Before
INSERT INTO hospital_detail (...) VALUES (...);  -- 100번
INSERT INTO hospital_detail (...) VALUES (...);
...

-- After
INSERT INTO hospital_detail (...) VALUES (...), (...), ...;  -- 1번
```

### JPA vs JDBC 비교

| 항목 | JPA | JDBC |
|------|-----|------|
| **Entity 변환** | DTO → Entity (20개 필드) | 불필요 (DTO 직접 사용) |
| **영속성 컨텍스트** | 1차 캐시 메모리 누적 | 없음 (즉시 반영) |
| **메모리 사용** | 누적 증가 → OOM | 청크별 일정 유지 |
| **배치 처리** | `saveAll()` 내부 최적화 | `batchUpdate()` 직접 제어 |
| **성능** | 7건/초 (20% 실패) | 20건/초 (100% 완료) |

---

## 성능 측정 결과

### 테스트 환경

| 항목 | 설정 |
|------|------|
| **데이터 규모** | 79,081개 병원 |
| **API 소스** | 공공데이터포털 |
| **동시성 제어** | RateLimiter 20건/초 |
| **청크 크기** | 100개 |
| **배치 크기** | 100개 |

### Before vs After

| 항목 | Before (JPA) | After (JDBC) | 개선 |
|------|-------------|--------------|------|
| **처리 완료율** | 20% (컨테이너 다운) | 100% (안정 완료) | 처리 가능 |
| **처리 속도** | 7건/초 | 20건/초 | 286% 향상 |
| **예상 소요 시간** | 미완료 (실패) | 1시간 6분 | 완료 가능 |
| **메모리 사용** | 누적 증가 → OOM | 청크별 일정 유지 | 안정화 |
| **실패율** | 100% (컨테이너 다운) | 0% | 안정성 확보 |

### 실시간 모니터링 로그

```
[JPA 버전]
시작 후 10분: 4,200개 완료 (7건/초)
시작 후 20분: 8,400개 완료 (속도 저하)
시작 후 35분: 약 15,000개 완료
시작 후 40분: 메모리 부족, CPU 과부하
→ 컨테이너 다운

[JDBC 버전]
시작 후 21초: 394개 완료
시작 후 48초: 820개 완료 (15.8건/초)
시작 후 68초: 1,221개 완료 (20.1건/초)
시작 후 8분: 9,692개 완료 (20.2건/초)
시작 후 1시간 6분: 79,081개 완료

평균 처리 속도: 19~20건/초 (안정적)
메모리 사용량: 일정 유지
실패: 0건
```

---

## 한계점 및 트레이드오프

### JDBC 전환의 단점

#### 1. Type Safety 상실

```java
// JPA: 컴파일 타임 타입 검증
entity.setParkingCapacity(123);  // Integer 타입 보장

// JDBC: 런타임 오류 가능
Object[] params = new Object[]{
    parseInteger(item.getParkQty())  // 파싱 실패 시 런타임 예외
};
```

JPA Entity는 타입이 명확히 정의되어 컴파일 타임에 오류를 잡을 수 있지만, JDBC는 런타임에만 타입 오류를 발견할 수 있습니다.

#### 2. 보일러플레이트 코드 증가

```java
// JPA: 간단한 저장
repository.save(entity);

// JDBC: SQL 직접 작성 필요
String sql = "INSERT INTO hospital_detail (hospital_code, parking_capacity, " +
             "park_xpns_yn, lunch_week, no_trmt_holi, trmt_mon_start, " +
             "trmt_mon_end, ...) VALUES (?, ?, ?, ?, ?, ?, ?, ...)";
jdbcTemplate.update(sql, code, capacity, yn, week, ...);
```

JDBC는 SQL을 직접 작성하고 파라미터를 순서대로 바인딩해야 하므로 코드량이 증가합니다.

#### 3. 복잡한 연관관계 처리 어려움

```java
// JPA: 객체 그래프 탐색
hospital.getDetails().forEach(detail -> {
    detail.getTreatmentTimes().forEach(time -> {
        // 연관된 엔티티 자동 로딩
    });
});

// JDBC: JOIN 쿼리 직접 작성 및 수동 매핑
String sql = "SELECT h.*, d.*, t.* FROM hospital h " +
             "LEFT JOIN detail d ON h.code = d.hospital_code " +
             "LEFT JOIN treatment_time t ON d.id = t.detail_id";
// ResultSet에서 수동으로 객체 생성 및 연관관계 설정
```

복잡한 연관관계가 있는 도메인에서는 JDBC 사용 시 코드 복잡도가 크게 증가합니다.

#### 4. 변경 추적(Dirty Checking) 부재

```java
// JPA: 변경 감지
@Transactional
public void updateHospital(String code) {
    HospitalDetail detail = repository.findById(code);
    detail.setParkingCapacity(50);  // 자동으로 UPDATE 쿼리 생성
}

// JDBC: 명시적 UPDATE 필요
public void updateHospital(String code) {
    String sql = "UPDATE hospital_detail SET parking_capacity = ? WHERE code = ?";
    jdbcTemplate.update(sql, 50, code);
}
```

### 대안 고려

JDBC로 전환하기 전에 고려할 수 있었던 다른 최적화 방법들:

#### 1. JPA Batch Size 조정

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=100
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

**적용 가능 상황**:
- 단순 INSERT/UPDATE가 대부분
- 연관관계가 복잡하지 않음
- 메모리 사용량이 임계치 이하

**한계**:
- 영속성 컨텍스트 메모리 누적 문제는 여전히 존재
- 20개 필드 매핑 오버헤드 해결 불가

#### 2. StatelessSession 사용

```java
StatelessSession session = sessionFactory.openStatelessSession();
Transaction tx = session.beginTransaction();

for (HospitalDetail entity : entities) {
    session.insert(entity);
}

tx.commit();
session.close();
```

**적용 가능 상황**:
- Hibernate 사용 중
- 영속성 컨텍스트가 필요 없는 배치 작업

**한계**:
- Hibernate 의존성 유지
- Entity 변환 오버헤드 여전히 존재

#### 3. QueryDSL + DTO Projection

```java
List<HospitalDetailDto> results = queryFactory
    .select(Projections.constructor(HospitalDetailDto.class,
        hospitalDetail.hospitalCode,
        hospitalDetail.parkingCapacity,
        // ...
    ))
    .from(hospitalDetail)
    .fetch();
```

**적용 가능 상황**:
- 타입 안전성이 중요
- 복잡한 조회 쿼리

**한계**:
- 대량 INSERT/UPDATE에는 부적합
- 메모리 문제 해결 불가

### 선택 기준

| 상황 | 권장 방법 | 이유 |
|------|---------|------|
| **단순 CRUD** | JPA | 개발 생산성 높음 |
| **복잡한 비즈니스 로직** | JPA + QueryDSL | 타입 안전성 + 복잡한 쿼리 지원 |
| **대용량 배치 (단순 구조)** | JDBC | 메모리 효율, 성능 |
| **대용량 배치 (복잡한 구조)** | JPA StatelessSession | 영속성 컨텍스트 제거 + 일부 편의성 유지 |

### 본 프로젝트의 선택

**JDBC를 선택한 이유**:
1. 단순한 데이터 구조 (20개 필드, 연관관계 없음)
2. 대용량 배치 처리 (79,081건)
3. 읽기 작업 거의 없음 (INSERT/UPDATE 위주)
4. 성능과 안정성이 최우선

**만약 다음 조건이었다면 다른 선택**:
- 복잡한 연관관계: JPA 유지 + StatelessSession
- 빈번한 조회 및 수정: JPA + Batch Size 조정
- 타입 안전성 중요: QueryDSL + DTO Projection

---

## 결론

### 핵심 개선 사항

| 지표 | 개선 결과 |
|------|----------|
| 처리 완료율 | 20% → 100% |
| 처리 속도 | 7건/초 → 20건/초 (286% 향상) |
| 소요 시간 | 미완료 → 1시간 6분 |
| 메모리 안정성 | OOM 발생 → 안정화 |

### 주요 교훈

#### 1. DTO Projection의 한계

복잡한 매핑(20개 필드)에서는 DTO Projection의 효과가 미미합니다. JPA는 내부적으로 Entity를 거치며, 영속성 컨텍스트 오버헤드가 여전히 존재합니다.

#### 2. 기술 선택의 맥락

JPA와 JDBC는 각각 장단점이 명확합니다:
- JPA: 복잡한 비즈니스 로직, 연관관계 관리
- JDBC: 대용량 배치, 단순 INSERT/UPDATE

본 프로젝트는 대용량 배치 처리에 해당하므로 JDBC가 적합했습니다.

#### 3. 실측의 중요성

추측: "DTO Projection이면 충분할 것이다"
실측: "JDBC 직접 제어가 필요했다"

성능 최적화는 가설이 아닌 실측 데이터를 기반으로 이루어져야 합니다.

#### 4. 메모리 관리

대량 데이터 처리 시 영속성 컨텍스트의 메모리 누적은 치명적입니다. JDBC의 청크 단위 처리로 메모리 사용량을 일정하게 유지할 수 있었습니다.

### 적용 가능성

이 최적화 기법은 다음 조건에서 유용합니다:
- 대용량 데이터 배치 처리 (수만 건 이상)
- 단순한 데이터 구조 (복잡한 연관관계 없음)
- INSERT/UPDATE 위주 작업
- 메모리 제약이 있는 환경

반대로 다음 상황에서는 JPA 유지를 권장합니다:
- 복잡한 도메인 로직
- 빈번한 조회 및 객체 그래프 탐색
- 타입 안전성이 중요한 경우

---

## 📚 참고 자료

- [Spring JDBC Documentation](https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#jdbc)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [MariaDB Batch Statement Rewrite](https://mariadb.com/kb/en/about-mariadb-connector-j/#batch-statement-rewrite)
- [JPA vs JDBC Performance - Vlad Mihalcea](https://vladmihalcea.com/jpa-hibernate-batch-insert/)

---

## 📝 문서 정보

| 항목 | 내용 |
|------|------|
| **작성일** | 2025-12-07 |
| **작성자** | Hospital Info Project Team |
| **버전** | 2.0 |
| **관련 문서** | [대용량 API 호출 처리 시스템](./LARGE_SCALE_API_CALL_INFRASTRUCTURE.md) |

---
