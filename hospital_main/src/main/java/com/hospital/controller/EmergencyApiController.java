package com.hospital.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.dto.EmergencyWebResponse;
import com.hospital.service.EmergencyLocationApiService;
import com.hospital.service.EmergencyLiveService;
import com.hospital.websocket.EmergencyApiWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/emergency")
public class EmergencyApiController {

	@Value("${api.admin.key}")
	private String adminApiKey;

	private final EmergencyLiveService emergencyLiveService;
	private final EmergencyApiWebSocketHandler emergencyApiWebSocketHandler;
	private final EmergencyLocationApiService emergencyLocationApiService;

	public EmergencyApiController(EmergencyLiveService emergencyLiveService,
	                               EmergencyApiWebSocketHandler emergencyApiWebSocketHandler,
	                               EmergencyLocationApiService emergencyLocationApiService) {
		this.emergencyLiveService = emergencyLiveService;
		this.emergencyApiWebSocketHandler = emergencyApiWebSocketHandler;
		this.emergencyLocationApiService = emergencyLocationApiService;
	}
	
	private boolean isValidApiKey(String apiKey) {
		if (apiKey == null || apiKey.trim().isEmpty()) {
			return false;
		}
		return adminApiKey.equals(apiKey);
	}

	private ResponseEntity<Map<String, Object>> unauthorizedResponse() {
		Map<String, Object> response = new HashMap<>();
		response.put("success", false);
		response.put("error", "UNAUTHORIZED");
		response.put("message", "유효하지 않은 API 키입니다");
		response.put("timestamp", LocalDateTime.now());

		log.warn("API 키 인증 실패");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
	}

	@GetMapping(value = "/start", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> getEmergencyList() {
	    log.info("응급실 정보 즉시 수집 시작...");

	    // EmergencyApiService를 통해 배치 매핑 적용
	    List<EmergencyWebResponse> emergencyData = emergencyLiveService.fetchAndMapEmergencyData();

	    Map<String, Object> response = new HashMap<>();
	    response.put("success", true);
	    response.put("message", "응급실 정보 수집 완료");
	    response.put("connectedWebSocketSessions", emergencyApiWebSocketHandler.getConnectedSessionCount());
	    response.put("data", emergencyData);
	    response.put("count", emergencyData.size());
	    response.put("timestamp", LocalDateTime.now());

	    log.info("응급실 정보 수집 완료 ({}건)", emergencyData.size());

	    return ResponseEntity.ok(response);
	}

	@GetMapping("/stop")
	public ResponseEntity<Map<String, Object>> shutdownCompleteService() {
	    log.info("응급실 서비스 강제 종료 요청...");

	    // 모든 WebSocket 세션 강제 종료 (이로 인해 자동으로 스케줄러도 중지됨)
	    emergencyApiWebSocketHandler.closeAllSessions();

	    // 혹시 남아있을 스케줄러 강제 중지
	    emergencyLiveService.stopScheduler();

	    Map<String, Object> response = new HashMap<>();
	    response.put("success", true);
	    response.put("message", "응급실 서비스 강제 종료 완료 - 모든 WebSocket 연결 해제 및 스케줄러 중지");
	    response.put("warning", "이 API는 관리자용입니다. 정상적인 종료는 WebSocket 연결 해제로 자동 처리됩니다.");
	    response.put("timestamp", LocalDateTime.now());

	    log.info("응급실 서비스 강제 종료 완료");
	    return ResponseEntity.ok(response);
	}

	@GetMapping("/status")
	public ResponseEntity<Map<String, Object>> getServiceStatus() {
	    log.info("응급실 서비스 상태 조회...");

	    boolean schedulerRunning = emergencyLiveService.isSchedulerRunning();
	    int connectedSessions = emergencyApiWebSocketHandler.getConnectedSessionCount();
	    Map<String, Object> stats = emergencyLiveService.getStats();

	    Map<String, Object> response = new HashMap<>();
	    response.put("success", true);
	    response.put("schedulerRunning", schedulerRunning);
	    response.put("connectedWebSocketSessions", connectedSessions);
	    response.put("connectionStatus", emergencyApiWebSocketHandler.getConnectionStatus());
	    response.put("collectionStats", stats);
	    response.put("lastDataCount", emergencyLiveService.getEmergencyRoomData().size());
	    response.put("timestamp", LocalDateTime.now());

	    if (schedulerRunning && connectedSessions > 0) {
	        response.put("status", "ACTIVE");
	        response.put("message", "정상 운영 중 - 실시간 데이터 수집 및 브로드캐스트");
	    } else if (connectedSessions > 0) {
	        response.put("status", "STARTING");
	        response.put("message", "WebSocket 연결 있음 - 스케줄러 시작 중");
	    } else {
	        response.put("status", "IDLE");
	        response.put("message", "대기 중 - WebSocket 연결 없음");
	    }

	    log.info("응급실 서비스 상태: {}, 세션: {}, 스케줄러: {}",
	        response.get("status"), connectedSessions, schedulerRunning);

	    return ResponseEntity.ok(response);
	}

	@GetMapping("/manual-start")
	public ResponseEntity<String> manualStart() {
		emergencyLiveService.onWebSocketConnected();
	    return ResponseEntity.ok("수동으로 스케줄러 시작됨");
	}

	/**
	 * 응급실 코드 데이터 수집 및 DB 저장
	 */
	@PostMapping(value = "/location/save", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> saveEmergenyLocations(
			@RequestHeader(value = "X-API-Key", required = false) String apiKey) {

		// API 키 검증
		if (!isValidApiKey(apiKey)) {
			return unauthorizedResponse();
		}

		log.info("응급실 코드 데이터 수집 요청...");

		try {
			emergencyLocationApiService.saveEmergencyLocations();

			Map<String, Object> response = new HashMap<>();
			response.put("success", true);
			response.put("message", "응급실 코드 데이터 저장 완료");
			response.put("timestamp", LocalDateTime.now());

			log.info("응급실 코드 데이터 저장 완료");
			return ResponseEntity.ok(response);

		} catch (Exception e) {
			log.error("응급실 코드 데이터 저장 실패: {}", e.getMessage(), e);

			Map<String, Object> response = new HashMap<>();
			response.put("success", false);
			response.put("error", "SAVE_FAILED");
			response.put("message", "응급실 코드 데이터 저장 실패: " + e.getMessage());
			response.put("timestamp", LocalDateTime.now());

			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
		}
	}

}
