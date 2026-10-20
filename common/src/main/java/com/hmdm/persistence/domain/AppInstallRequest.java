package com.hmdm.persistence.domain;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class AppInstallRequest {
    private int id;
    private String deviceNumber;
    private String deviceName;
    private Integer configurationId;
    private String packageName;
    private String appName;
    private String developer;
    private String reason;
    private String status;
    private String decisionBy;
    private String decisionNote;
    private long createdAt;
    private Long decidedAt;
    private Long installedAt;
}
