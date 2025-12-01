package com.hospital.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.hospital.dto.HospitalDetailApiItem;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class HospitalDetailJdbcRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int BATCH_SIZE = 100;

    // RowMapper: ResultSet → HospitalDetailApiItem 변환
    private static final RowMapper<HospitalDetailApiItem> ROW_MAPPER = new RowMapper<HospitalDetailApiItem>() {
        @Override
        public HospitalDetailApiItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            HospitalDetailApiItem item = new HospitalDetailApiItem();
            item.setHospitalCode(rs.getString("hospital_code"));
            item.setParkQty(rs.getString("parking_capacity"));
            item.setParkXpnsYn(rs.getString("park_xpns_yn"));
            item.setLunchWeek(rs.getString("weekday_lunch"));
            item.setNoTrmtHoli(rs.getString("noTrmtHoli"));
            item.setNoTrmtSun(rs.getString("noTrmtSun"));
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
    };

    /**
     * 1. 전체 병원 코드 조회 (중복 제거)
     */
    public List<String> findAllDistinctHospitalCodes() {
        String sql = "SELECT DISTINCT hospital_code FROM hospital_detail";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * 2. 병원 코드로 삭제
     */
    @Transactional
    public void deleteByHospitalCodeIn(List<String> hospitalCodes) {
        if (hospitalCodes == null || hospitalCodes.isEmpty()) {
            return;
        }

        String placeholders = hospitalCodes.stream()
                .map(code -> "?")
                .collect(Collectors.joining(","));

        String sql = "DELETE FROM hospital_detail WHERE hospital_code IN (" + placeholders + ")";

        int deletedCount = jdbcTemplate.update(sql, hospitalCodes.toArray());
        log.info("병원 상세정보 삭제 완료: {}건", deletedCount);
    }

    /**
     * 3. 대량 INSERT (JDBC Batch)
     */
    @Transactional
    public void batchInsert(List<HospitalDetailApiItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO hospital_detail " +
                "(hospital_code, parking_capacity, park_xpns_yn, weekday_lunch, noTrmtHoli, noTrmtSun, " +
                "mon_open, mon_end, tues_open, tues_end, wed_open, wed_end, " +
                "thurs_open, thurs_end, fri_open, fri_end, trmt_sat_start, trmt_sat_end, " +
                "trmt_sun_start, trmt_sun_end) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        List<Object[]> batchArgs = items.stream()
                .map(item -> new Object[]{
                        item.getHospitalCode(),
                        parseInteger(item.getParkQty()),
                        item.getParkXpnsYn(),
                        item.getLunchWeek(),
                        item.getNoTrmtHoli(),
                        item.getNoTrmtSun(),
                        item.getTrmtMonStart(),
                        item.getTrmtMonEnd(),
                        item.getTrmtTueStart(),
                        item.getTrmtTueEnd(),
                        item.getTrmtWedStart(),
                        item.getTrmtWedEnd(),
                        item.getTrmtThurStart(),
                        item.getTrmtThurEnd(),
                        item.getTrmtFriStart(),
                        item.getTrmtFriEnd(),
                        item.getTrmtSatStart(),
                        item.getTrmtSatEnd(),
                        item.getTrmtSunStart(),
                        item.getTrmtSunEnd()
                })
                .collect(Collectors.toList());

        int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
        log.info("병원 상세정보 INSERT 완료: {}건", results.length);
    }

    /**
     * 4. 대량 UPDATE (JDBC Batch)
     */
    @Transactional
    public void batchUpdate(List<HospitalDetailApiItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        String sql = "UPDATE hospital_detail SET " +
                "parking_capacity = ?, park_xpns_yn = ?, weekday_lunch = ?, " +
                "noTrmtHoli = ?, noTrmtSun = ?, " +
                "mon_open = ?, mon_end = ?, tues_open = ?, tues_end = ?, " +
                "wed_open = ?, wed_end = ?, thurs_open = ?, thurs_end = ?, " +
                "fri_open = ?, fri_end = ?, trmt_sat_start = ?, trmt_sat_end = ?, " +
                "trmt_sun_start = ?, trmt_sun_end = ? " +
                "WHERE hospital_code = ?";

        List<Object[]> batchArgs = items.stream()
                .map(item -> new Object[]{
                        parseInteger(item.getParkQty()),
                        item.getParkXpnsYn(),
                        item.getLunchWeek(),
                        item.getNoTrmtHoli(),
                        item.getNoTrmtSun(),
                        item.getTrmtMonStart(),
                        item.getTrmtMonEnd(),
                        item.getTrmtTueStart(),
                        item.getTrmtTueEnd(),
                        item.getTrmtWedStart(),
                        item.getTrmtWedEnd(),
                        item.getTrmtThurStart(),
                        item.getTrmtThurEnd(),
                        item.getTrmtFriStart(),
                        item.getTrmtFriEnd(),
                        item.getTrmtSatStart(),
                        item.getTrmtSatEnd(),
                        item.getTrmtSunStart(),
                        item.getTrmtSunEnd(),
                        item.getHospitalCode() // WHERE 절
                })
                .collect(Collectors.toList());

        int[] results = jdbcTemplate.batchUpdate(sql, batchArgs);
        log.info("병원 상세정보 UPDATE 완료: {}건", results.length);
    }

    /**
     * 5. 병원 코드로 조회
     */
    public Map<String, HospitalDetailApiItem> findByHospitalCodeInAsMap(List<String> hospitalCodes) {
        if (hospitalCodes == null || hospitalCodes.isEmpty()) {
            return Map.of();
        }

        String placeholders = hospitalCodes.stream()
                .map(code -> "?")
                .collect(Collectors.joining(","));

        String sql = "SELECT * FROM hospital_detail WHERE hospital_code IN (" + placeholders + ")";

        List<HospitalDetailApiItem> results = jdbcTemplate.query(sql, ROW_MAPPER, hospitalCodes.toArray());

        return results.stream()
                .collect(Collectors.toMap(
                        HospitalDetailApiItem::getHospitalCode,
                        item -> item
                ));
    }

    /**
     * Helper: String → Integer 변환
     */
    private Integer parseInteger(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            log.warn("정수 변환 실패: {}", value);
            return null;
        }
    }
}
