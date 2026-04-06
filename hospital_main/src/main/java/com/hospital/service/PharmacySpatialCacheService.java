package com.hospital.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.hospital.dto.PharmacyWebResponse;
import com.hospital.repository.PharmacyJdbcRepository;
import com.hospital.util.DistanceCalculator;

import geoindex.api.SpatialCacheEngine;

@Service
public class PharmacySpatialCacheService {
	private final PharmacyJdbcRepository pharmacyJdbcRepository;
	private final SpatialCacheEngine<PharmacyWebResponse> spatialCacheEngine;
	private final DistanceCalculator distanceCalculator;
	
	@Autowired
	public PharmacySpatialCacheService( PharmacyJdbcRepository pharmacyJdbcRepository, 
			@Qualifier("pharmacySpatialCahceEngine") SpatialCacheEngine<PharmacyWebResponse> spatialCacheEngine,
			DistanceCalculator distanceCalculator) {
		this.pharmacyJdbcRepository = pharmacyJdbcRepository;
		this.spatialCacheEngine = spatialCacheEngine;
		this.distanceCalculator = distanceCalculator;
	}
	
	public List<PharmacyWebResponse> search (double lat, double lng, double radiusKm ){
		List<PharmacyWebResponse> results = spatialCacheEngine.search(lat, lng, radiusKm, codes -> {
			List<PharmacyWebResponse> dbResults = pharmacyJdbcRepository.findByPharmacyCodes(codes);
			return dbResults.stream()
					.collect(Collectors.toMap(PharmacyWebResponse::getPharmacyCode, p -> p ));
		});
		
		double[] mbr = distanceCalculator.calcMBR(lat, lng, radiusKm);
		return results.stream()
				.filter(h -> h.getCoordinateX() >= mbr[0] && h.getCoordinateX() <= mbr[1]
        				&& h.getCoordinateY() >= mbr[2] && h.getCoordinateY() <= mbr[3])
				.collect(Collectors.toList());
	}
	

}
