# 병원 정보 프로젝트 - SW 구성도 및 흐름도

## 1. 전체 시스템 아키텍처 (SW 구성도)

```mermaid
graph TB
    subgraph "Application Layer"
        HospitalMain["Hospital Main (의료 백엔드)<br/>hospital_main"]
    end

    subgraph "External APIs (외부 API)"
        HospitalAPI["Hospital API<br/>(공공의료 데이터)"]
        YouTubeAPI["YouTube API<br/>(의료 정보)"]
        GeminiAPI["Gemini AI API<br/>(AI 챗봇)"]
    end

    subgraph "Core Framework & Libraries"
        SpringMVC["Spring WebMVC"]
        SpringJPA["Spring Data JPA<br/>+ Hibernate 6.2"]
        SpringWS["Spring WebSocket<br/>(실시간 응급실)"]
        Micrometer["Micrometer<br/>+ Prometheus"]
    end

    subgraph "Supporting Libraries"
        Jackson["Jackson<br/>(JSON/XML)"]
        Hibernate["Hibernate Spatial<br/>(위치 기반)"]
        Caffeine["Caffeine<br/>Cache"]
        HikariCP["HikariCP<br/>(Connection Pool)"]
        WebFlux["Spring WebFlux<br/>(Reactive)"]
        Quartz["Quartz<br/>Scheduler"]
    end

    subgraph "Runtime Platform"
        Spring["Spring Framework 6.0.13"]
    end

    subgraph "Container & Deployment"
        Tomcat["Apache Tomcat 10.1 (Servlet Container)"]
    end

    subgraph "Runtime Environment"
        JRE["JRE 21 (Amazon Corretto)"]
    end

    subgraph "Container Platform"
        Docker["Docker Container"]
    end

    subgraph "Operating System"
        Ubuntu["Ubuntu Linux 24 (EC2)"]
    end

    subgraph "Data & Monitoring"
        MariaDB["MariaDB 10.11<br/>(hospital_api_db)"]
        Prometheus["Prometheus<br/>(Metrics Collection)"]
        Grafana["Grafana<br/>(Dashboard)"]
        cAdvisor["cAdvisor<br/>(Container Metrics)"]
        NodeExporter["Node Exporter<br/>(System Metrics)"]
    end

    subgraph "CI/CD Pipeline"
        Jenkins["Jenkins<br/>(CI/CD Automation)"]
        Maven["Maven 3.9<br/>(Build Tool)"]
    end

    HospitalMain --> HospitalAPI
    HospitalMain --> YouTubeAPI
    HospitalMain --> GeminiAPI

    HospitalMain --> SpringMVC
    HospitalMain --> SpringJPA
    HospitalMain --> SpringWS
    HospitalMain --> Micrometer

    SpringMVC --> Jackson
    SpringJPA --> Hibernate
    SpringMVC --> Caffeine
    SpringJPA --> HikariCP
    SpringMVC --> WebFlux
    SpringMVC --> Quartz

    SpringMVC --> Spring
    SpringJPA --> Spring
    SpringWS --> Spring
    Micrometer --> Spring

    Spring --> Tomcat
    Tomcat --> JRE
    JRE --> Docker
    Docker --> Ubuntu

    HospitalMain --> MariaDB
    Micrometer --> Prometheus
    Prometheus --> Grafana
    Docker --> cAdvisor
    Ubuntu --> NodeExporter
    cAdvisor --> Prometheus
    NodeExporter --> Prometheus

    Maven --> Jenkins
    Jenkins --> Docker

    style HospitalMain fill:#26a69a
    style MariaDB fill:#26a69a
    style Prometheus fill:#26a69a
    style Grafana fill:#26a69a
    style cAdvisor fill:#26a69a
    style NodeExporter fill:#26a69a
    style Jenkins fill:#26a69a
    style Maven fill:#26a69a
```

## 2. 레이어별 상세 구성도

```mermaid
graph TB
    subgraph "프레젠테이션 계층 (Presentation Layer)"
        Controllers["Controllers (11개)<br/>- HospitalApiController<br/>- HospitalWebController<br/>- EmergencyApiController<br/>- StatisticsApiController<br/>- UnifiedSearchController<br/>- ChatbotController<br/>- YouTubeController<br/>- MonitoringController 등"]
        WSHandler["WebSocket Handler<br/>- EmergencyApiWebSocketHandler"]
    end

    subgraph "비즈니스 로직 계층 (Service Layer)"
        Services["Services (15개)<br/>- HospitalMainApiService<br/>- HospitalDetailApiService<br/>- HospitalWebService<br/>- EmergencyLiveService<br/>- PharmacyApiService<br/>- DiseaseStatsApiService<br/>- ChatbotService 등"]
    end

    subgraph "비동기 처리 계층 (Async Layer)"
        AsyncRunners["Async Runners (8개)<br/>- HospitalMainAsyncRunner<br/>- HospitalDetailAsyncRunner<br/>- PharmacyApiAsyncRunner<br/>- EmergencyLiveAsyncRunner 등"]
        Schedulers["Schedulers<br/>- EmergencyDataScheduler<br/>- Quartz Jobs"]
    end

    subgraph "외부 API 연동 계층 (Integration Layer)"
        ApiCallers["API Callers (10개)<br/>- HospitalMainApiCaller<br/>- EmergencyApiCaller<br/>- PharmacyApiCaller<br/>- DiseaseStatsApiCaller 등"]
        Parsers["Parsers<br/>- API 응답 파싱<br/>- XML/JSON 변환"]
    end

    subgraph "데이터 접근 계층 (Data Access Layer)"
        JPARepos["JPA Repositories (9개)<br/>- HospitalMainApiRepository<br/>- PharmacyApiRepository<br/>- EmergencyLocationRepository 등"]
        JDBCRepos["JDBC Repositories (최적화)<br/>- HospitalJdbcRepository<br/>- HospitalDetailJdbcRepository"]
    end

    subgraph "데이터 변환 계층 (Conversion Layer)"
        Converters["Converters<br/>- HospitalConverter<br/>- Entity ↔ DTO 변환"]
        RowMappers["Row Mappers<br/>- HospitalWebResponseRowMapper<br/>- JDBC ResultSet 매핑"]
    end

    subgraph "엔티티 계층 (Entity Layer)"
        Entities["Entities (9개)<br/>- HospitalMain<br/>- HospitalDetail<br/>- MedicalSubject<br/>- ProDoc<br/>- Pharmacy<br/>- EmergencyLocation<br/>- DiseaseStats<br/>- YouTubeVideo"]
    end

    subgraph "유틸리티 계층 (Utility Layer)"
        Utils["Utilities<br/>- DistanceCalculator<br/>- CoordinateConverter<br/>- StringUtils 등"]
        Validators["Validators<br/>- 입력 검증<br/>- API 키 검증"]
    end

    subgraph "인프라 계층 (Infrastructure Layer)"
        Config["Configuration<br/>- DatabaseConfig<br/>- WebConfig<br/>- AsyncConfig<br/>- WebSocketConfig"]
        Aspects["AOP Aspects<br/>- Logging<br/>- Transaction<br/>- Metrics"]
        Filters["Filters & Interceptors<br/>- CharacterEncodingFilter<br/>- MetricsInterceptor"]
    end

    Controllers --> Services
    WSHandler --> Services
    Services --> AsyncRunners
    Services --> ApiCallers
    Services --> JPARepos
    Services --> JDBCRepos
    AsyncRunners --> ApiCallers
    AsyncRunners --> Schedulers
    ApiCallers --> Parsers
    Parsers --> Converters
    JPARepos --> Entities
    JDBCRepos --> RowMappers
    Converters --> Entities
    Services --> Converters
    Services --> Utils
    Controllers --> Validators
    Config --> Services
    Aspects --> Services
    Filters --> Controllers
```

## 3. 데이터 흐름도 (Data Flow Diagram)

### 3.1 병원 데이터 수집 흐름

```mermaid
sequenceDiagram
    actor Admin as 관리자
    participant API as HospitalApiController
    participant Service as HospitalMainApiService
    participant Async as HospitalMainAsyncRunner<br/>(스레드풀: apiExecutor)
    participant RateLimit as RateLimiter<br/>(5 req/sec)
    participant Caller as HospitalMainApiCaller
    participant GovAPI as 정부 공공데이터 API
    participant Parser as HospitalMainApiParser
    participant Repo as HospitalMainRepository
    participant DB as MariaDB

    Admin->>API: POST /api/main/save<br/>Header: X-API-Key=7192

    API->>API: API 키 검증

    API->>Service: updateHospitalMain()

    Note over Service: 전국 17개 시도 코드 로드

    par 지역별 병렬 처리
        Service->>Async: runAsync("11") - 서울
        Service->>Async: runAsync("26") - 부산
        Service->>Async: runAsync("27") - 대구
        Service->>Async: ...17개 지역
    end

    loop 각 페이지 (numOfRows=100)
        Async->>RateLimit: acquire()
        RateLimit->>Caller: callApi(sidoCd, pageNo)
        Caller->>GovAPI: GET /getHospBasisList?sidoCd=11&pageNo=1
        GovAPI-->>Caller: JSON 응답
        Caller->>Caller: 결과 코드 검증 (00=성공)
        Caller-->>Async: HospitalMainApiResponse

        Async->>Parser: parseHospitals(response)
        Parser->>Parser: JSON → Entity 변환
        Parser-->>Async: List<HospitalMain>

        Async->>Repo: saveAll(hospitals) - 배치 100건
        Repo->>DB: BATCH INSERT
    end

    Async-->>Service: 완료 (성공/실패 건수)
    Service-->>API: 전체 진행 상황
    API-->>Admin: { success: true, completed: 15234, failed: 23 }
```

### 3.2 병원 검색 흐름 (위치 기반)

```mermaid
sequenceDiagram
    actor User as 사용자
    participant Web as HospitalWebController
    participant Service as HospitalWebService
    participant JDBC as HospitalJdbcRepository
    participant DB as MariaDB (PostGIS)
    participant Filter as HospitalTagFilter
    participant Converter as HospitalConverter

    User->>Web: GET /web/hospitalsData<br/>?userLat=37.5&userLng=127.0<br/>&radius=5&departments=내과,외과

    Web->>Service: getOptimizedHospitalsV2(lat, lng, radius, departments)

    Service->>Service: MBR 계산<br/>minLat, maxLat, minLng, maxLng

    Note over Service: 위도: ±radius/110km<br/>경도: ±radius/(111.32*cos(lat))

    Service->>JDBC: findByMBRDirect(minLng, maxLng, minLat, maxLat)

    JDBC->>DB: SELECT h.*, d.*<br/>FROM hospital_main h<br/>LEFT JOIN hospital_detail d<br/>WHERE h.coordinateX BETWEEN ? AND ?<br/>AND h.coordinateY BETWEEN ? AND ?

    DB-->>JDBC: ResultSet (병원 데이터)

    JDBC->>JDBC: 진료과목 배치 로드<br/>SELECT * FROM medical_subject<br/>WHERE hospital_code IN (...)

    JDBC->>JDBC: 전문의 배치 로드<br/>SELECT * FROM pro_doc<br/>WHERE hospital_code IN (...)

    JDBC->>JDBC: RowMapper로 DTO 변환<br/>(N+1 쿼리 방지)

    JDBC-->>Service: List<HospitalWebResponse>

    alt 진료과 필터링 요청
        Service->>Filter: filterByDepartments(hospitals, ["내과","외과"])
        Filter->>Filter: 진료과목 매칭 검사
        Filter-->>Service: 필터링된 병원 목록
    end

    Service->>Service: 거리 계산 및 정렬<br/>Haversine 공식

    Service->>Service: Limit 적용 (기본 50개)

    Service-->>Web: List<HospitalWebResponse>

    Web->>Web: JSON 직렬화

    Web-->>User: JSON 응답<br/>[{hospitalName, distance, departments, ...}]
```

### 3.3 응급실 실시간 데이터 흐름

```mermaid
sequenceDiagram
    actor User as 사용자
    participant WS as WebSocket Client
    participant Handler as EmergencyWebSocketHandler
    participant Service as EmergencyLiveService
    participant Async as EmergencyLiveAsyncRunner
    participant Scheduler as TaskScheduler (3분 주기)
    participant Caller as EmergencyApiCaller
    participant GovAPI as 정부 응급실 API
    participant Cache as 메모리 캐시<br/>(latestEmergencyJson)

    User->>WS: WebSocket 연결 시도
    WS->>Handler: connect()

    Handler->>Service: onWebSocketConnected()

    alt 첫 번째 연결
        Service->>Async: runAsyncForAllCities(callback)

        Note over Async: 즉시 첫 실행
        Async->>Async: fetchAndBroadcast()

        par 전국 시도별 병렬 호출
            Async->>Caller: callApi("서울")
            Async->>Caller: callApi("부산")
            Async->>Caller: callApi("경기")
            Async->>Caller: ...17개 지역
        end

        Caller->>GovAPI: GET /getEmrrmRltmUsefulSckbdInfoInqire<br/>?STAGE1=서울
        GovAPI-->>Caller: XML 응답

        Async->>Async: XML 파싱 및 좌표 매핑
        Async->>Async: 이전 데이터와 변경 감지
        Async->>Cache: 최신 데이터 저장

        Async->>Handler: callback(emergencyData)
        Handler->>WS: broadcastEmergencyRoomData()
        WS-->>User: JSON 응답 (실시간 응급실 정보)

        Note over Scheduler: 3분 주기 스케줄러 시작
        Scheduler->>Async: 3분 후 재실행

    else 추가 연결
        Service->>Cache: 캐시된 데이터 조회
        Cache-->>Service: 최신 응급실 데이터
        Service->>Handler: sendToClient()
        Handler->>WS: send()
        WS-->>User: JSON 응답 (캐시된 데이터)
    end

    loop 3분마다 반복
        Scheduler->>Async: fetchAndBroadcast()
        Async->>Caller: 전국 응급실 데이터 수집
        Caller->>GovAPI: API 호출
        GovAPI-->>Caller: 응답
        Async->>Cache: 업데이트
        Async->>Handler: broadcast()
        Handler->>WS: send()
        WS-->>User: 실시간 업데이트
    end

    User->>WS: WebSocket 연결 종료
    WS->>Handler: disconnect()
    Handler->>Service: onWebSocketDisconnected()

    alt 마지막 연결 종료
        Service->>Scheduler: stopScheduler()
        Service->>Async: cleanup()
        Note over Service: 리소스 정리<br/>스케줄러 중지
    end
```

## 4. 컴포넌트 다이어그램

```mermaid
graph TB
    subgraph "Hospital Main Application"
        subgraph "API Controllers"
            C1[HospitalApiController<br/>/api/main, /api/details]
            C2[EmergencyApiController<br/>/api/emergency]
            C3[StatisticsApiController<br/>/api/stats]
            C4[HospitalWebController<br/>/web]
            C5[UnifiedSearchController<br/>/search]
        end

        subgraph "Services"
            S1[HospitalMainApiService]
            S2[HospitalWebService]
            S3[EmergencyLiveService]
            S4[DiseaseStatsApiService]
            S5[UnifiedSearchService]
        end

        subgraph "Async Processing"
            A1[HospitalMainAsyncRunner]
            A2[EmergencyLiveAsyncRunner]
            A3[ThreadPoolTaskExecutor<br/>apiExecutor: 10-15]
            A4[TaskScheduler<br/>Pool: 3]
        end

        subgraph "External Integration"
            I1[HospitalMainApiCaller]
            I2[EmergencyApiCaller]
            I3[PharmacyApiCaller]
            I4[DiseaseStatsApiCaller]
            I5[RateLimiter<br/>5 req/sec]
        end

        subgraph "Data Access"
            D1[HospitalMainRepository<br/>JPA]
            D2[HospitalJdbcRepository<br/>JDBC]
            D3[PharmacyRepository]
            D4[EmergencyLocationRepository]
            D5[HikariCP<br/>Max: 50 connections]
        end

        subgraph "Caching & Optimization"
            O1[Caffeine Cache]
            O2[Hibernate Second Level Cache]
            O3[EntityGraph<br/>N+1 쿼리 방지]
        end
    end

    subgraph "External Systems"
        E1[정부 공공데이터 API<br/>의료기관/응급실/통계]
        E2[Google Gemini AI<br/>챗봇]
        E3[YouTube API<br/>의료 영상]
    end

    subgraph "Database"
        DB1[(MariaDB 10.11<br/>hospital_api_db)]
        DB2[PostGIS Extension<br/>공간 데이터 처리]
    end

    subgraph "Monitoring Stack"
        M1[Micrometer]
        M2[Prometheus]
        M3[Grafana]
        M4[cAdvisor]
        M5[Node Exporter]
    end

    C1 --> S1
    C2 --> S3
    C3 --> S4
    C4 --> S2
    C5 --> S5

    S1 --> A1
    S3 --> A2
    A1 --> A3
    A2 --> A4

    A1 --> I1
    A2 --> I2
    I1 --> I5
    I2 --> I5
    I3 --> I5
    I4 --> I5

    I1 --> E1
    I2 --> E1
    I3 --> E1
    I4 --> E1

    S2 --> D2
    S1 --> D1
    S3 --> D4
    S5 --> D3

    D1 --> O3
    D2 --> D5
    S3 --> O1

    D1 --> DB1
    D2 --> DB1
    D3 --> DB1
    D4 --> DB1

    DB1 --> DB2

    S1 --> M1
    S2 --> M1
    S3 --> M1
    M1 --> M2
    M2 --> M3
    M4 --> M2
    M5 --> M2
```

## 5. 배포 아키텍처 다이어그램

```mermaid
graph TB
    subgraph "CI/CD Pipeline"
        Dev[개발자<br/>코드 푸시]
        Git[Git Repository<br/>GitHub/GitLab]
        Jenkins[Jenkins CI/CD<br/>자동 빌드/배포]
        Maven[Maven 3.9<br/>mvn clean package]
        Artifact[WAR 파일<br/>hospital_main.war]
    end

    subgraph "EC2 Instance (Ubuntu 24)"
        subgraph "Docker Container"
            subgraph "Tomcat 10.1"
                subgraph "Spring Framework 6.0.13"
                    App[Hospital Main<br/>Application]
                end
            end

            JRE[JRE 21<br/>Amazon Corretto]
        end

        subgraph "Database Container"
            MariaDB[(MariaDB 10.11<br/>hospital_api_db)]
        end

        subgraph "Monitoring Containers"
            Prometheus[Prometheus<br/>메트릭 수집]
            Grafana[Grafana<br/>대시보드]
            cAdvisor[cAdvisor<br/>컨테이너 메트릭]
            NodeExporter[Node Exporter<br/>시스템 메트릭]
        end

        Docker[Docker Engine]
    end

    subgraph "External Services"
        GovAPI[정부 공공데이터 API]
        GeminiAPI[Gemini AI API]
        YouTubeAPI[YouTube API]
    end

    Dev --> Git
    Git --> Jenkins
    Jenkins --> Maven
    Maven --> Artifact
    Artifact --> Docker
    Docker --> Tomcat
    Tomcat --> JRE

    App --> MariaDB
    App -.->|HTTPS| GovAPI
    App -.->|HTTPS| GeminiAPI
    App -.->|HTTPS| YouTubeAPI

    App --> Prometheus
    cAdvisor --> Prometheus
    NodeExporter --> Prometheus
    Prometheus --> Grafana

    style Jenkins fill:#26a69a
    style Maven fill:#26a69a
    style MariaDB fill:#26a69a
    style Prometheus fill:#26a69a
    style Grafana fill:#26a69a
    style cAdvisor fill:#26a69a
    style NodeExporter fill:#26a69a
```

## 7. 성능 최적화 전략

```mermaid
mindmap
  root((성능 최적화))
    데이터베이스
      HikariCP 커넥션 풀
        Max 50 connections
        Min 10 connections
      JDBC 직접 쿼리
        JPA 오버헤드 제거
        RowMapper 사용
      배치 처리
        100건 단위 INSERT
        saveAll 최적화
      EntityGraph
        N+1 쿼리 방지
        관계 데이터 즉시 로드
      PostGIS 공간 인덱스
        POINT 타입 사용
        MBR 사각형 검색
    비동기 처리
      스레드 풀
        apiExecutor 10-15
        taskExecutor 2-5
        taskScheduler 3
      병렬 처리
        지역별 병렬 수집
        CompletableFuture
      Rate Limiting
        5 req/sec
        API 호출 제한
    캐싱
      Caffeine Cache
        응급실 데이터
        인메모리 캐싱
      Hibernate 2nd Cache
        엔티티 캐싱
      HTTP 캐시
        응답 캐싱
    네트워크
      WebSocket
        실시간 통신
        양방향 연결
      HTTP/2
        멀티플렉싱
      압축
        GZIP
```

## 8. 보안 아키텍처

```mermaid
graph TB
    subgraph "클라이언트"
        User[사용자]
    end

    subgraph "보안 계층"
        CORS[CORS Policy<br/>허용된 출처만]
        APIKey[API Key 인증<br/>X-API-Key 헤더]
        SSL[HTTPS/TLS<br/>암호화 통신]
    end

    subgraph "애플리케이션 보안"
        InputVal[입력 검증<br/>Validator]
        SQLInj[SQL Injection 방지<br/>PreparedStatement]
        XSS[XSS 방지<br/>입력 필터링]
    end

    subgraph "데이터 보안"
        Encrypt[민감 정보 암호화<br/>API 키 관리]
        Backup[데이터 백업<br/>정기 백업]
    end

    subgraph "모니터링 & 감사"
        Logging[로깅<br/>SLF4J + Logback]
        Metrics[메트릭 수집<br/>Prometheus]
        Audit[감사 로그<br/>접근 기록]
    end

    User --> CORS
    CORS --> APIKey
    APIKey --> SSL
    SSL --> InputVal
    InputVal --> SQLInj
    SQLInj --> XSS
    XSS --> Encrypt

    InputVal --> Logging
    SQLInj --> Logging
    XSS --> Logging
    Encrypt --> Backup

    Logging --> Metrics
    Metrics --> Audit
```

## 9. 에러 처리 흐름

```mermaid
graph TB
    Request[클라이언트 요청]

    Request --> Validation{입력 검증}

    Validation -->|실패| ValidationError[400 Bad Request<br/>입력 오류 메시지]
    Validation -->|성공| Auth{API 키 인증}

    Auth -->|실패| AuthError[401 Unauthorized<br/>인증 실패]
    Auth -->|성공| Business[비즈니스 로직 처리]

    Business --> APICall{외부 API 호출}

    APICall -->|타임아웃| TimeoutError[504 Gateway Timeout<br/>외부 API 응답 없음]
    APICall -->|실패| APIError[502 Bad Gateway<br/>외부 API 오류]
    APICall -->|성공| DataAccess{데이터 접근}

    DataAccess -->|DB 오류| DBError[500 Internal Server Error<br/>데이터베이스 오류]
    DataAccess -->|성공| Processing[데이터 처리]

    Processing --> ParseError{파싱/변환 오류}

    ParseError -->|실패| DataError[500 Internal Server Error<br/>데이터 처리 오류]
    ParseError -->|성공| Success[200 OK<br/>성공 응답]

    ValidationError --> ErrorHandler[GlobalExceptionHandler<br/>통합 에러 처리]
    AuthError --> ErrorHandler
    TimeoutError --> ErrorHandler
    APIError --> ErrorHandler
    DBError --> ErrorHandler
    DataError --> ErrorHandler

    ErrorHandler --> Logging[에러 로깅<br/>SLF4J]
    ErrorHandler --> Metrics[메트릭 수집<br/>Prometheus]
    ErrorHandler --> Response[클라이언트 응답<br/>JSON 에러 메시지]

    Success --> Logging
    Success --> Metrics
    Success --> Response
```

## 10. 주요 API 엔드포인트 맵

```mermaid
graph LR
    subgraph "병원 API (데이터 수집)"
        A1[POST /api/main/save<br/>병원 기본 정보]
        A2[GET /api/main/status<br/>수집 진행 상황]
        A3[POST /api/details/save<br/>병원 상세 정보]
        A4[POST /api/subject/save<br/>진료과목 정보]
        A5[POST /api/proDoc/save<br/>전문의 정보]
    end

    subgraph "병원 Web (데이터 조회)"
        B1[GET /web/hospitalsData<br/>위치 기반 병원 검색]
        B2[GET /web/hospitalsDataFiltered<br/>진료과 필터링]
        B3[GET /web/pharmaciesData<br/>약국 검색]
    end

    subgraph "응급실 API"
        C1[GET /api/emergency/start<br/>즉시 수집 시작]
        C2[GET /api/emergency/stop<br/>서비스 종료]
        C3[GET /api/emergency/status<br/>상태 조회]
        C4[WebSocket /emergency<br/>실시간 스트리밍]
    end

    subgraph "통계 API"
        D1[POST /api/stats/disease/save<br/>질병 통계 수집]
        D2[GET /api/stats/disease/status<br/>수집 진행 상황]
        D3[GET /stats/...<br/>통계 조회]
    end

    subgraph "통합 검색"
        E1[GET /search/unifiedData<br/>병원 + 약국 통합]
    end

    subgraph "부가 기능"
        F1[POST /chatbot/...<br/>AI 챗봇]
        F2[GET /youtube/...<br/>의료 영상]
        F3[GET /metrics<br/>모니터링]
    end
```

## 요약


### 핵심 기술 스택
- **Runtime**: JRE 21 (Amazon Corretto) → Docker Container → Ubuntu Linux 24 (EC2)
- **Framework**: Spring Framework 6.0.13 + Spring MVC + Spring Data JPA
- **Database**: MariaDB 10.11 (PostGIS 공간 데이터)
- **Container**: Apache Tomcat 10.1
- **Monitoring**: Prometheus + Grafana + cAdvisor + Node Exporter
- **CI/CD**: Jenkins + Maven 3.9

### 주요 아키텍처 패턴
1. **비동기 병렬 처리**: 지역별 데이터 수집을 스레드 풀로 병렬화
2. **Rate Limiting**: API 호출 제한 (5 req/sec)
3. **JDBC 최적화**: N+1 쿼리 방지 및 성능 최적화
4. **WebSocket 실시간 통신**: 응급실 데이터 3분 주기 업데이트
5. **캐싱 전략**: Caffeine + Hibernate 2차 캐시
6. **공간 데이터 처리**: PostGIS + MBR 검색

### 데이터 흐름
1. **수집**: 정부 API → Parser → Entity → Database
2. **조회**: Database → JDBC Query → RowMapper → DTO → JSON
3. **실시간**: WebSocket → Scheduler → API → Broadcast → Client
