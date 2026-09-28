package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppUsageDailyReport {
    private String packageName;
    private String usageDate;
    private Long foregroundMs;
    private Long limitReachedAt;
    private String status;
}
