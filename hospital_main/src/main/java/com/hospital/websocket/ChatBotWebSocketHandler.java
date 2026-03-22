package com.hospital.websocket;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.dto.ChatbotResponse;
import com.hospital.service.ChatbotService;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
public class ChatBotWebSocketHandler extends TextWebSocketHandler {

    private static final int MAX_HISTORY_SIZE = 10;

    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, LinkedList<String>> conversationHistories = new ConcurrentHashMap<>();
    private final ChatbotService chatbotService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatBotWebSocketHandler(ChatbotService chatbotService, ObjectMapper objectMapper) {
        this.chatbotService = chatbotService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        conversationHistories.put(session.getId(), new LinkedList<>());
        log.info("ChatBot WebSocket 연결됨: {}, 총 연결수: {}", session.getId(), sessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.debug("[수신] Raw Payload: {}", message.getPayload());

        JsonNode node = objectMapper.readTree(message.getPayload());

        if (node.has("type") && "chat".equals(node.get("type").asText()) && node.has("message")) {
            String userMessage = node.get("message").asText();
            String sessionId = session.getId();

            log.info("[사용자 메시지] sessionId={}: {}", sessionId, userMessage);

            String conversationHistory = getConversationHistory(sessionId);
            ChatbotResponse response = chatbotService.chatWithHistory(userMessage, conversationHistory);

            log.info("[AI 응답] type={}", response.getType());

            addToHistory(sessionId, userMessage, response);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } else {
            log.warn("[알 수 없는 메시지 형식]: {}", message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        sessions.remove(session);
        conversationHistories.remove(session.getId());
        log.info("ChatBot WebSocket 연결 종료: {}", session.getId());
    }

    /**
     * 대화 이력 조회
     */
    private String getConversationHistory(String sessionId) {
        LinkedList<String> history = conversationHistories.get(sessionId);
        if (history == null || history.isEmpty()) {
            return "";
        }
        return String.join("\n", history);
    }

    /**
     * 대화 이력에 추가 (최대 개수 제한)
     */
    private void addToHistory(String sessionId, String userMessage, ChatbotResponse response) {
        LinkedList<String> history = conversationHistories.get(sessionId);
        if (history == null) {
            history = new LinkedList<>();
            conversationHistories.put(sessionId, history);
        }

        String timestamp = response.getTimestamp();

        // 사용자 메시지 추가 (타임스탬프 포함)
        history.add("[" + timestamp + "] 사용자: " + userMessage);

        // AI 응답 추가 (타임스탬프 포함)
        history.add("[" + timestamp + "] AI: " + response.getMessage());

        // 최대 개수 초과 시 오래된 대화 삭제 (FIFO)
        while (history.size() > MAX_HISTORY_SIZE * 2) { // 사용자+AI 쌍이므로 *2
            history.removeFirst();
        }
    }
}
