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

package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** MyBatis mapper for staged agent-APK rollouts. Auto-registered via the mapper package scan. */
public interface RolloutMapper {

    @Insert({"INSERT INTO agentRollout (customerId, targetVersion, packageName, apkUrl, apkSha256, apkVersionCode, apkSignatureChecksum, stage, createdAt, updatedAt) " +
            "VALUES (#{customerId}, #{targetVersion}, #{packageName}, #{apkUrl}, #{apkSha256}, #{apkVersionCode}, #{apkSignatureChecksum}, #{stage}, #{createdAt}, #{updatedAt})"})
    @SelectKey(statement = "SELECT currval('agentrollout_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insertRollout(AgentRollout rollout);

    @Insert({"INSERT INTO agentRolloutCanary (rolloutId, deviceNumber) VALUES (#{rolloutId}, #{deviceNumber}) " +
            "ON CONFLICT DO NOTHING"})
    void insertCanary(@Param("rolloutId") int rolloutId, @Param("deviceNumber") String deviceNumber);

    @Select({"SELECT * FROM agentRollout WHERE customerId = #{customerId} AND stage IN ('canary','fleet') ORDER BY id DESC LIMIT 1"})
    AgentRollout findActiveByCustomer(@Param("customerId") int customerId);

    @Select({"SELECT * FROM agentRollout WHERE id = #{id}"})
    AgentRollout findById(@Param("id") int id);

    @Update({"UPDATE agentRollout SET stage = #{stage}, updatedAt = #{updatedAt} WHERE id = #{id}"})
    void updateStage(@Param("id") int id, @Param("stage") String stage, @Param("updatedAt") long updatedAt);

    @Select({"SELECT deviceNumber FROM agentRolloutCanary WHERE rolloutId = #{rolloutId}"})
    List<String> listCanaryNumbers(@Param("rolloutId") int rolloutId);

    @Select({"SELECT d.number AS deviceNumber, s.agentVersion AS agentVersion, s.agentVersionCode, s.agentSignatureChecksum, s.agentPackageName, s.updatedAt AS lastSeen, d.agentCapabilities AS capabilitiesJson " +
            "FROM devices d LEFT JOIN device_state s ON s.deviceNumber = d.number WHERE d.customerId = #{customerId}"})
    List<RolloutDeviceRow> listCustomerDevices(@Param("customerId") int customerId);

    @Select({"SELECT DISTINCT c.deviceNumber FROM agentCommand c JOIN devices d ON d.number = c.deviceNumber " +
            "WHERE d.customerId = #{customerId} AND c.type = 'app.install' AND c.status IN ('pending','delivered')"})
    List<String> listPendingInstallNumbers(@Param("customerId") int customerId);

    @Insert("INSERT INTO agentRolloutTarget(rolloutId, deviceNumber) " +
            "SELECT #{id}, number FROM devices WHERE customerId = #{customerId}")
    void snapshotTargets(@Param("id") int id, @Param("customerId") int customerId);

    String TARGET_ROWS = "SELECT d.number AS deviceNumber, s.agentVersion, s.agentVersionCode, " +
            "s.agentSignatureChecksum, s.agentPackageName, s.updatedAt AS lastSeen, d.agentCapabilities AS capabilitiesJson, " +
            "c.status AS commandStatus, c.detail AS commandDetail, c.createdAt AS commandCreatedAt, c.completedAt AS commandCompletedAt, " +
            "EXISTS(SELECT 1 FROM agentRolloutCanary ca WHERE ca.rolloutId = t.rolloutId AND ca.deviceNumber = d.number) AS canary, " +
            "(SELECT COUNT(*) FROM agentCommand ac WHERE ac.rolloutId = t.rolloutId AND ac.deviceNumber = d.number " +
            " AND ac.id > t.retryAfterCommandId AND ac.deliveredAt IS NOT NULL) AS attempts, " +
            "EXISTS(SELECT 1 FROM agentCommand ac WHERE ac.deviceNumber = d.number AND ac.type = 'app.install' " +
            " AND ac.status IN ('pending','delivered','accepted')) AS busy " +
            "FROM agentRolloutTarget t JOIN agentRollout r ON r.id = t.rolloutId " +
            "JOIN devices d ON d.number = t.deviceNumber AND d.customerId = r.customerId " +
            "LEFT JOIN device_state s ON s.deviceNumber = d.number " +
            "LEFT JOIN LATERAL (SELECT * FROM agentCommand ac WHERE ac.rolloutId = t.rolloutId " +
            " AND ac.deviceNumber = d.number AND ac.id > t.retryAfterCommandId ORDER BY ac.id DESC LIMIT 1) c ON true " +
            "WHERE t.rolloutId = #{id} ";

    @Select(TARGET_ROWS + " ORDER BY d.number")
    List<RolloutDeviceRow> targets(@Param("id") int id);

    @Select(TARGET_ROWS + " AND d.number = #{deviceNumber}")
    RolloutDeviceRow target(@Param("id") int id, @Param("deviceNumber") String deviceNumber);

    @Select("SELECT id FROM agentRollout WHERE id = #{id} FOR UPDATE")
    Integer lock(@Param("id") int id);

    @Insert("INSERT INTO agentCommand(deviceNumber, type, payload, requiresCapability, status, createdAt, rolloutId) " +
            "VALUES(#{deviceNumber}, 'app.install', #{payload}, 'app.silentInstall', 'pending', #{now}, #{id}) " +
            "ON CONFLICT DO NOTHING")
    int enqueue(@Param("id") int id, @Param("deviceNumber") String deviceNumber,
                @Param("payload") String payload, @Param("now") long now);

    @Update("UPDATE agentRolloutTarget SET retryAfterCommandId = (SELECT COALESCE(MAX(id),0) FROM agentCommand WHERE rolloutId = #{id} AND deviceNumber = #{deviceNumber}) WHERE rolloutId = #{id} AND deviceNumber = #{deviceNumber}")
    void retry(@Param("id") int id, @Param("deviceNumber") String deviceNumber);

    @Update("UPDATE agentCommand SET status = 'cancelled', detail = 'Rollout stopped by administrator', completedAt = #{now} " +
            "WHERE rolloutId = #{id} AND status = 'pending'")
    void cancelPending(@Param("id") int id, @Param("now") long now);

    @Update("UPDATE agentCommand SET status = 'done', detail = 'Verified installed agent version from check-in', completedAt = #{now} " +
            "WHERE rolloutId = #{id} AND deviceNumber = #{deviceNumber} AND status IN ('pending','delivered','accepted')")
    void completeTarget(@Param("id") int id, @Param("deviceNumber") String deviceNumber, @Param("now") long now);

    @Update("UPDATE agentCommand SET status = 'cancelled', detail = 'Device is not eligible for this agent release', completedAt = #{now} " +
            "WHERE rolloutId = #{id} AND deviceNumber = #{deviceNumber} AND status = 'pending'")
    void cancelIneligible(@Param("id") int id, @Param("deviceNumber") String deviceNumber, @Param("now") long now);
}
