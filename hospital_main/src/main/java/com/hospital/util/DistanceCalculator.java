package com.hospital.util;

import org.springframework.stereotype.Component;

@Component
public class DistanceCalculator {

	private static final double EARTH_RADIUS = 6371; // 지구 반지름 (단위: km)
	 private static final double KM_PER_DEGREE_LAT = 110.0;

	public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
		double latDistance = Math.toRadians(lat2 - lat1);
		double lonDistance = Math.toRadians(lon2 - lon1);
		double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2) + Math.cos(Math.toRadians(lat1))
				* Math.cos(Math.toRadians(lat2)) * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
		double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
		return EARTH_RADIUS * c; // km 단위
	}
	
	 public double[] calcMBR(double lat, double lng, double radiusKm) {
	        double deltaDegreeY = radiusKm / KM_PER_DEGREE_LAT;
	        double kmPerDegreeLon = 111.32 * Math.cos(Math.toRadians(lat));
	        double deltaDegreeX = radiusKm / kmPerDegreeLon;
	        return new double[]{
	            lng - deltaDegreeX, lng + deltaDegreeX,
	            lat - deltaDegreeY, lat + deltaDegreeY
	        };
	    }
}