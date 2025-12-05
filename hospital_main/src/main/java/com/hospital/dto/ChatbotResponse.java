package com.hospital.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Gemini AI 응답 파싱용 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotResponse {
    
    /**
     * 응답 타입: question, suggest, inappropriate, emergency
     */
    @JsonProperty("type")
    private String type;

    /**
     * AI 메시지
     */
    @JsonProperty("message")
    private String message;

    /**
     * UTC 타임스탬프 (ISO 8601 형식)
     */
    @JsonProperty("timestamp")
    private String timestamp;

    /**
     * 여러 진료과 목록(suggest 일 때)
     */
    @JsonProperty("departments")
    private List<String> departments;

    /**
     * 강조 메시지 (suggest 타입 전용)
     * 진료과 추천 부분을 강조하기 위한 메시지
     */
    @JsonProperty("highlightMessage")
    private String highlightMessage;

    /**
     * 응답 타입 확인 메서드
     */
    public boolean isQuestion() {
        return "question".equals(type);
    }
    public boolean isEmergency() {
    	return "emergency".equals(type);
    }

    public boolean isRecommendation() {
        return "suggest".equals(type);
    }

    public boolean isInappropriate() {  
        return "inappropriate".equals(type);
    }

}
