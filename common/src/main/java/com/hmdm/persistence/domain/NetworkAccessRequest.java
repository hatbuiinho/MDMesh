package com.hmdm.persistence.domain;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class NetworkAccessRequest {
    private String effectiveStatus;
    private boolean supported;
    private boolean applied;
    private int id;
    private String deviceNumber;
    private Integer configurationId;
    private String policyHash;
    private String packageName;
    private String domain;
    private String reason;
    private String status;
    private String decisionBy;
    private String decisionNote;
    private String grantPackages;
    private Long expiresAt;
    private long createdAt;
    private Long decidedAt;
    private String deviceName;
}
