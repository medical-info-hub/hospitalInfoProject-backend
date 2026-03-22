package com.hospital.event;

public class EmergencyDataUpdateEvent {

    private final String jsonData;

    public EmergencyDataUpdateEvent(String jsonData) {
        this.jsonData = jsonData;
    }

    public String getJsonData() {
        return jsonData;
    }
}
