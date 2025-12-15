# 🔄 JPA 기반 조회 구조의 성능 병목 분석과 JDBC 전환

> **JPA 기반 데이터 조회의 성능 병목 분석과 JDBC 전환에 따른 개선 효과**
> – DTO Projection 환경에서의 엔티티 변환 오버헤드 분석 –

---

## 📑 목차

1. [개요](#-개요)
2. [문제 정의: 20% 지점에서 컨테이너 다운](#-문제-정의-20-지점에서-컨테이너-다운)
3. [1차 시도: JPA DTO Projection](#-1차-시도-jpa-dto-projection)
4. [DTO Projection이 실패한 이유](#-dto-projection이-실패한-이유)
5. [2차 해결: JDBC Template 마이그레이션](#-2차-해결-jdbc-template-마이그레이션)
6. [성능 측정 결과](#-성능-측정-결과)
7. [결론](#-결론)

---

## 🎯 개요

### 프로젝트 배경

병원 상세정보 수집 시스템에서 **79,081개 병원 데이터**를 외부 API로부터 수집하고 DB에 저장하는 배치 작업을 진행했습니다.
공공데이터포털 API에서 주차 정보, 진료 시간 등 **20개 필드**를 수집하여 저장해야 했습니다.

### 핵심 문제

79,081개 처리 목표 → 20% 지점(~16,000개)에서 컨테이너 다운 (JPA Entity 변환 오버헤드 + 영속성 컨텍스트 메모리 누적)

### 기술 스택

| 항목 | 기술 |
|------|------|
| **Backend** | Java 21, Spring 6.0.13 |
| **ORM** | JPA 3.0.11 → Spring JDBC |
| **DB** | MariaDB 10.11 |
| **Connection Pool** | HikariCP |
| **동시성 제어** | Google Guava RateLimiter |

### 핵심 성과

```diff
+ 처리 완료율: 20% → 100% (완료 가능 ✅)
+ 처리 속도: 7건/초 → 20건/초 (286% 향상 ✅)
+ 소요 시간: 불가능 → 1시간 6분 ✅
+ 메모리 사용: OOM 발생 → 안정화 ✅
```

---

## ⚠️ 문제 정의: 20% 지점에서 컨테이너 다운

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

            // 3. DTO → Entity 변환 (문제 발생)
            List<HospitalDetail> entities = items.stream()
                .map(this::convertDtoToEntity)
                .collect(Collectors.toList());

            // 4. JPA 저장
            repository.saveAll(entities);
        }
    }

    // 매 API 호출마다 실행되는 변환 로직
    private HospitalDetail convertDtoToEntity(HospitalDetailApiItem item) {
        HospitalDetail entity = new HospitalDetail();
        entity.setHospitalCode(item.getHospitalCode());
        entity.setParkingCapacity(item.getParkQty());
        // ...총 20개 필드 수동 매핑!
        return entity;
    }
}
```

### 문제점

1. **DTO → Entity 변환**: 20개 필드 × 79,081번 = 누적 오버헤드
2. **JPA 영속성 컨텍스트**: 1차 캐시 + Dirty Checking → 메모리 누적
3. **처리 속도**: 7건/초 → 20% 지점에서 OOM/CPU 과부하 → 컨테이너 다운

---

## 🔧 1차 시도: JPA DTO Projection

### 전략

Entity 변환 문제 → DTO Projection으로 직접 조회 시도

```java
// Projection 인터페이스 (20개 Getter)
interface HospitalDetailProjection { ... }

// Projection → ApiItem 변환 (여전히 20개 필드 복사)
private HospitalDetailApiItem convertProjectionToApiItem(HospitalDetailProjection proj) { ... }
```

---

## ❌ DTO Projection이 실패한 이유

### 결과: 실패

**처리 속도**: 7건/초 → 7.5건/초 (미미한 향상)
**메모리**: 여전히 누적
**결론**: 20% 지점에서 여전히 컨테이너 다운

**실패 원인**:
1. Projection → ApiItem 변환에서 **여전히 20개 필드 복사**
2. JPA 내부적으로 **ResultSet → Entity → Projection** 변환 (Entity 생략 안 됨)
3. 영속성 컨텍스트 메모리 누적 여전

→ DTO Projection은 조회 최적화일 뿐, **대량 배치 처리에는 부적합**

---

## 🚀 2차 해결: JDBC Template 마이그레이션

### 핵심 아이디어

JPA 완전히 버리고 JDBC 직접 제어

### 구현

```java
// 1. RowMapper로 ResultSet → ApiItem 직접 매핑 (Entity 생략)
private static final RowMapper<HospitalDetailApiItem> ROW_MAPPER = (rs, rowNum) -> {
    // 20개 필드 직접 매핑
};

// 2. Batch Insert/Update
@Transactional
public void batchInsert(List<HospitalDetailApiItem> items) {
    int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
}

// 3. 청크(100개) 단위 조회 (메모리 효율)
public Map<String, HospitalDetailApiItem> findByHospitalCodeInAsMap(List<String> codes) {
    // IN 절로 100개만 조회
}
```

**핵심 최적화**: `rewriteBatchedStatements=true` 설정 → 100개 INSERT를 1번 네트워크 왕복으로 처리

### JPA vs JDBC 비교

| 항목 | JPA | JDBC |
|------|-----|------|
| **흐름** | DTO → Entity (20개 필드 복사) → 영속성 컨텍스트 → DB | DTO 직접 사용 → batchUpdate → DB |
| **메모리** | 1차 캐시 누적 → OOM | 청크별 일정 유지 |
| **성능** | 7건/초 (20% 실패) | 20건/초 (100% 완료) |

---

## 📊 성능 측정 결과

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
| **처리 속도** | 7건/초 | 20건/초 | 286% ↑ |
| **소요 시간** | 불가능 | 1시간 6분 | 완료 가능 |
| **메모리** | 누적 → OOM | 일정 유지 | 안정화 |

**JDBC 버전 로그**: 평균 19~20건/초 안정적 처리, 메모리 일정 유지, 실패 0건

---

## 결론

**최종 성과**: 20% 실패 → 100% 완료, 7건/초 → 20건/초 (286% 향상), OOM 해결

**여정**: 문제 인식 (20% 컨테이너 다운) → DTO Projection 시도 (실패) → JDBC 마이그레이션 (성공)

**핵심 교훈**:
1. DTO Projection은 대량 배치 처리에 부적합 (20개 필드 복잡 매핑, Entity 여전히 거침)
2. JPA (복잡한 로직, 소규모) vs JDBC (대량 배치, 메모리 효율)
3. JPA 1차 캐시는 대량 데이터에서 메모리 폭탄, JDBC는 청크별 일정 유지

**향후 개선**: RateLimiter 확장, CompletableFuture 병렬 처리, 재시도 로직 강화, 실시간 모니터링 API

---

## 📚 참고 자료

- [Spring JDBC Documentation](https://docs.spring.io/spring-framework/docs/current/reference/html/data-access.html#jdbc)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [MariaDB Batch Statement Rewrite](https://mariadb.com/kb/en/about-mariadb-connector-j/#batch-statement-rewrite)
- [JPA vs JDBC Performance](https://vladmihalcea.com/jpa-hibernate-batch-insert/)

---

## 📝 문서 정보

| 항목 | 내용 |
|------|------|
| **작성일** | 2025-12-07 |
| **작성자** | Hospital Info Project Team |
| **버전** | 1.0 |
| **관련 문서** | [대용량 API 데이터 수집 성능 최적화](./performance-optimization-wiki.md) |
| **라이센스** | MIT |

---


