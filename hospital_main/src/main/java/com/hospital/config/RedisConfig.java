package com.hospital.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.hospital.dto.HospitalWebResponse;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis 설정 클래스
 *
 * 지오해시 기반 병원 데이터 캐싱을 위한 Redis 설정
 */
@Slf4j
@Configuration
public class RedisConfig {

	@Value("${redis.host:localhost}")
	private String redisHost;

	@Value("${redis.port:6379}")
	private int redisPort;

	@Value("${redis.password:}")
	private String redisPassword;

	/**
	 * ClientResources Bean - Lettuce 연결 관리 리소스
	 * 명시적으로 생성하여 리소스 누수 방지
	 */
	@Bean(destroyMethod = "shutdown")
	public ClientResources clientResources() {
		return DefaultClientResources.builder()
			.ioThreadPoolSize(4)  // I/O 스레드 풀 크기
			.computationThreadPoolSize(4)  // 연산 스레드 풀 크기
			.build();
	}

	@Bean
	public RedisConnectionFactory redisConnectionFactory(ClientResources clientResources) {
		log.info("Redis 연결 설정 - host: {}, port: {}", redisHost, redisPort);

		RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
		config.setHostName(redisHost);
		config.setPort(redisPort);

		if (redisPassword != null && !redisPassword.isEmpty()) {
			config.setPassword(redisPassword);
		}

		// Connection Pool 설정 최적화
		GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
		poolConfig.setMaxTotal(50);  // 최대 연결 수
		poolConfig.setMaxIdle(20);   // 최대 유휴 연결
		poolConfig.setMinIdle(10);   // 최소 유휴 연결
		poolConfig.setMaxWait(Duration.ofMillis(100)); // 최대 대기 시간 단축
		poolConfig.setTestOnBorrow(false);  // 비활성화 (성능 향상)
		poolConfig.setTestOnReturn(false);
		poolConfig.setTestWhileIdle(false);  // 비활성화
		poolConfig.setBlockWhenExhausted(true);

		// Client 설정 최적화
		ClientOptions clientOptions = ClientOptions.builder()
			.autoReconnect(true)
			.pingBeforeActivateConnection(false)  // 연결 전 PING 비활성화
			.build();

		LettucePoolingClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
			.poolConfig(poolConfig)
			.clientOptions(clientOptions)
			.clientResources(clientResources)  // 명시적 ClientResources 주입
			.commandTimeout(Duration.ofSeconds(3))  // 타임아웃 증가
			.build();

		LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfig);
		factory.setShareNativeConnection(true);  // 네이티브 연결 공유
		factory.setValidateConnection(false);     // 연결 검증 비활성화 (성능 향상)
		factory.afterPropertiesSet();             // 초기화

		log.info("Redis ConnectionFactory 초기화 완료");
		return factory;
	}

	@Bean
	public RedisTemplate<String, Object> redisTemplate(
			RedisConnectionFactory connectionFactory) {

		log.info("RedisTemplate 설정 시작");

		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);

		// Key Serializer: String
		template.setKeySerializer(new StringRedisSerializer());
		template.setHashKeySerializer(new StringRedisSerializer());

		// Jackson2JsonRedisSerializer with TypeReference (타입 안전 + 빠름!)
		ObjectMapper mapper = new ObjectMapper();
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
		mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

		// JavaType 설정으로 List<HospitalWebResponse> 타입 보장
		TypeFactory typeFactory = mapper.getTypeFactory();
		JavaType javaType = typeFactory.constructParametricType(
			List.class,
			HospitalWebResponse.class
		);

		Jackson2JsonRedisSerializer<Object> jsonSerializer =
			new Jackson2JsonRedisSerializer<>(mapper, javaType);

		template.setValueSerializer(jsonSerializer);
		template.setHashValueSerializer(jsonSerializer);

		template.afterPropertiesSet();

		log.info("RedisTemplate 설정 완료");
		return template;
	}
}
