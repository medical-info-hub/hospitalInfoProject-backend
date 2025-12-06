#!/bin/bash
# Hospital Project 무중단 배포 스크립트 (Blue-Green Deployment)
set -e

echo "🚀 Hospital Project 무중단 배포 시작..."

# .env 파일 로드
if [ -f ".env" ]; then
    echo "📄 .env 파일 로드 중..."
    set -a
    source .env
    set +a
    echo "✅ .env 파일 로드 완료"
else
    echo "⚠️ .env 파일을 찾을 수 없습니다."
    exit 1
fi

export IMAGE_TAG=${IMAGE_TAG:-latest}
export COMPOSE_FILE="docker-compose.prod.yml"

# 색상 정의
GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

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

# 대상 컨테이너 결정 (현재와 반대)
function get_target_container() {
    local active=$1
    if [ "$active" = "blue" ]; then
        echo "green"
    elif [ "$active" = "green" ]; then
        echo "blue"
    else
        echo "blue"  # 최초 배포는 blue로
    fi
}

# 헬스체크 함수
function wait_for_health() {
    local container=$1
    local max_attempts=30
    local attempt=0
    
    echo "⏳ ${container} 컨테이너 헬스체크 중..."
    
    while [ $attempt -lt $max_attempts ]; do
        if docker exec hospital-backend-${container} curl -f -s http://localhost:8888/actuator/health > /dev/null 2>&1; then
            echo -e "${GREEN}✅ ${container} 컨테이너 정상 구동 확인${NC}"
            return 0
        fi
        
        attempt=$((attempt + 1))
        echo "대기 중... ($attempt/$max_attempts)"
        sleep 10
    done
    
    echo -e "${RED}❌ ${container} 컨테이너 헬스체크 실패${NC}"
    return 1
}

# Nginx 설정 업데이트 함수
function update_nginx_config() {
    local primary=$1
    local backup=$2
    
    echo "📝 Nginx 설정 업데이트 중... (Primary: ${primary}, Backup: ${backup})"
    
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

    upstream backend {
        server hospital-backend-${primary}:8888 max_fails=3 fail_timeout=30s;
        server hospital-backend-${backup}:8888 max_fails=3 fail_timeout=30s backup;
    }

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
            proxy_pass http://backend;
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

    # Nginx 설정 리로드
    if docker exec hospital-nginx nginx -t > /dev/null 2>&1; then
        docker exec hospital-nginx nginx -s reload
        echo -e "${GREEN}✅ Nginx 설정 리로드 완료${NC}"
    else
        echo -e "${RED}❌ Nginx 설정 오류${NC}"
        return 1
    fi
}

# 메인 배포 로직
echo "=========================================="
echo "        무중단 배포 시작"
echo "=========================================="

# 현재 상태 확인
ACTIVE_CONTAINER=$(get_active_container)
TARGET_CONTAINER=$(get_target_container $ACTIVE_CONTAINER)

echo -e "${BLUE}현재 활성 컨테이너: ${ACTIVE_CONTAINER}${NC}"
echo -e "${GREEN}배포 대상 컨테이너: ${TARGET_CONTAINER}${NC}"

# 필요한 디렉토리 생성
echo "📁 디렉토리 구조 생성 중..."
sudo mkdir -p /opt/hospital/data/mariadb
sudo mkdir -p /opt/hospital/logs/backend/blue
sudo mkdir -p /opt/hospital/logs/backend/green
sudo mkdir -p /opt/hospital/logs/nginx
sudo mkdir -p /opt/hospital/config/nginx
sudo chown -R ec2-user:ec2-user /opt/hospital/

# Nginx 설정 파일이 없으면 생성
if [ ! -f "/opt/hospital/config/nginx/nginx.conf" ]; then
    echo "📝 초기 Nginx 설정 생성..."
    update_nginx_config "blue" "green"
fi

# 최초 배포인 경우
if [ "$ACTIVE_CONTAINER" = "none" ]; then
    echo -e "${YELLOW}🎬 최초 배포를 시작합니다...${NC}"
    
    # MariaDB와 Nginx, Blue 컨테이너 시작
    docker-compose -f $COMPOSE_FILE up -d mariadb nginx backend-blue
    
    # Blue 컨테이너 헬스체크
    if wait_for_health "blue"; then
        echo -e "${GREEN}🎉 최초 배포 완료!${NC}"
        exit 0
    else
        echo -e "${RED}❌ 최초 배포 실패${NC}"
        exit 1
    fi
fi

# 새 버전 배포 시작
echo -e "${BLUE}🔄 새 버전을 ${TARGET_CONTAINER} 컨테이너에 배포합니다...${NC}"

# 대상 컨테이너 시작
echo "▶️ ${TARGET_CONTAINER} 컨테이너 시작..."
if [ "$TARGET_CONTAINER" = "green" ]; then
    docker-compose -f $COMPOSE_FILE up -d backend-green
else
    docker-compose -f $COMPOSE_FILE up -d backend-blue
fi

# 새 컨테이너 헬스체크
if ! wait_for_health "$TARGET_CONTAINER"; then
    echo -e "${RED}❌ 새 컨테이너 시작 실패. 배포를 중단합니다.${NC}"
    docker-compose -f $COMPOSE_FILE stop backend-${TARGET_CONTAINER}
    exit 1
fi

# Nginx 트래픽 전환
echo "🔀 트래픽을 ${TARGET_CONTAINER}로 전환 중..."
update_nginx_config "$TARGET_CONTAINER" "$ACTIVE_CONTAINER"

# 연결 드레이닝 대기 (기존 요청 처리 완료 대기)
echo "⏳ 기존 연결 종료 대기 (30초)..."
sleep 30

# 이전 컨테이너 중지
echo "⏹️ 이전 컨테이너(${ACTIVE_CONTAINER}) 중지..."
docker-compose -f $COMPOSE_FILE stop backend-${ACTIVE_CONTAINER}

# 시스템 정리
echo "🧹 사용하지 않는 Docker 리소스 정리..."
docker system prune -f

# 배포 완료 정보 출력
PUBLIC_IP=$(curl -s --connect-timeout 5 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "localhost")

echo ""
echo "=========================================="
echo -e "${GREEN}🎉 무중단 배포 완료!${NC}"
echo "=========================================="
echo -e "${BLUE}활성 컨테이너: ${TARGET_CONTAINER}${NC}"
echo -e "${BLUE}대기 컨테이너: ${ACTIVE_CONTAINER} (중지됨)${NC}"
echo ""
echo "📍 접속 정보:"
echo "  🔗 API (Nginx): http://${PUBLIC_IP}"
echo "  🔗 Backend (직접): http://${PUBLIC_IP}:8888"
echo ""
echo "💡 롤백이 필요한 경우:"
echo "  docker-compose -f $COMPOSE_FILE start backend-${ACTIVE_CONTAINER}"
echo "  그 후 Nginx 설정을 ${ACTIVE_CONTAINER}로 변경하세요."
echo "=========================================="
