package com.hospital.caller;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospital.dto.DiseaseStatsApiResponse;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DiseaseStatsApiCaller {

	@Value("${diseasesStats.api.base-url}")
	private String baseUrl;

	@Value("${diseasesStats.api.Key}")
	private String serviceKey;

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;

	public DiseaseStatsApiCaller(RestTemplate restTemplate, ObjectMapper objectMapper) {
		this.restTemplate = restTemplate;
		this.objectMapper = objectMapper;
	}

	public DiseaseStatsApiResponse callApi(int StartYear, int EndYear, int pageNo) {
		String encodedServiceKey = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8);

		URI uri = UriComponentsBuilder.fromUriString(baseUrl).queryParam("serviceKey", encodedServiceKey)
				.queryParam("resType", "2").queryParam("searchPeriodType", "3").queryParam("searchStartYear", StartYear)
				.queryParam("searchEndYear", EndYear).queryParam("pageNo", pageNo).queryParam("numOfRows", "100")
				.build(true).toUri();

		try {

			log.debug("DiseaseStats API 호출 시작 - 페이지: {}", pageNo); 
			
			String responseJson = restTemplate.getForObject(uri, String.class);

			if (responseJson == null || responseJson.trim().isEmpty()) {
				log.warn("응답이 비어있습니다.");
				throw new RuntimeException("Api 응답이 비어있습니다");
			}

			log.debug("api 응답 {}", responseJson);

			DiseaseStatsApiResponse apiResponseDto = objectMapper.readValue(responseJson,
					DiseaseStatsApiResponse.class);
			
			//resultCode 검사
			String resultCode = apiResponseDto.getResponse().getHeader().getResultCode();
			String resultMsg = apiResponseDto.getResponse().getHeader().getResultMsg();
			
			if(!"00".equals(resultCode)) {
				log.error("Api 응답 오류 - 코드 : {}, 메시지 : {}", resultCode, resultMsg);
				throw new RuntimeException("Api 응답 오류 " + resultCode + " - " + resultMsg);
			}
			return apiResponseDto;
			

		} catch (HttpClientErrorException e) {
			// 4xx 클라이언트 오류
			log.error("API 클라이언트 오류 - 상태코드: {}, 응답: {}", e.getStatusCode(), e.getResponseBodyAsString());
			throw new RuntimeException("DiseaesStats API 클라이언트 오류: " + e.getMessage(), e);

		} catch (HttpServerErrorException e) {
			// 5xx 서버 오류
			log.error("API 서버 오류 - 상태코드: {}, 응답: {}", e.getStatusCode(), e.getResponseBodyAsString());
			throw new RuntimeException("DiseaesStats API 서버 오류: " + e.getMessage(), e);

		} catch (JsonProcessingException e) {
			// JSON 파싱 오류
			log.error("JSON 파싱 오류: {}", e.getMessage());
			throw new RuntimeException("API 응답 JSON 파싱 오류: " + e.getMessage(), e);

		} catch (Exception e) {
			// 기타 예외
			log.error("HospitalMain API 호출 중 예외 발생", e);
			throw new RuntimeException("DiseaesStats API 호출 중 오류 발생: " + e.getMessage(), e);
		}
	}
}