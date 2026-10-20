package com.hmdm.persistence.domain;
import lombok.Getter;
import lombok.Setter;
@Getter @Setter
public class NetworkAdminSubscription {
    private int id;
    private int userId;
    private int customerId;
    private String installationId;
    private String tokenHash;
    private String credentialVersion;
    private long expiresAt;
}
