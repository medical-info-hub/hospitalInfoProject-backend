# 🗺️ 공간 인덱스 기반 병원 검색 최적화

> **복합 인덱스의 한계를 극복하고 MBR 기반 2단계 필터링으로 2배 성능 향상**
> ST_Distance_Sphere 원형 필터링의 함정부터 SPATIAL INDEX 최적화까지

---

## 📑 목차

1. [개요](#-개요)
2. [초기 구현: ST_Distance_Sphere의 함정](#-초기-구현-st_distance_sphere의-함정)
3. [1차 최적화: 복합 인덱스 시도](#-1차-최적화-복합-인덱스-시도)
4. [복합 인덱스가 작동하지 않은 이유](#-복합-인덱스가-작동하지-않은-이유)
5. [2차 최적화: SPATIAL INDEX + MBR 전략](#-2차-최적화-spatial-index--mbr-전략)
6. [EXPLAIN으로 검증하기](#-explain으로-검증하기)
7. [프론트엔드 원형 필터링 결정](#-프론트엔드-원형-필터링-결정)
8. [성능 측정 결과](#-성능-측정-결과)
9. [결론](#-결론)

---

## 🎯 개요

### 프로젝트 배경

위치 기반 병원 검색 시스템에서 사용자 위치 기준 **반경 3km 내 병원을 검색**하는 API를 개발했습니다.
전국 약 **8만 개 병원 데이터**에서 빠르게 검색하기 위해서는 **인덱스 최적화**가 필수였습니다.

**최적화 필요성**: Full Scan 80,000행 → 동시 접속 100명 시 800만 행 스캔 → DB CPU 과부하 → 컨테이너 다운 위험

### 기술 스택

| 항목 | 기술 |
|------|------|
| **DB** | MariaDB 10.11 |
| **Framework** | Spring 6.0.13 (순수 Spring) |
| **ORM** | JPA 3.0.11 → JDBC |
| **공간 함수** | ST_Distance_Sphere, MBRContains |
| **인덱스** | SPATIAL INDEX (R-Tree) |

### 핵심 성과

```diff
+ 응답 시간: 200-300ms → 100-150ms (2배 향상 ✅)
+ 스캔 행 수: 80,000행 → 220행 (99.72% 감소 ✅)
+ Full Table Scan 제거: 공간 인덱스 활용 (EXPLAIN 검증 ✅)
+ 동시 접속자 환경 대비: 컨테이너 안정성 확보 ✅
+ 프론트엔드 협업: 원형 필터링 역할 분담으로 효율 극대화 ✅
```

---

## ⚠️ 초기 구현: ST_Distance_Sphere의 함정

```sql
WHERE ST_Distance_Sphere(POINT(h.coordinate_x, h.coordinate_y), POINT(:lon, :lat)) <= :radius
```

**문제**: 함수로 감싼 컬럼 → 인덱스 사용 불가 → type=ALL (Full Scan 80,000행) → 응답 200-300ms

---

## 🔧 1차 최적화: 복합 인덱스 시도

**전략**: 사각형 BETWEEN (인덱스) + 원형 ST_Distance (정확도)

```sql
CREATE INDEX idx_coordinates ON hospital_main (coordinate_x, coordinate_y);

WHERE coordinate_x BETWEEN :minLon AND :maxLon
  AND coordinate_y BETWEEN :minLat AND :maxLat
  AND ST_Distance_Sphere(...) <= :radius
```

---

## ❌ 복합 인덱스가 작동하지 않은 이유

**EXPLAIN 결과**: type=ALL, key=NULL (인덱스 인식했으나 미사용)

**실패 원인**:
1. 두 컬럼 모두 BETWEEN (범위) → 복합 인덱스 효율 낮음
2. 옵티마이저 Cost 계산 → Full Scan이 더 빠르다고 판단
3. 동적 파라미터 → 범위 예측 불가 → 인덱스 무시
4. FORCE INDEX 사용해도 오히려 느림 (Random I/O 비용)

---

## 🚀 2차 최적화: SPATIAL INDEX + MBR 전략

**SPATIAL INDEX**: R-Tree 기반 2차원 공간 인덱스 (MBR 기반, O(log N))

### 구현

```sql
-- 1. POINT 컬럼 추가 (SPATIAL INDEX는 GEOMETRY 타입 필요)
ALTER TABLE hospital_main ADD COLUMN location POINT;
UPDATE hospital_main SET location = POINT(coordinate_x, coordinate_y);

-- 2. SPATIAL INDEX 생성
CREATE SPATIAL INDEX idx_location ON hospital_main (location);

-- 3. MBRContains 쿼리 (자동으로 SPATIAL INDEX 사용)
WHERE MBRContains(
  ST_GeomFromText('POLYGON((...))' 4326),  -- 사각형 영역
  h.location
)
```

**JDBC Template 사용 이유**: JPA N+1/카테시안 곱 회피 → LEFT JOIN + IN 절 배치 로드

**핵심 최적화**:
1. MBR 쿼리 → SPATIAL INDEX 활용
2. LEFT JOIN → Detail 한 번에 조회
3. 배치 로드 (IN 절) → N+1 방지
4. DTO 직접 매핑 → Entity 변환 오버헤드 제거

### ST_Distance_Sphere 제거 결정

**DB에서 원형 필터링 (기각)**: MBR (10ms) + ST_Distance 3,500개 (80-100ms) = 120-140ms
**프론트엔드 원형 필터링 (채택)**: MBR (10ms) + 네트워크 (30ms) + JS 계산 (5-10ms) = 45-50ms

→ 프론트엔드 필터링이 **2-3배 빠름** + SessionStorage 캐싱 가능

```javascript
// 프론트엔드: 사각형 결과를 캐싱
const filtered = hospitals.filter(h =>
  calculateDistance(userLat, userLng, h.lat, h.lng) <= 3
);

---

## 📊 EXPLAIN으로 검증하기

| 항목 | Before | After | 개선 |
|------|--------|-------|------|
| **type** | ALL (Full Scan) | range (Index Scan) | ✅ |
| **key** | NULL | idx_hospital_location | ✅ |
| **rows** | 80,000 | **220** | **99.72% 감소** ✅ |
| **응답 시간** | 200-300ms | 100-150ms | **2배 향상** ✅ |

---

## 📈 성능 측정 결과

| 최적화 단계 | type | key | rows | 응답 시간 | 개선 |
|------------|------|-----|------|---------|------|
| **초기 (ST_Distance만)** | ALL | NULL | 80,000 | 200-300ms | - |
| **1차 (복합 인덱스)** | ALL | NULL | 80,000 | 180-250ms | ❌ 효과 없음 |
| **2차 (SPATIAL INDEX)** | range | idx_hospital_location | **220** | 100-150ms | **2배 향상** ✅ |

**부가 개선**: JDBC 배치 로드 (IN 절) → N+1 문제 해결

---

## 🎉 결론

**최종 성과**: Full Scan 제거 (ALL → range) + 응답 2배 향상 (200-300ms → 100-150ms) + 스캔 행 99.72% 감소 (80,000 → 220)

**핵심 교훈**:
1. 복합 인덱스는 2차원 범위 검색에 비효율 → SPATIAL INDEX 필수
2. EXPLAIN으로 반드시 검증 (옵티마이저가 인덱스 무시 가능)
3. Backend (SPATIAL INDEX) + Frontend (원형 필터 + 캐싱) 역할 분담
4. ST_Distance_Sphere (느림) → MBRContains (빠름 + 인덱스 활용)

**향후 개선**: Geohash 캐싱 통합 (캐시 HIT 29-124ms), Redis Geo Commands (R-Tree), PostgreSQL PostGIS

---

## 📚 참고 자료

- [MariaDB SPATIAL INDEX Documentation](https://mariadb.com/kb/en/spatial-index/)
- [MySQL 8.0 Spatial Data Types](https://dev.mysql.com/doc/refman/8.0/en/spatial-types.html)
- [R-Tree 알고리즘 설명](https://en.wikipedia.org/wiki/R-tree)
- [ST_Distance vs MBRContains 성능 비교](https://gis.stackexchange.com/questions/186342/)
- [Geohash 캐싱 최적화 문서](./GEOHASH_CACHE_OPTIMIZATION.md)

---

## 📝 문서 정보

| 항목 | 내용 |
|------|------|
| **작성일** | 2025-12-07 |
| **작성자** | Hospital Info Project Team |
| **버전** | 1.0 |
| **관련 커밋** | 83718c9, f133ee9 |
| **라이센스** | MIT |

---

