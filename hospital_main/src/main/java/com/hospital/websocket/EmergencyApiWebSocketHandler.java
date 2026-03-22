package com.hospital.websocket;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.hospital.event.EmergencyDataUpdateEvent;
import com.hospital.service.EmergencyLiveService;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EmergencyApiWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());
    private final EmergencyLiveService emergencyLiveService;

    public EmergencyApiWebSocketHandler(EmergencyLiveService emergencyLiveService) {
        this.emergencyLiveService = emergencyLiveService;
    }

    @EventListener
    public void handleEmergencyDataUpdate(EmergencyDataUpdateEvent event) {
        broadcastEmergencyRoomData(event.getJsonData());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("WebSocket 연결됨: {}, 총 연결수: {}", session.getId(), sessions.size());

        boolean isFirstConnection = (sessions.size() == 1);

        if (isFirstConnection) {
            emergencyLiveService.onWebSocketConnected();
            log.info("첫 연결 - 스케줄러 시작 및 데이터 수집 대기 중: {}", session.getId());
        } else {
            try {
                JsonNode initialData = emergencyLiveService.getEmergencyRoomData();
                if (initialData != null && initialData.size() > 0) {
                    session.sendMessage(new TextMessage(initialData.toString()));
                    log.info("초기 데이터 전송 완료 (캐시): {}", session.getId());
                } else {
                    log.info("추가 연결 - 스케줄러 데이터 대기 중: {}", session.getId());
                }
            } catch (Exception e) {
                log.error("초기 데이터 전송 실패: {}", session.getId(), e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket 연결 해제: {}, 총 연결수: {}", session.getId(), sessions.size());

        if (getConnectedSessionCount() == 0) {
            emergencyLiveService.onWebSocketDisconnected();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 에러: {}", session.getId(), exception);
        sessions.remove(session);

        if (getConnectedSessionCount() == 0) {
            emergencyLiveService.onWebSocketDisconnected();
        }
    }

    

    /**
     * 모든 연결된 클라이언트에게 데이터 브로드캐스트
     */
    public void broadcastEmergencyRoomData(String data) {
        if (data == null || sessions.isEmpty()) {
            return;
        }

        synchronized (sessions) {
            // 닫힌 세션 제거
            sessions.removeIf(session -> !session.isOpen());

            int successCount = 0;
            for (WebSocketSession session : new HashSet<>(sessions)) {
                try {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(data));
                        successCount++;
                    }
                } catch (IOException e) {
                    log.warn("메시지 전송 실패: {}", session.getId());
                    sessions.remove(session);
                }
            }
            log.debug("브로드캐스트 완료. 성공한 세션 수: {}/{}", successCount, sessions.size());
        }
    }

    /**
     * 모든 WebSocket 연결 강제 종료
     */
    public void closeAllSessions() {
        synchronized (sessions) {
            for (WebSocketSession session : new HashSet<>(sessions)) {
                try {
                    if (session.isOpen()) {
                        session.close(CloseStatus.NORMAL);
                    }
                } catch (IOException e) {
                    log.warn("WebSocket 세션 종료 실패: {}", session.getId());
                }
            }
            sessions.clear();
        }
        log.info("모든 WebSocket 연결 종료 완료");
    }

    /**
     * 현재 연결된 세션 수 조회 (유효하지 않은 세션 정리 포함)
     */
    public int getConnectedSessionCount() {
        synchronized (sessions) {
            // 유효하지 않은 세션 정리
            sessions.removeIf(session -> !session.isOpen());
            return sessions.size();
        }
    }
    
    /**
     * 현재 연결 상태 정보 반환
     */
    public String getConnectionStatus() {
        int validSessions = getConnectedSessionCount();
        return String.format("총 세션: %d, 유효 세션: %d", sessions.size(), validSessions);
    }
}