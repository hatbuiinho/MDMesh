package com.hmdm.persistence.domain;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class DeviceAppUsageDaily {
    private String deviceNumber;
    private String packageName;
    private String usageDate;
    private Long foregroundMs;
    private Long limitReachedAt;
    private String status;
    private Long updatedAt;
}
