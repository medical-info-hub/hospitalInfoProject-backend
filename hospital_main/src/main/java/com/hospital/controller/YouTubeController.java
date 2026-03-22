package com.hospital.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.caller.YouTubeApiCaller;
import com.hospital.entity.YouTubeVideo;
import com.hospital.service.YouTubeVideoService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/youtube")
@RequiredArgsConstructor
public class YouTubeController {

	private final YouTubeVideoService youTubeVideoService;
	private final YouTubeApiCaller youTubeApiCaller;


	@GetMapping(value="/categoryData",produces=MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> getData(){
	      return youTubeVideoService.getCategories();   		  
	}
    
	/**
	 * 페이지네이션 영상 조회 (15개씩) GET
	 * /youtube/videos/page?page=0&size=15&detailCategory=내과
	 */
	@GetMapping(value = "/videos/page", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> getVideosByPage(
			@RequestParam(value = "page", defaultValue = "0") int page,
			@RequestParam(value = "size", defaultValue = "15") int size,
			@RequestParam(value = "detailCategory", required = false) List<String> detailCategory) {

		Pageable pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending());
		Page<YouTubeVideo> videoPage = youTubeVideoService.getVideosByPage(detailCategory, pageable);

		Map<String, Object> response = new HashMap<>();
		response.put("content", videoPage.getContent()); // 실제 데이터
		response.put("currentPage", videoPage.getNumber()); // 현재 페이지 (0부터 시작)
		response.put("totalPages", videoPage.getTotalPages()); // 전체 페이지 수
		response.put("totalElements", videoPage.getTotalElements()); // 전체 데이터 수
		response.put("size", videoPage.getSize()); // 페이지 크기
		response.put("hasNext", videoPage.hasNext()); // 다음 페이지 있는지
		response.put("hasPrevious", videoPage.hasPrevious()); // 이전 페이지 있는지

		return ResponseEntity.ok(response);
	}
	
	@GetMapping(value = "/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
	public String searchVideos(
			@RequestParam(value = "query", required = true) String query){
		return youTubeApiCaller.searchMedicalVideos(query, 15);
	}

}