pipeline {
    agent any
    
    environment {
        // Docker 이미지 설정
        IMAGE_NAME = 'hospital-backend'
        IMAGE_TAG = "${BUILD_NUMBER}"

        // EC2 배포 환경
        EC2_USER = credentials('EC2_USER')
        
        // 데이터베이스 설정
        DB_ROOT_PASSWORD = credentials('DB_ROOT_PASSWORD')
        DB_PASSWORD = credentials('DB_PASSWORD')
        DB_URL = credentials('DB_URL')
        DB_USERNAME = credentials('DB_USERNAME')
        
        // Redis 설정
        REDIS_PASSWORD = credentials('REDIS_PASSWORD')
        
        // 모니터링 설정
        GRAFANA_ADMIN_PASSWORD = credentials('GRAFANA_ADMIN_PASSWORD')
        
        // API 키 및 설정
        HOSPITAL_MAIN_API_KEY = credentials('HOSPITAL_MAIN_API_KEY')
        HOSPITAL_DETAIL_API_KEY = credentials('HOSPITAL_DETAIL_API_KEY')
        HOSPITAL_MEDICAL_SUBJECT_API_KEY = credentials('HOSPITAL_MEDICAL_SUBJECT_API_KEY')
        HOSPITAL_PRODOC_API_KEY = credentials('HOSPITAL_PRODOC_API_KEY')
        HOSPITAL_PHARMACY_API_KEY = credentials('HOSPITAL_PHARMACY_API_KEY')
        HOSPITAL_EMERGENCY_API_KEY = credentials('HOSPITAL_EMERGENCY_API_KEY')
        API_ADMIN_KEY = credentials('API_ADMIN_KEY')
        
        HOSPITAL_MAIN_API_BASE_URL = credentials('HOSPITAL_MAIN_API_BASE_URL')
        HOSPITAL_DETAIL_API_BASE_URL = credentials('HOSPITAL_DETAIL_API_BASE_URL')
        HOSPITAL_MEDICAL_SUBJECT_API_BASE_URL = credentials('HOSPITAL_MEDICAL_SUBJECT_API_BASE_URL')
        HOSPITAL_PRODOC_API_BASE_URL = credentials('HOSPITAL_PRODOC_API_BASE_URL')
        HOSPITAL_PHARMACY_API_BASE_URL = credentials('HOSPITAL_PHARMACY_API_BASE_URL')
        HOSPITAL_EMERGENCY_API_BASE_URL = credentials('HOSPITAL_EMERGENCY_API_BASE_URL')
        HOSPITAL_EMERGENCY_LOCATION_API_BASE_URL = credentials('HOSPITAL_EMERGENCY_LOCATION_API_BASE_URL')

        YOUTUBE_API_KEY = credentials('YOUTUBE_API_KEY')
        YOUTUBE_API_BASE_URL = credentials('YOUTUBE_API_BASE_URL')
        YOUTUBE_API_TRUSTED_CHANNELS = credentials('YOUTUBE_API_TRUSTED_CHANNELS')
        
        GEMINI_API_KEY = credentials('GEMINI_API_KEY')
        GEMINI_API_URL = credentials('GEMINI_API_URL')
        GEMINI_API_MODEL = credentials('GEMINI_API_MODEL')
        
        CHATBOT_SYSTEM_PROMPT_FILE = credentials('CHATBOT_SYSTEM_PROMPT_FILE')

        DISEASE_STATS_API_KEY = credentials('DISEASE_STATS_API_KEY')
        DISEASE_STATS_API_BASE_URL = credentials('DISEASE_STATS_API_BASE_URL')
    }
    
    stages {
        stage('EC2 공인 IP 자동 감지') {
            steps {
                script {
                    def publicIp = sh(
                        script: 'curl -s --connect-timeout 5 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo ""',
                        returnStdout: true
                    ).trim()

                    if (publicIp && publicIp != "") {
                        env.EC2_HOST = publicIp
                        echo "✅ EC2 공인 IP 자동 감지: ${publicIp}"
                    } else {
                        env.EC2_HOST = "localhost"
                        echo "⚠️ EC2 메타데이터 접근 불가 - localhost 사용"
                    }
                }
            }
        }

        stage('소스코드 체크아웃') {
            steps {
                checkout scm
            }
        }
        
        stage('빌드용 Properties 파일 생성') {
            steps {
                script {
                    // api.properties
                    writeFile file: 'hospital_main/src/main/resources/api.properties', text: """
# Hospital API Keys
hospital.main.api.key=${HOSPITAL_MAIN_API_KEY}
hospital.detail.api.key=${HOSPITAL_DETAIL_API_KEY}
hospital.medicalSubject.api.key=${HOSPITAL_MEDICAL_SUBJECT_API_KEY}
hospital.proDoc.api.key=${HOSPITAL_PRODOC_API_KEY}
hospital.pharmacy.api.key=${HOSPITAL_PHARMACY_API_KEY}
hospital.emergency.api.serviceKey=${HOSPITAL_EMERGENCY_API_KEY}
api.admin.key=${API_ADMIN_KEY}

# Hospital API Base URLs
hospital.main.api.base-url=${HOSPITAL_MAIN_API_BASE_URL}
hospital.detail.api.base-url=${HOSPITAL_DETAIL_API_BASE_URL}
hospital.medicalSubject.api.base-url=${HOSPITAL_MEDICAL_SUBJECT_API_BASE_URL}
hospital.proDoc.api.base-url=${HOSPITAL_PRODOC_API_BASE_URL}
hospital.pharmacy.api.base-url=${HOSPITAL_PHARMACY_API_BASE_URL}
hospital.emergency.api.baseUrl=${HOSPITAL_EMERGENCY_API_BASE_URL}
hospital.emergencyLocation.api.baseUrl=${HOSPITAL_EMERGENCY_LOCATION_API_BASE_URL}

# YouTube API
youTube.api.key=${YOUTUBE_API_KEY}
youTube.api.base-url=${YOUTUBE_API_BASE_URL}
youTube.api.trusted-channels=${YOUTUBE_API_TRUSTED_CHANNELS}

# Gemini API
gemini.api.key=${GEMINI_API_KEY}
gemini.api.url=${GEMINI_API_URL}
gemini.api.model=${GEMINI_API_MODEL}

# Chatbot
chatbot.system-prompt-file=${CHATBOT_SYSTEM_PROMPT_FILE}

# Disease Statistics API
diseasesStats.api.Key=${DISEASE_STATS_API_KEY}
diseasesStats.api.base-url=${DISEASE_STATS_API_BASE_URL}
"""

                    // db.properties
                    writeFile file: 'hospital_main/src/main/resources/db.properties', text: """jdbc.driverClassName=org.mariadb.jdbc.Driver
jdbc.url=${DB_URL}
jdbc.username=${DB_USERNAME}
jdbc.password=${DB_PASSWORD}
"""

                    // redis.properties (환경변수 형식 - 백슬래시로 $ 이스케이프)
                    writeFile file: 'hospital_main/src/main/resources/redis.properties', text: '''# Redis Configuration
# Docker Compose
redis.host=${REDIS_HOST:localhost}
redis.port=${REDIS_PORT:6379}
redis.password=${REDIS_PASSWORD:}
# Cache TTL (hours)
redis.cache.ttl.hours=1
'''
                }
            }
        }
        
        stage('백엔드 빌드 및 압축') {
            steps {
                script {
                    dir('hospital_main') {
                        sh "docker build --no-cache -t ${IMAGE_NAME}:${IMAGE_TAG} ."
                        sh "docker tag ${IMAGE_NAME}:${IMAGE_TAG} ${IMAGE_NAME}:latest"
                    }
                    sh "docker save ${IMAGE_NAME}:latest | gzip > backend.tar.gz"
                }
            }
        }
        
        stage('배포용 설정 파일 생성') {
            steps {
                script {
                    // .env 파일
                    writeFile file: 'env.prod', text: """ENVIRONMENT=production
IMAGE_TAG=latest

DB_ROOT_PASSWORD=${DB_ROOT_PASSWORD}
DB_PASSWORD=${DB_PASSWORD}
DB_PORT=3500

REDIS_HOST=hospital-redis
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PASSWORD}

BACKEND_HOST=hospital-backend
BACKEND_PORT=8888

PROMETHEUS_PORT=9090
GRAFANA_PORT=3000
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD}

HOSPITAL_MAIN_API_KEY=${HOSPITAL_MAIN_API_KEY}
HOSPITAL_DETAIL_API_KEY=${HOSPITAL_DETAIL_API_KEY}
HOSPITAL_MEDICAL_SUBJECT_API_KEY=${HOSPITAL_MEDICAL_SUBJECT_API_KEY}
HOSPITAL_PRODOC_API_KEY=${HOSPITAL_PRODOC_API_KEY}
HOSPITAL_PHARMACY_API_KEY=${HOSPITAL_PHARMACY_API_KEY}
HOSPITAL_EMERGENCY_API_KEY=${HOSPITAL_EMERGENCY_API_KEY}
API_ADMIN_KEY=${API_ADMIN_KEY}

HOSPITAL_MAIN_API_BASE_URL=${HOSPITAL_MAIN_API_BASE_URL}
HOSPITAL_DETAIL_API_BASE_URL=${HOSPITAL_DETAIL_API_BASE_URL}
HOSPITAL_MEDICAL_SUBJECT_API_BASE_URL=${HOSPITAL_MEDICAL_SUBJECT_API_BASE_URL}
HOSPITAL_PRODOC_API_BASE_URL=${HOSPITAL_PRODOC_API_BASE_URL}
HOSPITAL_PHARMACY_API_BASE_URL=${HOSPITAL_PHARMACY_API_BASE_URL}
HOSPITAL_EMERGENCY_API_BASE_URL=${HOSPITAL_EMERGENCY_API_BASE_URL}
HOSPITAL_EMERGENCY_LOCATION_API_BASE_URL=${HOSPITAL_EMERGENCY_LOCATION_API_BASE_URL}

DB_URL=${DB_URL}
DB_USERNAME=${DB_USERNAME}
DB_PASSWORD=${DB_PASSWORD}

YOUTUBE_API_KEY=${YOUTUBE_API_KEY}
YOUTUBE_API_BASE_URL=${YOUTUBE_API_BASE_URL}
YOUTUBE_API_TRUSTED_CHANNELS=${YOUTUBE_API_TRUSTED_CHANNELS}

GEMINI_API_KEY=${GEMINI_API_KEY}
GEMINI_API_URL=${GEMINI_API_URL}
GEMINI_API_MODEL=${GEMINI_API_MODEL}

CHATBOT_SYSTEM_PROMPT_FILE=${CHATBOT_SYSTEM_PROMPT_FILE}

DISEASE_STATS_API_KEY=${DISEASE_STATS_API_KEY}
DISEASE_STATS_API_BASE_URL=${DISEASE_STATS_API_BASE_URL}
"""

                    // Prometheus 설정
                    writeFile file: 'prometheus.yml', text: """global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'prometheus'
    static_configs:
      - targets: ['localhost:9090']

  - job_name: 'hospital-backend'
    static_configs:
      - targets: ['hospital-backend-blue:8888', 'hospital-backend-green:8888']
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
"""

                    // Nginx 설정
                    writeFile file: 'nginx.conf', text: """user nginx;
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
    types_hash_max_size 2048;

    # Gzip 압축 설정
    gzip on;
    gzip_vary on;
    gzip_proxied any;
    gzip_comp_level 6;
    gzip_types text/plain text/css text/xml text/javascript 
               application/json application/javascript application/xml+rss 
               application/rss+xml font/truetype font/opentype 
               application/vnd.ms-fontobject image/svg+xml;

    # Docker의 내부 DNS resolver 사용 (컨테이너 동적 탐지)
    resolver 127.0.0.11 valid=10s;
    resolver_timeout 5s;

    server {
        listen 80;
        server_name _;

        # 클라이언트 요청 크기 제한
        client_max_body_size 50M;

        # 프록시 타임아웃 설정
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;

        # 헬스체크 엔드포인트 (Nginx 자체 상태)
        location /nginx-health {
            access_log off;
            return 200 "healthy\\n";
            add_header Content-Type text/plain;
        }

        # API 프록시 설정 (동적 upstream)
        location / {
            # 변수를 사용하여 동적 해석 활성화
            set \$backend "hospital-backend-blue:8888";
            
            # Blue 컨테이너 우선, 실패 시 Green으로 fallback
            proxy_pass http://\$backend;
            proxy_next_upstream error timeout http_502 http_503 http_504;
            proxy_http_version 1.1;
            
            # 헤더 설정
            proxy_set_header Host \$host;
            proxy_set_header X-Real-IP \$remote_addr;
            proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto \$scheme;
            proxy_set_header X-Forwarded-Host \$host;
            proxy_set_header X-Forwarded-Port \$server_port;

            # WebSocket 지원
            proxy_set_header Upgrade \$http_upgrade;
            proxy_set_header Connection "upgrade";

            # 버퍼링 설정
            proxy_buffering off;
            proxy_request_buffering off;
        }

        # 에러 페이지
        error_page 502 503 504 /50x.html;
        location = /50x.html {
            root /usr/share/nginx/html;
        }
    }
}
"""
                }
            }
        }
        
        stage('파일 패키징 및 전송') {
            steps {
                script {
                    sh "tar -czf deploy_pkg.tar.gz backend.tar.gz env.prod prometheus.yml nginx.conf deploy.sh rollback.sh docker-compose.prod.yml"
                    
                    sshagent(credentials: ['EC2_PRIVATE_KEY']) {
                        sh "scp -o StrictHostKeyChecking=no deploy_pkg.tar.gz ${EC2_USER}@${EC2_HOST}:/home/ec2-user/"
                    }
                }
            }
        }
        
        stage('EC2 무중단 배포 실행') {
            steps {
                script {
                    sshagent(credentials: ['EC2_PRIVATE_KEY']) {
                        sh '''
                            ssh -o StrictHostKeyChecking=no ${EC2_USER}@${EC2_HOST} << 'ENDSSH'

                            echo "🚀 무중단 배포 패키지 해제 중..."
                            tar -xzf deploy_pkg.tar.gz

                            # .env 파일 적용
                            mv env.prod .env

                            # 필요한 디렉토리 생성
                            sudo mkdir -p /opt/hospital/config/nginx
                            sudo mkdir -p /opt/hospital/config/prometheus
                            sudo mkdir -p /opt/hospital/data/mariadb
                            sudo mkdir -p /opt/hospital/data/redis
                            sudo mkdir -p /opt/hospital/logs/backend/blue
                            sudo mkdir -p /opt/hospital/logs/backend/green
                            sudo mkdir -p /opt/hospital/logs/nginx
                            sudo mkdir -p /opt/hospital/monitoring/prometheus/config
                            sudo mkdir -p /opt/hospital/monitoring/prometheus/data
                            sudo mkdir -p /opt/hospital/monitoring/grafana/data

                            # 설정 파일 이동
                            sudo mv prometheus.yml /opt/hospital/monitoring/prometheus/config/
                            sudo mv nginx.conf /opt/hospital/config/nginx/nginx.conf

                            # MariaDB 데이터 디렉토리 권한 설정 (중요!)
                            echo "🔐 MariaDB 디렉토리 권한 설정 중..."
                            sudo chown -R 999:999 /opt/hospital/data/mariadb
                            sudo chmod -R 755 /opt/hospital/data/mariadb

                            # 나머지 디렉토리 권한
                            sudo chown -R ec2-user:ec2-user /opt/hospital/data/redis
                            sudo chown -R ec2-user:ec2-user /opt/hospital/logs/
                            sudo chown -R ec2-user:ec2-user /opt/hospital/config/nginx
                            sudo chown -R ec2-user:ec2-user /opt/hospital/monitoring/

                            # 스크립트 실행 권한 부여
                            dos2unix deploy.sh rollback.sh 2>/dev/null || sed -i 's/\\r$//' deploy.sh rollback.sh
                            chmod +x deploy.sh rollback.sh

                            echo "📦 Docker 이미지 로드..."
                            docker load < backend.tar.gz

                            echo "▶️ 무중단 배포 스크립트 실행..."
                            ./deploy.sh

                            # 청소
                            rm -f deploy_pkg.tar.gz backend.tar.gz env.prod prometheus.yml nginx.conf
ENDSSH
                        '''
                    }
                }
            }
        }
        
        stage('헬스체크') {
            steps {
                script {
                    sshagent(credentials: ['EC2_PRIVATE_KEY']) {
                        sh """
                            ssh -o StrictHostKeyChecking=no ${EC2_USER}@${EC2_HOST} '
                                echo "🏥 헬스체크 시작..."
                                sleep 15
                                
                                # Nginx 헬스체크
                                curl -f -s --connect-timeout 5 http://${EC2_HOST}/nginx-health > /dev/null && echo "✅ Nginx 정상" || echo "⚠️ Nginx 확인 필요"
                                
                                # 백엔드 헬스체크 (Nginx를 통해)
                                curl -f -s --connect-timeout 5 http://${EC2_HOST}/actuator/health > /dev/null && echo "✅ 백엔드 정상 (Nginx 경유)" || echo "⚠️ 백엔드 확인 필요"
                                
                                # Redis 헬스체크
                                docker exec hospital-redis redis-cli --no-auth-warning -a "${REDIS_PASSWORD}" ping > /dev/null 2>&1 && echo "✅ Redis 정상" || echo "⚠️ Redis 확인 필요"
                                
                                # 백엔드 컨테이너 개수 확인
                                BACKEND_COUNT=\$(docker ps | grep -c hospital-backend || echo "0")
                                if [ "\$BACKEND_COUNT" -eq 1 ]; then
                                    echo "✅ 백엔드 컨테이너 1개만 실행 중 (정상)"
                                else
                                    echo "⚠️ 경고: 백엔드 컨테이너 \$BACKEND_COUNT 개 실행 중"
                                    docker ps | grep hospital-backend
                                fi
                            '
                        """
                    }
                }
            }
        }
    }
    
    post {
        success {
            echo '✅ 무중단 배포가 성공적으로 완료되었습니다!'
        }
        failure {
            echo '❌ 배포 중 오류가 발생했습니다. 롤백이 필요할 수 있습니다.'
            echo '💡 롤백 명령어: ./rollback.sh'
        }
        always {
            sh 'rm -f backend.tar.gz deploy_pkg.tar.gz prometheus.yml nginx.conf env.prod || true'
        }
    }
}
