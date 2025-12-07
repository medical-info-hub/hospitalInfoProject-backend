# 빌드 스테이지
FROM maven:3.9-amazoncorretto-21 AS builder

WORKDIR /app

# pom.xml 먼저 복사 (의존성 캐싱)
COPY pom.xml .

# [수정 포인트] go-offline 대신 resolve 사용
# resolve는 컴파일에 필요한 핵심 의존성만 다운로드하므로 에러가 훨씬 적습니다.
RUN mvn dependency:resolve -Dos.detected.classifier=linux-x86_64

# 소스 코드 복사
COPY src ./src

# db.properties 파일 수정 (파일이 존재하는 경우만)
RUN if [ -f "src/main/resources/db.properties" ]; then \
        echo "=== db.properties 수정 전 ===" && \
        cat src/main/resources/db.properties && \
        sed -i 's/localhost:3500/mariadb:3500/g' src/main/resources/db.properties && \
        echo "=== db.properties 수정 후 ===" && \
        cat src/main/resources/db.properties; \
    else \
        echo "db.properties 파일이 없습니다."; \
    fi

# Maven 빌드
# 여기서도 classifier 옵션을 유지합니다.
RUN echo "=== Maven 빌드 시작 ===" && \
    mvn clean package -DskipTests -B -Dos.detected.classifier=linux-x86_64

# 빌드 결과 확인
RUN echo "=== 빌드 결과 ===" && \
    ls -la target/ && \
    find target/ -name "*.war" -type f

# 실행 스테이지
FROM tomcat:10.1-jdk21-openjdk-slim

RUN apt-get update && apt-get install -y curl --no-install-recommends && rm -rf /var/lib/apt/lists/*

RUN rm -rf /usr/local/tomcat/webapps/*

COPY --from=builder /app/target/*.war /usr/local/tomcat/webapps/ROOT.war

RUN sed -i 's/port="8080"/port="8888"/g' /usr/local/tomcat/conf/server.xml

EXPOSE 8888

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=5 \
    CMD curl -f http://localhost:8888/actuator/health || exit 1

CMD ["catalina.sh", "run"]
