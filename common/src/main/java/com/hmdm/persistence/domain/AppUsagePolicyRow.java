package com.hmdm.persistence.domain;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class AppUsagePolicyRow {
    private Integer id;
    private Integer configurationId;
    private String timezone;
    private String packageName;
    private Integer dailyLimitMinutes;
    private Integer warningMinutes;
    private String action;
    private Boolean enabled;
    private String allowedWindows;
}
