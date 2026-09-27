/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence.domain;

import java.io.Serializable;

/** A device's rollout-relevant facts: its number, last-reported agent version, and capability JSON. */
public class RolloutDeviceRow implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long agentVersionCode;
    public Long getAgentVersionCode() { return agentVersionCode; }
    public void setAgentVersionCode(Long v) { agentVersionCode = v; }
    private String agentSignatureChecksum;
    public String getAgentSignatureChecksum() { return agentSignatureChecksum; }
    public void setAgentSignatureChecksum(String v) { agentSignatureChecksum = v; }
    private String agentPackageName;
    public String getAgentPackageName() { return agentPackageName; }
    public void setAgentPackageName(String v) { agentPackageName = v; }
    private Long lastSeen;
    public Long getLastSeen() { return lastSeen; }
    public void setLastSeen(Long v) { lastSeen = v; }
    private String commandStatus;
    public String getCommandStatus() { return commandStatus; }
    public void setCommandStatus(String v) { commandStatus = v; }
    private String commandDetail;
    public String getCommandDetail() { return commandDetail; }
    public void setCommandDetail(String v) { commandDetail = v; }
    private Long commandCreatedAt;
    public Long getCommandCreatedAt() { return commandCreatedAt; }
    public void setCommandCreatedAt(Long v) { commandCreatedAt = v; }
    private Long commandCompletedAt;
    public Long getCommandCompletedAt() { return commandCompletedAt; }
    public void setCommandCompletedAt(Long v) { commandCompletedAt = v; }
    private Integer attempts;
    public Integer getAttempts() { return attempts; }
    public void setAttempts(Integer v) { attempts = v; }
    private Boolean busy;
    public Boolean getBusy() { return busy; }
    public void setBusy(Boolean v) { busy = v; }
    private Boolean canary;
    public Boolean getCanary() { return canary; }
    public void setCanary(Boolean v) { canary = v; }
    private String deviceNumber;
    private String agentVersion;
    private String capabilitiesJson;

    public String getDeviceNumber() { return deviceNumber; }
    public void setDeviceNumber(String deviceNumber) { this.deviceNumber = deviceNumber; }

    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }

    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
}
