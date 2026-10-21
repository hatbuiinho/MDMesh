package com.hmdm.persistence.domain;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class AgentAdminRefreshSession {
    private String id;
    private int userId;
    private int customerId;
    private String installationId;
    private String credentialVersion;
    private long expiresAt;
    private Long revokedAt;
    private Long usedAt;
}
