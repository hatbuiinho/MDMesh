package com.hmdm.persistence.domain;

public class AgentRelease {
    private Integer id;
    private Integer customerId;
    private String packageName;
    private String versionName;
    private Integer versionCode;
    private String sha256;
    private String signatureChecksum;
    private String filePath;
    private Long createdAt;
    public Integer getId() { return id; }
    public void setId(Integer v) { id = v; }
    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer v) { customerId = v; }
    public String getPackageName() { return packageName; }
    public void setPackageName(String v) { packageName = v; }
    public String getVersionName() { return versionName; }
    public void setVersionName(String v) { versionName = v; }
    public Integer getVersionCode() { return versionCode; }
    public void setVersionCode(Integer v) { versionCode = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { sha256 = v; }
    public String getSignatureChecksum() { return signatureChecksum; }
    public void setSignatureChecksum(String v) { signatureChecksum = v; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String v) { filePath = v; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long v) { createdAt = v; }
}
