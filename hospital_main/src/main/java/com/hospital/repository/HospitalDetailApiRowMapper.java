package com.hospital.repository;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.springframework.jdbc.core.RowMapper;

import com.hospital.dto.HospitalDetailApiItem;

/**
 * 외부 API 데이터 파싱용 RowMapper
 * hospital_detail 테이블을 HospitalDetailApiItem으로 변환
 * 원본 데이터를 그대로 유지 (1:1 매핑)
 */
public class HospitalDetailApiRowMapper implements RowMapper<HospitalDetailApiItem> {

	@Override
	public HospitalDetailApiItem mapRow(ResultSet rs, int rowNum) throws SQLException {
		HospitalDetailApiItem item = new HospitalDetailApiItem();
		item.setHospitalCode(rs.getString("hospital_code"));
		item.setParkQty(rs.getString("parking_capacity"));
		item.setParkXpnsYn(rs.getString("park_xpns_yn"));
		item.setLunchWeek(rs.getString("weekday_lunch"));
		item.setNoTrmtHoli(rs.getString("no_Trmt_Holi"));
		item.setNoTrmtSun(rs.getString("no_Trmt_Sun"));
		item.setTrmtMonStart(rs.getString("mon_open"));
		item.setTrmtMonEnd(rs.getString("mon_end"));
		item.setTrmtTueStart(rs.getString("tues_open"));
		item.setTrmtTueEnd(rs.getString("tues_end"));
		item.setTrmtWedStart(rs.getString("wed_open"));
		item.setTrmtWedEnd(rs.getString("wed_end"));
		item.setTrmtThurStart(rs.getString("thurs_open"));
		item.setTrmtThurEnd(rs.getString("thurs_end"));
		item.setTrmtFriStart(rs.getString("fri_open"));
		item.setTrmtFriEnd(rs.getString("fri_end"));
		item.setTrmtSatStart(rs.getString("trmt_sat_start"));
		item.setTrmtSatEnd(rs.getString("trmt_sat_end"));
		item.setTrmtSunStart(rs.getString("trmt_sun_start"));
		item.setTrmtSunEnd(rs.getString("trmt_sun_end"));
		return item;
	}
}
