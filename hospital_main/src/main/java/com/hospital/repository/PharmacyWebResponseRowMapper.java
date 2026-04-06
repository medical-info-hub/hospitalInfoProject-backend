package com.hospital.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;

import org.springframework.jdbc.core.RowMapper;

import com.hospital.dto.PharmacyWebResponse;

import com.hospital.util.MapperUtils;

public class PharmacyWebResponseRowMapper implements RowMapper<PharmacyWebResponse>{
	
	@Override
	public PharmacyWebResponse mapRow(ResultSet rs, int rowNum) throws SQLException{
		DayOfWeek today = LocalDate.now().getDayOfWeek();

		return PharmacyWebResponse.builder()
				.pharmacyCode(rs.getString("ykiho"))
				.pharmacyName(rs.getString("pharmacy_name"))
				.pharmacyAddress(rs.getString("address"))
				.pharmacyTel(rs.getString("phone"))
				.pharmacyFax(rs.getString("fax"))
				.pharmacyEtc(rs.getString("etc"))
				.pharmacyMapInfo(rs.getString("map_info"))
				.coordinateX(rs.getDouble("longitude"))
				.coordinateY(rs.getDouble("latitude"))
				.todayOpen(MapperUtils.formatTime(MapperUtils.createPharmacyTodayOpen(rs, today)))
				.todayClose(MapperUtils.formatTime(MapperUtils.createPharmacyTodayClose(rs, today)))
				.weeklySchedule(MapperUtils.createPharmacyWeeklySchedule(
						rs.getString("mon_open"), rs.getString("mon_close"),
						rs.getString("tue_open"), rs.getString("tue_close"),
						rs.getString("wed_open"), rs.getString("wed_close"),
						rs.getString("thu_open"), rs.getString("thu_close"),
						rs.getString("fri_open"), rs.getString("fri_close"),
						rs.getString("sat_open"), rs.getString("sat_close"),
						rs.getString("sun_open"), rs.getString("sun_close"),
						rs.getString("holiday_open"), rs.getString("holiday_close")
					)).build();
	}
}
