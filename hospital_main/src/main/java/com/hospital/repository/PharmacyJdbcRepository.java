package com.hospital.repository;

import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.hospital.dto.PharmacyWebResponse;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PharmacyJdbcRepository {

	private final JdbcTemplate jdbcTemplate;

	public List<PharmacyWebResponse> findByPharmacyCodes(List<String> codes) {
		if (codes.isEmpty())
			return List.of();

		String placeholders = String.join(",", Collections.nCopies(codes.size(), "?"));

		String sql = """
				SELECT ykiho, pharmacy_name, address, phone, fax, etc, map_info,
				       latitude, longitude,
				       mon_open, mon_close, tue_open, tue_close,
				       wed_open, wed_close, thu_open, thu_close,
				       fri_open, fri_close, sat_open, sat_close,
				       sun_open, sun_close, holiday_open, holiday_close
				       FROM pharmacy
				       WHERE ykiho IN (""" + placeholders + ")";
		return jdbcTemplate.query(sql, new PharmacyWebResponseRowMapper(), codes.toArray());
	}

}
