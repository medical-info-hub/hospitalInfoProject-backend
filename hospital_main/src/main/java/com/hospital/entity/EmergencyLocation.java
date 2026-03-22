package com.hospital.entity;

import org.hibernate.annotations.DynamicUpdate;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "emergency_Location")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamicUpdate
public class EmergencyLocation {

	@Id
	@Column(name = "emergency_code", length = 50)
	private String emergencyCode;
	
	@Column(name = "coordinate_Y")
	private String coordinateY;
	
	@Column(name = "coordinate_X")
	private String coordinateX;
	
	@Column(name = "emergency_address")
	private String emergencyAddress;
	
	

}
