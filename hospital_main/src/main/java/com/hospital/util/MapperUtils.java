package com.hospital.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.Map;

import com.hospital.entity.HospitalDetail;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MapperUtils {

	private MapperUtils() {
		// Utility class - prevent instantiation
	}

	/**
	 * 문자열을 Integer로 변환
	 * @param value 변환할 문자열
	 * @return 변환된 Integer, 실패 시 null
	 */
	public static Integer parseInteger(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			log.warn("정수 변환 실패: {}", value);
			return null;
		}
	}

	/**
	 * 시간 문자열 포맷팅 (0900 → 09:00)
	 * @param time 4자리 시간 문자열
	 * @return 포맷팅된 시간 (HH:mm), 유효하지 않으면 빈 문자열
	 */
	public static String formatTime(String time) {
		if (HospitalDetail.isValidTime(time)) {
			return time.substring(0, 2) + ":" + time.substring(2, 4);
		}
		return "";
	}
	
	public static String createHospitalTodayOpen(ResultSet rs, DayOfWeek today) throws SQLException{
		return switch (today) {
		case MONDAY -> rs.getString("mon_open");
		case TUESDAY -> rs.getString("tues_open");
		case WEDNESDAY -> rs.getString("wed_open");
		case THURSDAY -> rs.getString("thurs_open");
		case FRIDAY -> rs.getString("fri_open");
		case SATURDAY -> rs.getString("trmt_sat_start");
		case SUNDAY -> rs.getString("trmt_sun_start");
		};
	}
	
	public static String createHospitalTodayClose(ResultSet rs, DayOfWeek today) throws SQLException{
		return switch (today) {
		case MONDAY -> rs.getString("mon_end");
		case TUESDAY -> rs.getString("tues_end");
		case WEDNESDAY -> rs.getString("wed_end");
		case THURSDAY -> rs.getString("thurs_end");
		case FRIDAY -> rs.getString("fri_end");
		case SATURDAY -> rs.getString("trmt_sat_end");
		case SUNDAY -> rs.getString("trmt_sun_end");
		};
	}
	
	public static String createPharmacyTodayOpen(ResultSet rs, DayOfWeek today) throws SQLException{
		return switch (today) {
		case MONDAY -> rs.getString("mon_open");
		case TUESDAY -> rs.getString("tue_open");
		case WEDNESDAY -> rs.getString("wed_open");
		case THURSDAY -> rs.getString("thu_open");
		case FRIDAY -> rs.getString("fri_open");
		case SATURDAY -> rs.getString("sat_open");
		case SUNDAY -> rs.getString("sun_open");
		};
	}
	
	public static String createPharmacyTodayClose(ResultSet rs, DayOfWeek today) throws SQLException{
		return switch (today) {
		case MONDAY -> rs.getString("mon_close");
		case TUESDAY -> rs.getString("tue_close");
		case WEDNESDAY -> rs.getString("wed_close");
		case THURSDAY -> rs.getString("thu_close");
		case FRIDAY -> rs.getString("fri_close");
		case SATURDAY -> rs.getString("sat_close");
		case SUNDAY -> rs.getString("sun_close");
		};
	}

	/**
	 * 주간 스케줄 생성 (HospitalDetail 기반)
	 * @param detail 병원 상세 정보
	 * @return 요일별 운영 시간 맵
	 */
	public static Map<String, Map<String, String>> createWeeklySchedule(HospitalDetail detail) {
		Map<String, Map<String, String>> schedule = new LinkedHashMap<>();

		schedule.put("월요일", createDaySchedule(
			detail != null ? detail.getTrmtMonStart() : null,
			detail != null ? detail.getTrmtMonEnd() : null
		));
		schedule.put("화요일", createDaySchedule(
			detail != null ? detail.getTrmtTueStart() : null,
			detail != null ? detail.getTrmtTueEnd() : null
		));
		schedule.put("수요일", createDaySchedule(
			detail != null ? detail.getTrmtWedStart() : null,
			detail != null ? detail.getTrmtWedEnd() : null
		));
		schedule.put("목요일", createDaySchedule(
			detail != null ? detail.getTrmtThurStart() : null,
			detail != null ? detail.getTrmtThurEnd() : null
		));
		schedule.put("금요일", createDaySchedule(
			detail != null ? detail.getTrmtFriStart() : null,
			detail != null ? detail.getTrmtFriEnd() : null
		));
		schedule.put("토요일", createDaySchedule(
			detail != null ? detail.getTrmtSatStart() : null,
			detail != null ? detail.getTrmtSatEnd() : null
		));
		schedule.put("일요일", createDaySchedule(
			detail != null ? detail.getTrmtSunStart() : null,
			detail != null ? detail.getTrmtSunEnd() : null
		));

		return schedule;
	}

	/**
	 * 주간 스케줄 생성 (ResultSet 기반 - RowMapper용)
	 * @param monOpen 월요일 시작 시간
	 * @param monEnd 월요일 종료 시간
	 * @param tuesOpen 화요일 시작 시간
	 * @param tuesEnd 화요일 종료 시간
	 * @param wedOpen 수요일 시작 시간
	 * @param wedEnd 수요일 종료 시간
	 * @param thursOpen 목요일 시작 시간
	 * @param thursEnd 목요일 종료 시간
	 * @param friOpen 금요일 시작 시간
	 * @param friEnd 금요일 종료 시간
	 * @param satStart 토요일 시작 시간
	 * @param satEnd 토요일 종료 시간
	 * @param sunStart 일요일 시작 시간
	 * @param sunEnd 일요일 종료 시간
	 * @return 요일별 운영 시간 맵
	 */
	public static Map<String, Map<String, String>> createHospitalWeeklySchedule(
			String monOpen, String monEnd,
			String tuesOpen, String tuesEnd,
			String wedOpen, String wedEnd,
			String thursOpen, String thursEnd,
			String friOpen, String friEnd,
			String satStart, String satEnd,
			String sunStart, String sunEnd) {

		Map<String, Map<String, String>> schedule = new LinkedHashMap<>();
		schedule.put("월요일", createDaySchedule(monOpen, monEnd));
		schedule.put("화요일", createDaySchedule(tuesOpen, tuesEnd));
		schedule.put("수요일", createDaySchedule(wedOpen, wedEnd));
		schedule.put("목요일", createDaySchedule(thursOpen, thursEnd));
		schedule.put("금요일", createDaySchedule(friOpen, friEnd));
		schedule.put("토요일", createDaySchedule(satStart, satEnd));
		schedule.put("일요일", createDaySchedule(sunStart, sunEnd));
		return schedule;
	}
	// 약국용 메서드 오버로딩
	 public static Map<String, Map<String, String>> createPharmacyWeeklySchedule(                                                                                               
	          String monOpen, String monEnd,
	          String tuesOpen, String tuesEnd,                                                                                                                           
	          String wedOpen, String wedEnd,                                                                                                                           
	          String thursOpen, String thursEnd,
	          String friOpen, String friEnd,
	          String satStart, String satEnd,
	          String sunStart, String sunEnd,
	          String holidayOpen, String holidayEnd) {

	      Map<String, Map<String, String>> schedule = new LinkedHashMap<>();
	      schedule.put("월요일", createDaySchedule(monOpen, monEnd));
	      schedule.put("화요일", createDaySchedule(tuesOpen, tuesEnd));
	      schedule.put("수요일", createDaySchedule(wedOpen, wedEnd));
	      schedule.put("목요일", createDaySchedule(thursOpen, thursEnd));
	      schedule.put("금요일", createDaySchedule(friOpen, friEnd));
	      schedule.put("토요일", createDaySchedule(satStart, satEnd));
	      schedule.put("일요일", createDaySchedule(sunStart, sunEnd));
	      schedule.put("공휴일", createDaySchedule(holidayOpen, holidayEnd));
	      return schedule;
	  }

	/**
	 * 일별 스케줄 생성
	 * @param startTime 시작 시간
	 * @param endTime 종료 시간
	 * @return open/close 키를 가진 맵
	 */
	public static Map<String, String> createDaySchedule(String startTime, String endTime) {
		Map<String, String> daySchedule = new LinkedHashMap<>();
		daySchedule.put("open", formatTime(startTime));
		daySchedule.put("close", formatTime(endTime));
		return daySchedule;
	}
}
