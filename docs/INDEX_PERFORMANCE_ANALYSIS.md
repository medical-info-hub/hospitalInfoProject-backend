# 🔍 공간 인덱스 vs 복합 인덱스 성능 분석 - 7만 건 규모 최적화

> **SPATIAL INDEX와 복합 인덱스를 실측 비교하여 Full Scan이 최적임을 검증**
> MBRContains 오버헤드 분석부터 JOIN 환경에서의 인덱스 선택도 검증

---

## 📑 목차

1. [개요](#-개요)
2. [초기 구현: 공간 인덱스 도입](#-초기-구현-공간-인덱스-도입)
3. [1차 분석: MBRContains 오버헤드 분석](#-1차-분석-mbrcontains-오버헤드-분석)
4. [2차 최적화: 복합 인덱스 도입](#-2차-최적화-복합-인덱스-도입)
5. [복합 인덱스 검증 (JOIN 없는 환경)](#-복합-인덱스-검증-join-없는-환경)
6. [캐시 효과 제거를 위한 순서 교대 테스트](#-캐시-효과-제거를-위한-순서-교대-테스트)
7. [JOIN 환경에서의 인덱스 미사용 분석](#-join-환경에서의-인덱스-미사용-분석)
8. [FORCE INDEX를 통한 선택도 분석](#-force-index를-통한-선택도-분석)
9. [프론트엔드 원형 필터링 위임](#-프론트엔드-원형-필터링-위임)
10. [성능 측정 결과](#-성능-측정-결과)
11. [결론](#-결론)

---

## 🎯 개요

### 프로젝트 배경

위치 기반 병원 검색 시스템에서 사용자 위치 기준 반경 5km 내 병원을 검색하는 API를 개발했습니다.
전국 약 **79,081개 병원 데이터**에서 빠르게 검색하기 위해 **공간 인덱스(SPATIAL INDEX)**를 도입했으나, 예상과 다른 성능 결과를 확인했습니다.

### 기술 스택

| 항목 | 기술 |
|------|------|
| **DB** | MariaDB 10.11 |
| **Framework** | Spring 6.0.13 (순수 Spring) |
| **데이터 규모** | 79,081개 병원 |
| **쿼리 방식** | JDBC Template (LEFT JOIN 사용) |
| **테스트 도구** | EXPLAIN, 순서 교대 성능 측정 |

### 핵심 발견

```diff
- 공간 인덱스(SPATIAL INDEX): MBRContains 오버헤드로 2-3배 느림
- 복합 인덱스(coordinate_x, coordinate_y): JOIN 환경에서 선택도 낮아 비효율
+ Full Scan + BETWEEN이 7만 건 규모에서 최적 (인덱스 없이)
+ 백엔드: 사각형(MBR) 필터링, 프론트엔드: 원형 필터링 역할 분담
```

---

## 🗺️ 초기 구현: 공간 인덱스 도입

### 구현

```sql
-- 1. POINT 컬럼 추가
ALTER TABLE hospital_main ADD COLUMN location POINT;
UPDATE hospital_main SET location = POINT(coordinate_x, coordinate_y);

-- 2. SPATIAL INDEX 생성
CREATE SPATIAL INDEX idx_location ON hospital_main(location);

-- 3. MBRContains 쿼리
SELECT h.*, d.*
FROM hospital_main h
LEFT JOIN hospital_detail d ON h.hospital_code = d.hospital_code
WHERE MBRContains(
    ST_GeomFromText(
        CONCAT('POLYGON((', ?, ' ', ?, ',', ?, ' ', ?, ',',
                            ?, ' ', ?, ',', ?, ' ', ?, ',', ?, ' ', ?, '))'),
        4326
    ),
    h.location
);
```

### 초기 기대

- R-Tree 기반 공간 인덱스 활용
- Full Scan 제거
- 빠른 2차원 범위 검색

---

## ⚠️ 1차 분석: MBRContains 오버헤드 분석

### 성능 테스트 결과 (초기)

| 영역 | 공간 인덱스 (MBRContains) | BETWEEN (복합 인덱스) | 차이 |
|------|-------------------------|---------------------|------|
| **제주도 (작은 영역)** | 8ms | 12ms | 공간 인덱스 33% 빠름 |
| **강남 (중간 영역)** | 29ms | 32ms | 거의 동일 (9% 차이) |
| **서울 전체 (큰 영역)** | 110ms | 37ms | BETWEEN이 3배 빠름 |

### 문제 분석

**공간 인덱스의 오버헤드:**
```sql
MBRContains(
    ST_GeomFromText(CONCAT('POLYGON(...)', 4326),  -- POLYGON 생성
    h.location                                      -- 공간 연산 수행
)
```

1. **ST_GeomFromText()**: 문자열 → GEOMETRY 변환
2. **CONCAT()**: POLYGON 문자열 동적 생성
3. **공간 연산**: R-Tree 탐색 + MBR 포함 검사

**넓은 영역일수록:**
- 공간 연산 비용 > 인덱스 이득
- Full Scan에 가까운 탐색
- CPU 연산 오버헤드 증가

---

## 🔧 2차 최적화: 복합 인덱스 도입

### 전략

공간 인덱스의 오버헤드를 제거하고 단순한 BETWEEN 범위 검색을 사용했습니다.

```sql
-- 1. 복합 인덱스 생성
CREATE INDEX idx_coordinates ON hospital_main(coordinate_x, coordinate_y);

-- 2. BETWEEN 쿼리
SELECT h.*, d.*
FROM hospital_main h
LEFT JOIN hospital_detail d ON h.hospital_code = d.hospital_code
WHERE h.coordinate_x BETWEEN ? AND ?
  AND h.coordinate_y BETWEEN ? AND ?
```

### 기대 효과

- 단순한 숫자 비교 (공간 연산 제거)
- B-Tree 복합 인덱스 활용
- CPU 오버헤드 감소

---

## ✅ 복합 인덱스 검증 (JOIN 없는 환경)

### 테스트 쿼리

```sql
-- JOIN 없이 테스트
SELECT hospital_code, hospital_name, coordinate_x, coordinate_y
FROM hospital_main
WHERE coordinate_x BETWEEN 127.02 AND 127.03
  AND coordinate_y BETWEEN 37.49 AND 37.51;
```

### EXPLAIN 결과

```
id: 1
type: range
key: idx_coordinates
rows: 4,000
Extra: Using index condition
```

**검증 결과**: 강남 좁은 범위(4,000건, 5.7% 선택도)에서 복합 인덱스 활용 확인

---

## 🧪 캐시 효과 제거를 위한 순서 교대 테스트

### 초기 테스트의 문제점

```
Round 1: 공간 인덱스 (캐시 콜드) → 110ms
         BETWEEN (캐시 히트)      → 37ms

→ 캐시 효과로 왜곡된 결과
```

### 개선: 순서 교대 테스트

```
Round 1: 공간 인덱스 먼저 → BETWEEN 나중
Round 2: BETWEEN 먼저 → 공간 인덱스 나중
Round 3: 공간 인덱스 먼저 → BETWEEN 나중
...
```

### 강남 지역 순서 교대 테스트 결과

| Round | 순서 | 공간 인덱스 | 복합 인덱스 |
|-------|------|------------|------------|
| 1 | 공간 → 복합 | 10ms | 17ms |
| 2 | 복합 → 공간 | 41ms | 36ms |
| 3 | 공간 → 복합 | 46ms | 14ms |
| 4 | 복합 → 공간 | 6ms | 5ms |
| 5 | 공간 → 복합 | 10ms | 4ms |
| 6 | 복합 → 공간 | 9ms | 7ms |
| 7 | 공간 → 복합 | 15ms | 10ms |
| 8 | 복합 → 공간 | 5ms | 5ms |
| 9 | 공간 → 복합 | 8ms | 0ms |
| 10 | 복합 → 공간 | 22ms | 0ms |

### 결과 분석

```
공간 인덱스(MBRContains + location):
  평균: 17ms, 최소: 5ms, 최대: 46ms

복합 인덱스(BETWEEN + coordinate_x/y):
  평균: 9ms, 최소: 0ms, 최대: 36ms

→ 복합 인덱스가 2배 빠름 (캐시 효과 제거 후에도)
```

### 공간 인덱스가 느린 이유

**좁은 범위에서도:**
1. MBRContains 공간 연산 오버헤드
2. ST_GeomFromText + CONCAT 비용
3. R-Tree 탐색 비용

**단순 BETWEEN이 빠른 이유:**
1. 숫자 비교만 수행
2. CPU 연산 최소화
3. B-Tree 탐색 (1차원 × 2)

---

## 🚧 JOIN 환경에서의 인덱스 미사용 분석

### 실제 프로덕션 쿼리

```sql
-- LEFT JOIN 포함
SELECT h.*, d.*
FROM hospital_main h
LEFT JOIN hospital_detail d ON h.hospital_code = d.hospital_code
WHERE h.coordinate_x BETWEEN 127.070 AND 127.184
  AND h.coordinate_y BETWEEN 37.375 AND 37.466;
```

### EXPLAIN 결과

```
id: 1
type: ALL
key: NULL
rows: 79,081
Extra: Using where
```

### 분석 결과

JOIN이 없으면 인덱스를 사용하던 쿼리가, LEFT JOIN을 추가하는 순간 Full Scan으로 전환되는 것을 확인했습니다.

**옵티마이저 판단:**
- 복합 인덱스 비용 > Full Scan 비용
- JOIN 비용까지 고려하면 Full Scan이 더 효율적

---

## 🔬 FORCE INDEX를 통한 선택도 분석

### 강제 인덱스 사용

```sql
EXPLAIN
SELECT h.*, d.*
FROM hospital_main h FORCE INDEX (idx_coordinates)
LEFT JOIN hospital_detail d ON h.hospital_code = d.hospital_code
WHERE h.coordinate_x BETWEEN 127.070 AND 127.184
  AND h.coordinate_y BETWEEN 37.375 AND 37.466;
```

### EXPLAIN 결과

```
id: 1
type: range
key: idx_coordinates (강제)
rows: 23,000
Extra: Using index condition
```

### 실제 반환 데이터

```
실제 반환: 1,000개 병원
```

### 핵심 문제 발견

**23,000 스캔 / 1,000 반환 = 23배 비효율**

**복합 인덱스의 한계:**
```
INDEX (coordinate_x, coordinate_y)

1단계: coordinate_x BETWEEN 127.070 AND 127.184
→ 경도 범위 해당: 23,000개 (서울 동쪽 전체, 남북으로 전국)
   선택도: 29% (23,000/79,000)

2단계: coordinate_y BETWEEN 37.375 AND 37.466
→ 위도 필터링: 1,000개로 축소
   선택도: 1.3% (1,000/79,000)
```

**문제:**
- 경도만으로는 선택도가 매우 낮음 (29%)
- 복합 인덱스는 첫 번째 컬럼 우선 → 23,000개 스캔
- 23,000개 × 랜덤 I/O 비용 > Full Scan 79,000개 × 순차 I/O

### 옵티마이저의 선택

```
복합 인덱스 사용:
  23,000개 인덱스 스캔 + 랜덤 I/O + JOIN
  = 느림

Full Scan:
  79,000개 순차 스캔 + JOIN
  = 더 빠름
```

---

## 🌐 프론트엔드 원형 필터링 위임

### 백엔드 vs 프론트엔드 역할 분담

**백엔드: 사각형(MBR) 필터링만 수행**

```java
// 사용자 반경 5km 요청
double radius = 5.0;

// MBR 계산
double deltaDegreeY = radius / 110.0;
double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(userLat));
double deltaDegreeX = radius / kmPerDegreeLon;

double minLon = userLng - deltaDegreeX;
double maxLon = userLng + deltaDegreeX;
double minLat = userLat - deltaDegreeY;
double maxLat = userLat + deltaDegreeY;

// 사각형 범위 조회
WHERE coordinate_x BETWEEN minLon AND maxLon
  AND coordinate_y BETWEEN minLat AND maxLat
```

**프론트엔드: 원형 필터링**

```javascript
// 백엔드에서 사각형 영역 결과 받음
const hospitals = await fetchHospitals(userLat, userLng, 5);

// 원형 필터링 (Haversine 공식)
const filtered = hospitals.filter(h => {
  const distance = calculateDistance(
    userLat, userLng,
    h.coordinate_y, h.coordinate_x
  );
  return distance <= 5;
});

// SessionStorage 캐싱 가능
sessionStorage.setItem('hospitals', JSON.stringify(filtered));
```

### 역할 분담의 이점

| 항목 | 백엔드 (사각형) | 프론트엔드 (원형) |
|------|---------------|------------------|
| **연산** | BETWEEN (단순) | Haversine 공식 |
| **비용** | DB 스캔 최소화 | 클라이언트 CPU |
| **캐싱** | Redis (Geohash) | SessionStorage |
| **정확도** | 사각형 (모서리 포함) | 원형 (정확) |

**시너지 효과:**
- 백엔드: 빠른 사각형 필터링 → 네트워크 전송량 감소
- 프론트엔드: 정확한 원형 필터링 → UX 개선
- 캐싱: 양쪽 모두 가능 → 중복 요청 제거

---

## 📊 성능 측정 결과

### EXPLAIN 비교

| 쿼리 조건 | JOIN | 범위 | rows | key | 선택도 | 결과 |
|----------|------|------|------|-----|--------|------|
| **테스트 (JOIN 없음)** | X | 강남 좁음 | 4,000 | idx_coordinates | 5.7% | 인덱스 사용 |
| **실제 API (JOIN 있음)** | O | 5km 반경 | 79,081 | NULL | - | Full Scan |
| **FORCE INDEX (강제)** | O | 5km 반경 | 23,000 | idx_coordinates (강제) | 29% | 비효율 23배 |
| **서울 전체** | O | 서울 전체 | 79,081 | NULL | 57% | Full Scan |

### 응답 시간 비교

| 영역 | 공간 인덱스 (MBRContains) | BETWEEN (Full Scan) | 승자 |
|------|-------------------------|-------------------|------|
| **제주도 (작음)** | 8ms | 12ms | 공간 인덱스 (+33%) |
| **강남 (중간)** | 29ms | 32ms | 거의 동일 |
| **강남 (순서 교대)** | 17ms | 9ms | BETWEEN 2배 빠름 |
| **서울 전체 (큼)** | 110ms | 37ms | BETWEEN 3배 빠름 |

### 선택도 분석

| 데이터 규모 | 반환 비율 | 인덱스 효과 | 옵티마이저 선택 |
|-----------|----------|-----------|---------------|
| **< 10%** | 높은 선택도 | 인덱스 효율적 | range (인덱스) |
| **10-20%** | 중간 선택도 | 경계선 | 상황에 따라 |
| **> 20%** | 낮은 선택도 | Full Scan 효율적 | ALL (Full Scan) |

**실제 케이스:**
- 5km 반경: 23,000/79,000 = 29% → Full Scan 선택
- 서울 전체: 40,000/79,000 = 57% → Full Scan 선택

---

## 🎉 결론

### 최종 성과

```diff
+ 공간 인덱스 제거: MBRContains 오버헤드 제거
+ 복합 인덱스 제거: 선택도 낮은 인덱스 유지 비용 제거
+ Full Scan + BETWEEN: 7만 건 규모에서 최적의 성능
+ 백엔드-프론트엔드 역할 분담: 사각형-원형 필터링 분리
```

### 핵심 교훈

1. **인덱스는 만능이 아닙니다**
   - 7만 건 규모에서는 Full Scan이 충분히 빠릅니다
   - 선택도가 낮으면 인덱스가 오히려 비효율적입니다

2. **공간 인덱스의 오버헤드**
   - MBRContains 공간 연산 비용 > 인덱스 이득
   - 단순 BETWEEN이 2-3배 빠릅니다

3. **복합 인덱스의 한계**
   - 2차원 범위 검색에서 선택도 낮습니다
   - JOIN 환경에서는 더욱 비효율적입니다
   - 23,000 스캔 / 1,000 반환 = 23배 비효율

4. **옵티마이저를 신뢰해야 합니다**
   - EXPLAIN으로 검증 필수
   - FORCE INDEX로 강제해도 느리면 Full Scan이 정답입니다

5. **캐시 효과 제거**
   - 순서 교대 테스트로 공정한 비교
   - DB 캐시는 성능 측정에서 고려해야 할 요소입니다

6. **백엔드-프론트엔드 협업**
   - 백엔드: 빠른 사각형 필터링 (DB 부하 최소화)
   - 프론트엔드: 정확한 원형 필터링 (SessionStorage 캐싱)

### 향후 개선 가능성

**7만 건을 넘어서:**
- 50만+ 건: 복합 인덱스 재검토
- 100만+ 건: Partitioning (지역별) 고려
- PostgreSQL PostGIS: GiST 인덱스 비교

**캐싱 강화:**
- Geohash 캐싱 (이미 적용): 캐시 HIT 시 29-124ms
- Redis Geo Commands: GEORADIUS 활용
- 프론트엔드 SessionStorage: 0ms (캐시 HIT)

### 최종 구성

```sql
-- 인덱스 없이 Full Scan + BETWEEN
SELECT h.*, d.*
FROM hospital_main h
LEFT JOIN hospital_detail d ON h.hospital_code = d.hospital_code
WHERE h.coordinate_x BETWEEN ? AND ?
  AND h.coordinate_y BETWEEN ? AND ?
```

**성능:**
- 응답 시간: 평균 30-50ms
- 스캔 방식: Full Scan (ALL)
- CPU 사용률: 6-7% (안정적)
- 확장성: Geohash 캐싱으로 보완

---

## 📚 참고 자료

- [MariaDB SPATIAL INDEX Documentation](https://mariadb.com/kb/en/spatial-index/)
- [MySQL Query Optimization: EXPLAIN](https://dev.mysql.com/doc/refman/8.0/en/explain.html)
- [Index Selectivity and Performance](https://use-the-index-luke.com/)
- [Composite Index vs Single Column Index](https://dev.mysql.com/doc/refman/8.0/en/multiple-column-indexes.html)
- [Geohash 캐싱 최적화 문서](./GEOHASH_CACHE_OPTIMIZATION.md)
- [대용량 API 호출 인프라 문서](./LARGE_SCALE_API_CALL_INFRASTRUCTURE.md)

---

## 📝 문서 정보

| 항목 | 내용 |
|------|------|
| **작성일** | 2025-12-22 |
| **작성자** | Hospital Info Project Team |
| **버전** | 1.0 |
| **테스트 환경** | MariaDB 10.11, 79,081개 병원 데이터 |
| **라이센스** | MIT |

---
