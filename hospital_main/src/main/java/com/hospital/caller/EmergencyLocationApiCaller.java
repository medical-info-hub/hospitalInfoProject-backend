package com.hospital.caller;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.hospital.dto.EmergencyLocationApiResponse;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class EmergencyLocationApiCaller {

	private final RestTemplate restTemplate;
	private final XmlMapper xmlMapper;

	@Value("${hospital.emergencyLocation.api.baseUrl}")
	private String baseUrl;

	@Value("${hospital.emergency.api.serviceKey}")
	private String serviceKey;

	public EmergencyLocationApiCaller(RestTemplate restTemplate, XmlMapper xmlMapper) {
		this.restTemplate = restTemplate;
		this.xmlMapper = xmlMapper;
	}

	public EmergencyLocationApiResponse callApi(int pageNo, int numOfRows) {
	    try {
	        String encodedServiceKey = URLEncoder.encode(serviceKey, StandardCharsets.UTF_8.toString());

	        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
	                .queryParam("serviceKey", encodedServiceKey)
	                .queryParam("pageNo", pageNo)
	                .queryParam("numOfRows", numOfRows)
	                .build(true)
	                .toUri();

	        log.debug("응급실 위치 API 호출 시작 - 페이지: {}", pageNo);

	        HttpHeaders headers = new HttpHeaders();
	        headers.setContentType(MediaType.APPLICATION_XML);
	        headers.add("Accept", "application/xml, text/xml");
	        headers.add("Accept-Charset", "UTF-8");

	        HttpEntity<String> entity = new HttpEntity<>(headers);
	        ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class);
	        byte[] responseBytes = response.getBody();

	        if (responseBytes == null || responseBytes.length == 0) {
	            log.warn("응급실 API 응답이 비어있음");
	            throw new RuntimeException("응급실 API 응답이 비어있습니다");
	        }

	        String responseBody = new String(responseBytes, StandardCharsets.UTF_8);
	        log.debug("응급실 API 응답 길이: {} bytes", responseBody.length());

	        // XML 응답을 EmergencyApiResponse DTO로 매핑
	        EmergencyLocationApiResponse mappedResponse = xmlMapper.readValue(responseBody, EmergencyLocationApiResponse.class);

	        // resultCode 검사
	        if (mappedResponse.getHeader() == null) {
	            log.error("응급실 API 응답 헤더가 없음");
	            throw new RuntimeException("응급실 API 응답 헤더가 없습니다");
	        }

	        String resultCode = mappedResponse.getHeader().getResultCode();
	        String resultMsg = mappedResponse.getHeader().getResultMsg();

	        if (!"00".equals(resultCode)) {
	            log.error("응급실 API 응답 오류 - 코드: {}, 메시지: {}", resultCode, resultMsg);
	            throw new RuntimeException("응급실 API 응답 오류: " + resultCode + " - " + resultMsg);
	        }

	        // 성공 로그
	        if (mappedResponse.getBody() != null && mappedResponse.getBody().getItems() != null
	            && mappedResponse.getBody().getItems().getItem() != null) {
	            log.debug("응급실 데이터 {} 건 수신 완료", mappedResponse.getBody().getItems().getItem().size());
	        }

	        return mappedResponse;

	    } catch (HttpClientErrorException e) {
	        // 4xx 클라이언트 오류
	        log.error("응급실 API 클라이언트 오류 - 상태코드: {}, 응답: {}", e.getStatusCode(), e.getResponseBodyAsString());
	        throw new RuntimeException("응급실 API 클라이언트 오류(page: " + pageNo + "): " + e.getMessage(), e);

	    } catch (HttpServerErrorException e) {
	        // 5xx 서버 오류
	        log.error("응급실 API 서버 오류 - 상태코드: {}, 응답: {}", e.getStatusCode(), e.getResponseBodyAsString());
	        throw new RuntimeException("응급실 API 서버 오류(page: " + pageNo + "): " + e.getMessage(), e);

	    } catch (JsonProcessingException e) {
	        // XML 파싱 오류
	        log.error("응급실 API XML 파싱 오류: {}", e.getMessage());
	        throw new RuntimeException("응급실 API XML 파싱 오류(page: " + pageNo + "): " + e.getMessage(), e);

	    } catch (Exception e) {
	        // 기타 예외
	        log.error("응급실 API 호출 중 예외 발생(page: {}): {}", pageNo, e.getMessage(), e);
	        throw new RuntimeException("응급실 API 호출 중 오류 발생(page: " + pageNo + "): " + e.getMessage(), e);
	    }
	}
}
