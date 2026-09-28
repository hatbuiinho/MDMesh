package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DesiredAppUsage {
    private String timezone = "UTC";
    private List<AppUsageRule> rules = new ArrayList<AppUsageRule>();
    private List<AppUsageGrant> overrides = new ArrayList<AppUsageGrant>();
}
