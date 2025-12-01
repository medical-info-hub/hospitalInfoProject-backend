#!/bin/bash
# Hospital Project 배포 스크립트
set -e
echo "🚀 Hospital Project 백엔드 배포 시작..."

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
export BACKEND_PORT=${BACKEND_PORT:-8888}
export DB_PORT=${DB_PORT:-3500}
export PROMETHEUS_PORT=${PROMETHEUS_PORT:-9090}
export GRAFANA_PORT=${GRAFANA_PORT:-3000}

PUBLIC_IP=$(curl -s --connect-timeout 5 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "localhost")
export SERVER_IP=${SERVER_IP:-$PUBLIC_IP}

echo "⏹️ 기존 컨테이너 중지..."
docker-compose -f docker-compose.prod.yml down || true

# 디렉토리 생성
sudo mkdir -p /opt/hospital/data/mariadb
sudo mkdir -p /opt/hospital/logs/backend
sudo mkdir -p /opt/hospital/monitoring/prometheus/config
sudo mkdir -p /opt/hospital/monitoring/prometheus/data
sudo mkdir -p /opt/hospital/monitoring/grafana/data

# 📝 prometheus.yml 파일 동적 생성
echo "📝 prometheus.yml 설정 파일 생성 중..."
cat <<EOF | sudo tee /opt/hospital/monitoring/prometheus/config/prometheus.yml > /dev/null
global:
  scrape_interval: 15s
  evaluation_interval: 15s
  external_labels:
    monitor: 'hospital-monitor'

scrape_configs:
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  - job_name: 'hospital-backend'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 5s
    static_configs:
      - targets: ['hospital-backend:8888']
        labels:
          # [중요] 대시보드 12900 호환을 위한 레이블
          application: 'Hospital App' 

  - job_name: 'node-exporter'
    static_configs:
      - targets: ['node-exporter:9100']

  - job_name: 'cadvisor'
    static_configs:
      - targets: ['cadvisor:8080']
EOF

# 권한 설정
sudo chown -R ec2-user:ec2-user /opt/hospital/
sudo chmod 644 /opt/hospital/monitoring/prometheus/config/prometheus.yml

echo "▶️ 백엔드 및 DB 컨테이너 시작..."
docker-compose -f docker-compose.prod.yml up -d

echo "⏳ 컨테이너 시작 대기..."
sleep 15

docker-compose -f docker-compose.prod.yml ps
docker system prune -f

echo ""
echo "🎉 배포 완료!"
echo "📍 접속 정보:"
echo "  🔗 백엔드 API: http://${SERVER_IP}:${BACKEND_PORT}"
echo "  📊 프로메테우스: http://${SERVER_IP}:${PROMETHEUS_PORT}"
echo "  📈 그라파나: http://${SERVER_IP}:${GRAFANA_PORT}"
