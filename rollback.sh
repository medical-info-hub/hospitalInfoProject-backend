#!/bin/bash
# Hospital Project 롤백 스크립트
set -e

echo "🔄 Hospital Project 롤백 시작..."

# 색상 정의
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

export COMPOSE_FILE="docker-compose.prod.yml"

# 현재 활성 컨테이너 확인
function get_active_container() {
    if docker ps | grep -q "hospital-backend-blue"; then
        echo "blue"
    elif docker ps | grep -q "hospital-backend-green"; then
        echo "green"
    else
        echo "none"
    fi
}

# 중지된 컨테이너 확인
function get_stopped_container() {
    local active=$1
    if [ "$active" = "blue" ]; then
        if docker ps -a | grep -q "hospital-backend-green"; then
            echo "green"
        else
            echo "none"
        fi
    elif [ "$active" = "green" ]; then
        if docker ps -a | grep -q "hospital-backend-blue"; then
            echo "blue"
        else
            echo "none"
        fi
    else
        echo "none"
    fi
}

# Nginx 설정 업데이트
function update_nginx_config() {
    local primary=$1
    local backup=$2
    
    echo "📝 Nginx 설정을 ${primary}로 전환 중..."
    
    cat > /opt/hospital/config/nginx/nginx.conf << EOF
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    log_format main '\$remote_addr - \$remote_user [\$time_local] "\$request" '
                    '\$status \$body_bytes_sent "\$http_referer" '
                    '"\$http_user_agent" "\$http_x_forwarded_for"';

    access_log /var/log/nginx/access.log main;

    sendfile on;
    tcp_nopush on;
    tcp_nodelay on;
    keepalive_timeout 65;

    gzip on;
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 6;
    gzip_types text/plain text/css text/xml text/javascript 
               application/json application/javascript application/xml+rss;

    # Docker의 내부 DNS resolver 사용
    resolver 127.0.0.11 valid=10s;
    resolver_timeout 5s;

    server {
        listen 80;
        server_name _;

        client_max_body_size 50M;

        location /nginx-health {
            access_log off;
            return 200 "healthy\n";
            add_header Content-Type text/plain;
        }

        location / {
            # Primary 백엔드로 설정
            set \$backend "hospital-backend-${primary}:8888";
            
            proxy_pass http://\$backend;
            proxy_next_upstream error timeout http_502 http_503 http_504;
            proxy_http_version 1.1;
            
            proxy_set_header Host \$host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto \$scheme;

            proxy_set_header Upgrade \$http_upgrade;
            proxy_set_header Connection "upgrade";

            proxy_buffering off;
            proxy_connect_timeout 60s;
            proxy_send_timeout 60s;
            proxy_read_timeout 60s;
        }
    }
}
EOF

    # Nginx 설정을 컨테이너에 복사
    echo "📋 Nginx 컨테이너에 설정 파일 적용 중..."
    
    # docker exec로 직접 작성
    docker exec hospital-nginx sh -c "cat > /etc/nginx/nginx.conf" < /opt/hospital/config/nginx/nginx.conf
    
    # Nginx 리로드
    docker exec hospital-nginx nginx -s reload
    echo -e "${GREEN}✅ Nginx 설정 리로드 완료${NC}"
}

# 메인 롤백 로직
echo "=========================================="
echo "        롤백 프로세스 시작"
echo "=========================================="

ACTIVE_CONTAINER=$(get_active_container)
ROLLBACK_CONTAINER=$(get_stopped_container $ACTIVE_CONTAINER)

if [ "$ROLLBACK_CONTAINER" = "none" ]; then
    echo -e "${RED}❌ 롤백할 컨테이너가 없습니다.${NC}"
    echo "현재 활성 컨테이너: ${ACTIVE_CONTAINER}"
    exit 1
fi

echo -e "${YELLOW}⚠️  다음 작업을 수행합니다:${NC}"
echo "  - 현재 활성: ${ACTIVE_CONTAINER}"
echo "  - 롤백 대상: ${ROLLBACK_CONTAINER}"
echo ""
read -p "계속하시겠습니까? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "롤백을 취소했습니다."
    exit 0
fi

# 롤백 대상 컨테이너 시작
echo -e "${BLUE}▶️ ${ROLLBACK_CONTAINER} 컨테이너 시작 중...${NC}"
if [ "$ROLLBACK_CONTAINER" = "green" ]; then
    docker-compose -f $COMPOSE_FILE up -d backend-green
else
    docker-compose -f $COMPOSE_FILE up -d backend-blue
fi

# 헬스체크
echo "⏳ ${ROLLBACK_CONTAINER} 컨테이너 헬스체크 중..."
max_attempts=30
attempt=0

while [ $attempt -lt $max_attempts ]; do
    if docker exec hospital-backend-${ROLLBACK_CONTAINER} curl -f -s http://localhost:8888/actuator/health > /dev/null 2>&1; then
        echo -e "${GREEN}✅ ${ROLLBACK_CONTAINER} 컨테이너 정상 구동 확인${NC}"
        break
    fi
    
    attempt=$((attempt + 1))
    echo "대기 중... ($attempt/$max_attempts)"
    sleep 10
done

if [ $attempt -eq $max_attempts ]; then
    echo -e "${RED}❌ ${ROLLBACK_CONTAINER} 컨테이너 시작 실패. 롤백을 중단합니다.${NC}"
    docker-compose -f $COMPOSE_FILE stop backend-${ROLLBACK_CONTAINER}
    exit 1
fi

# Nginx 트래픽 전환
echo "🔀 트래픽을 ${ROLLBACK_CONTAINER}로 전환 중..."
update_nginx_config "$ROLLBACK_CONTAINER" "$ACTIVE_CONTAINER"

# 연결 드레이닝 대기
echo "⏳ 기존 연결 종료 대기 (30초)..."
sleep 30

# 문제가 있던 컨테이너 중지
echo "⏹️ ${ACTIVE_CONTAINER} 컨테이너 중지..."
CONTAINER_NAME="hospital-backend-${ACTIVE_CONTAINER}"

# 컨테이너가 실행 중인지 확인
if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "단계 1: Graceful shutdown 시도 (30초 대기)..."
    docker stop -t 30 ${CONTAINER_NAME} 2>&1
    
    # 3초 대기
    sleep 3
    
    # 여전히 실행 중인지 확인
    if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
        echo -e "${YELLOW}⚠️ 여전히 실행 중입니다. 즉시 종료 시도...${NC}"
        docker kill ${CONTAINER_NAME} 2>&1
        sleep 2
    fi
    
    echo -e "${GREEN}✅ ${CONTAINER_NAME} 중지 완료${NC}"
else
    echo "ℹ️  ${CONTAINER_NAME}는 이미 중지 상태입니다."
fi

# 최종 상태 확인
RUNNING_COUNT=$(docker ps --format '{{.Names}}' | grep -c "^hospital-backend" || echo "0")
echo ""
echo "=========================================="
if [ "$RUNNING_COUNT" -eq 1 ]; then
    echo -e "${GREEN}✅ 백엔드 컨테이너 1개만 실행 중 (정상)${NC}"
    docker ps --format "table {{.Names}}\t{{.Status}}" | grep hospital-backend
elif [ "$RUNNING_COUNT" -gt 1 ]; then
    echo -e "${RED}⚠️ 경고: 백엔드 컨테이너가 ${RUNNING_COUNT}개 실행 중입니다!${NC}"
    docker ps --format "table {{.Names}}\t{{.Status}}" | grep hospital-backend
    echo ""
    echo -e "${YELLOW}💡 자동 정리 시도 중...${NC}"
    
    # ROLLBACK_CONTAINER가 아닌 모든 백엔드 컨테이너 강제 중지
    for container in $(docker ps --format '{{.Names}}' | grep "^hospital-backend"); do
        if [ "$container" != "hospital-backend-${ROLLBACK_CONTAINER}" ]; then
            echo "강제 중지: $container"
            docker kill $container 2>&1 || true
        fi
    done
    
    sleep 2
    RUNNING_COUNT=$(docker ps --format '{{.Names}}' | grep -c "^hospital-backend" || echo "0")
    
    if [ "$RUNNING_COUNT" -eq 1 ]; then
        echo -e "${GREEN}✅ 자동 정리 완료! 백엔드 컨테이너 1개만 실행 중${NC}"
    else
        echo -e "${RED}❌ 자동 정리 실패. 수동 확인 필요${NC}"
    fi
else
    echo -e "${RED}⚠️ 실행 중인 백엔드 컨테이너가 없습니다!${NC}"
fi
echo "=========================================="
echo ""

echo ""
echo "=========================================="
echo -e "${GREEN}✅ 롤백 완료!${NC}"
echo "=========================================="
echo -e "${BLUE}활성 컨테이너: ${ROLLBACK_CONTAINER}${NC}"
echo -e "${BLUE}중지된 컨테이너: ${ACTIVE_CONTAINER}${NC}"
echo ""
echo "💡 문제를 해결한 후 다시 배포하세요:"
echo "  ./deploy.sh"
echo "=========================================="
