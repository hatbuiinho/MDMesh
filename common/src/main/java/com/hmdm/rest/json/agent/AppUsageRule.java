package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppUsageRule {
    private Integer id;
    private String packageName;
    private Integer dailyLimitMinutes;
    private Integer warningMinutes = 5;
    private String action = "suspend";
    private Boolean enabled = true;
    private List<AppUsageWindow> allowedWindows = new ArrayList<AppUsageWindow>();
}
