package com.hmdm.rest.json.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

@Getter @Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppRestSchedule {
    private String id;
    private String name;
    private boolean enabled = true;
    private String scope = "ALL";
    private List<String> packages = new ArrayList<String>();
    private List<String> exceptions = new ArrayList<String>();
    private List<Integer> days = new ArrayList<Integer>();
    private String from;
    private String to;
    private int warningMinutes = 5;
}
