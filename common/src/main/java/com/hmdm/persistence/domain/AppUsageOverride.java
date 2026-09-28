package com.hmdm.persistence.domain;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class AppUsageOverride {
    private String deviceNumber;
    private String packageName;
    private Integer extraMinutes;
    private Long expiresAt;
    private Long createdAt;
}
