package com.hmdm.persistence.domain;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class AppUsageSettings {
    private int configurationId;
    private String timezone;
    private String schedules;
}
