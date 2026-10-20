package com.hmdm.rest.json.agent;
import lombok.Getter;
import lombok.Setter;
import java.util.*;
@Getter @Setter
public class NetworkGrant {
    private int requestId;
    private String domain;
    private List<String> packages = new ArrayList<>();
    private Long expiresAt;
}
