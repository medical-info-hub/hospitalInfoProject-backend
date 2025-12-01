package com.hospital.dto;

import com.hospital.analyzer.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DiseaseStatsWebResponse {
    private String icdGroupName;      // 질병 그룹명
    private String icdName;            // 질병명
    private List<WeeklyData> weeklyData;  // 주간 데이터 목록
    private Integer totalCount;        // 전체 환자 수
    private RiskLevel riskLevel;       // 위험도 수준
    private Integer recentChange;      // 최근 증감 수
    private Double recentChangeRate;   // 최근 증감률
    
    
    // 주간 데이터 내부 클래스
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class WeeklyData {
        private String period;  // 기간 
        private Integer count;  // 해당 주의 환자 수
    }
}