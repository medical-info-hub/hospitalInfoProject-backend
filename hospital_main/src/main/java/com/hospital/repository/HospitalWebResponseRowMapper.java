package com.hospital.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import org.springframework.jdbc.core.RowMapper;

import com.hospital.dto.HospitalWebResponse;
import com.hospital.util.HospitalMapperUtils;

/**
 * 웹 API 응답용 RowMapper
 * hospital_main + hospital_detail JOIN 결과를 HospitalWebResponse로 변환
 * 프레젠테이션 레이어를 위한 데이터 가공 포함
 */
public class HospitalWebResponseRowMapper implements RowMapper<HospitalWebResponse> {

	@Override
	public HospitalWebResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
		return HospitalWebResponse.builder()
				.hospitalCode(rs.getString("hospital_code"))
				.hospitalName(rs.getString("hospital_name"))
				.hospitalAddress(rs.getString("hospital_address"))
				.hospitalTel(rs.getString("hospital_tel"))
				.totalDoctors(HospitalMapperUtils.parseInteger(rs.getString("doctor_num")))
				.coordinateX(rs.getDouble("coordinate_x"))
				.coordinateY(rs.getDouble("coordinate_y"))

				// hospital_detail 테이블
				.weekdayLunch(rs.getString("weekday_lunch"))
				.parkingCapacity(rs.getInt("parking_capacity"))
				.parkingFee("Y".equalsIgnoreCase(rs.getString("park_xpns_yn")))
				.noTrmtHoli(rs.getString("no_Trmt_Holi"))
				.noTrmtSun(rs.getString("no_Trmt_Sun"))

				// 오늘 운영시간 (임시로 월요일 사용)
				.todayOpen(HospitalMapperUtils.formatTime(rs.getString("mon_open")))
				.todayClose(HospitalMapperUtils.formatTime(rs.getString("mon_end")))

				// 주간 스케줄
				.weeklySchedule(HospitalMapperUtils.createWeeklySchedule(
					rs.getString("mon_open"), rs.getString("mon_end"),
					rs.getString("tues_open"), rs.getString("tues_end"),
					rs.getString("wed_open"), rs.getString("wed_end"),
					rs.getString("thurs_open"), rs.getString("thurs_end"),
					rs.getString("fri_open"), rs.getString("fri_end"),
					rs.getString("trmt_sat_start"), rs.getString("trmt_sat_end"),
					rs.getString("trmt_sun_start"), rs.getString("trmt_sun_end")
				))
				.medicalSubjects(new ArrayList<>())
				.professionalDoctors(new HashMap<>())
				.build();
	}
}
