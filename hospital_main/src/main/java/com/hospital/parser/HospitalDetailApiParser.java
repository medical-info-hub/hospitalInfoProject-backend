package com.hospital.parser;

import com.hospital.dto.HospitalDetailApiItem;
import com.hospital.dto.HospitalDetailApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class HospitalDetailApiParser {

	private final ObjectMapper objectMapper;

	public HospitalDetailApiParser() {
		this.objectMapper = new ObjectMapper();
	}

	public HospitalDetailApiResponse parseResponse(String json) throws Exception {
		return objectMapper.readValue(json, HospitalDetailApiResponse.class);
	}

	/**
	 * API 응답을 파싱하여 HospitalDetailApiItem 리스트 반환 (엔티티 변환 생략)
	 */
	public List<HospitalDetailApiItem> parse(HospitalDetailApiResponse response, String hospitalCode) {
		List<HospitalDetailApiItem> items = new ArrayList<>();

		try {
			if (response != null && response.getResponse() != null && response.getResponse().getBody() != null
					&& response.getResponse().getBody().getItems() != null
					&& response.getResponse().getBody().getItems().getItem() != null) {

				List<HospitalDetailApiItem> apiItems = response.getResponse().getBody().getItems().getItem();

				for (HospitalDetailApiItem item : apiItems) {
					// hospitalCode 설정
					item.setHospitalCode(hospitalCode);
					// 문자열 정리
					normalizeItem(item);
					items.add(item);
				}
			} else {
				// 빈 데이터인 경우 빈 객체 생성
				HospitalDetailApiItem emptyItem = new HospitalDetailApiItem();
				emptyItem.setHospitalCode(hospitalCode);
				items.add(emptyItem);
			}
		} catch (Exception e) {
			log.error("파싱 중 예외 발생 - 빈 Item 생성: {}", hospitalCode, e);
			HospitalDetailApiItem emptyItem = new HospitalDetailApiItem();
			emptyItem.setHospitalCode(hospitalCode);
			items.add(emptyItem);
		}

		return items;
	}

	/**
	 * Item의 문자열 필드 정리 (trim 처리)
	 */
	private void normalizeItem(HospitalDetailApiItem item) {
		item.setParkQty(safeGetString(item.getParkQty()));
		item.setParkXpnsYn(safeGetString(item.getParkXpnsYn()));
		item.setLunchWeek(safeGetString(item.getLunchWeek()));
		item.setNoTrmtHoli(safeGetString(item.getNoTrmtHoli()));
		item.setNoTrmtSun(safeGetString(item.getNoTrmtSun()));
		item.setTrmtMonStart(safeGetString(item.getTrmtMonStart()));
		item.setTrmtMonEnd(safeGetString(item.getTrmtMonEnd()));
		item.setTrmtTueStart(safeGetString(item.getTrmtTueStart()));
		item.setTrmtTueEnd(safeGetString(item.getTrmtTueEnd()));
		item.setTrmtWedStart(safeGetString(item.getTrmtWedStart()));
		item.setTrmtWedEnd(safeGetString(item.getTrmtWedEnd()));
		item.setTrmtThurStart(safeGetString(item.getTrmtThurStart()));
		item.setTrmtThurEnd(safeGetString(item.getTrmtThurEnd()));
		item.setTrmtFriStart(safeGetString(item.getTrmtFriStart()));
		item.setTrmtFriEnd(safeGetString(item.getTrmtFriEnd()));
		item.setTrmtSatStart(safeGetString(item.getTrmtSatStart()));
		item.setTrmtSatEnd(safeGetString(item.getTrmtSatEnd()));
		item.setTrmtSunStart(safeGetString(item.getTrmtSunStart()));
		item.setTrmtSunEnd(safeGetString(item.getTrmtSunEnd()));
	}

	private String safeGetString(String value) {
		if (value == null || value.trim().isEmpty())
			return null;
		return value.trim();
	}
}