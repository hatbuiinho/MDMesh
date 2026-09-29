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

import com.hmdm.persistence.domain.AgentCommand;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>MyBatis mapper for the opaque agent v1 command queue ({@link AgentCommand}). The server never
 * interprets {@code type}/{@code payload}; this mapper only stores and forwards them.</p>
 */
public interface AgentCommandMapper {

    @Insert({"INSERT INTO agentCommand (deviceNumber, type, payload, requiresCapability, status, createdAt, deliveredAt) " +
            "VALUES (#{deviceNumber}, #{type}, #{payload}, #{requiresCapability}, #{status}, #{createdAt}, #{deliveredAt})"})
    @SelectKey(statement = "SELECT currval('agentcommand_id_seq')", keyColumn = "id", keyProperty = "id",
            before = false, resultType = int.class)
    void insert(AgentCommand command);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND status = 'pending' ORDER BY id"})
    List<AgentCommand> listPending(@Param("deviceNumber") String deviceNumber);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND id = #{id}"})
    AgentCommand findByDeviceAndId(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id);

    /**
     * Atomically claim a pending command for delivery. Returns 1 if THIS caller claimed it, 0 if a
     * concurrent check-in already did — so a command is delivered to exactly one check-in.
     */
    @Update({"UPDATE agentCommand SET status = 'delivered', deliveredAt = #{deliveredAt} " +
            "WHERE id = #{id} AND status = 'pending'"})
    int claimForDelivery(@Param("id") Integer id, @Param("deliveredAt") Long deliveredAt);

    /**
     * config.apply is idempotent and normally finishes in seconds. If its result is lost because
     * the agent process or network dies between execution and the next check-in, lease it back to
     * pending so the device can converge instead of blocking configuration sync for six hours.
     */
    @Update({"UPDATE agentCommand SET status = 'pending', deliveredAt = NULL " +
            "WHERE deviceNumber = #{deviceNumber} AND type = 'config.apply' " +
            "AND status = 'delivered' AND deliveredAt < #{deliveredCutoff}"})
    int requeueStaleConfigApply(@Param("deviceNumber") String deviceNumber,
                                @Param("deliveredCutoff") long deliveredCutoff);

    /**
     * Record a terminal result, but only if the command isn't already GENUINELY terminal — first
     * real result wins; a late/duplicate ack can't overwrite a done/failed (+ its detail). A
     * device-reported result DOES overwrite 'expired': expiry is the server's guess, the device's
     * report is the truth (a slow install may complete after the lazy expiry flipped it).
     * Ownership rides the WHERE (id + deviceNumber) so no pre-SELECT is needed.
     */
    @Update({"UPDATE agentCommand SET status = #{status}, detail = #{detail}, completedAt = #{completedAt} " +
            "WHERE id = #{id} AND deviceNumber = #{deviceNumber} " +
            "AND status NOT IN ('done','failed','unsupported','cancelled') " +
            "AND (#{status} <> 'accepted' OR status IN ('delivered','accepted'))"})
    void markResultWithTime(@Param("deviceNumber") String deviceNumber, @Param("id") Integer id,
                            @Param("status") String status, @Param("detail") String detail,
                            @Param("completedAt") Long completedAt);

    /**
     * Two-tier lazy expiry: PENDING ages by creation time, but DELIVERED ages by delivery time
     * with its own (longer) leash — the device already holds a delivered command, and expiring it
     * by createdAt was killing slow in-flight installs at the 60-minute mark.
     */
    @Update({"UPDATE agentCommand SET status = 'expired', completedAt = #{now} " +
            "WHERE deviceNumber = #{deviceNumber} AND (" +
            "(status = 'pending' AND createdAt < #{pendingCutoff}) OR " +
            "(status IN ('delivered','accepted') AND deliveredAt IS NOT NULL AND deliveredAt < #{deliveredCutoff}))"})
    void expireStale(@Param("deviceNumber") String deviceNumber, @Param("pendingCutoff") long pendingCutoff,
                     @Param("deliveredCutoff") long deliveredCutoff, @Param("now") long now);

    /**
     * A terminal result for a newer install proves the agent has advanced past older delivered
     * installs. Their result was lost and they must not keep the device busy for the full lease.
     */
    @Update({"UPDATE agentCommand stale SET status = 'expired', " +
            "detail = 'Superseded by a newer completed install command', completedAt = #{now} " +
            "WHERE stale.deviceNumber = #{deviceNumber} AND stale.type = 'app.install' " +
            "AND stale.status IN ('delivered','accepted') AND EXISTS (" +
            "SELECT 1 FROM agentCommand newer WHERE newer.deviceNumber = stale.deviceNumber " +
            "AND newer.type = 'app.install' AND newer.id > stale.id " +
            "AND newer.status IN ('done','failed','unsupported'))"})
    int expireSupersededDeliveredInstalls(@Param("deviceNumber") String deviceNumber,
                                          @Param("now") long now);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND createdAt >= #{since} " +
            "ORDER BY id DESC LIMIT #{limit}"})
    List<AgentCommand> listHistory(@Param("deviceNumber") String deviceNumber,
                                   @Param("since") long since, @Param("limit") int limit);

    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} AND status IN ('pending','delivered','accepted')"})
    int countOpenOfType(@Param("deviceNumber") String deviceNumber, @Param("type") String type);

    /** Open command with the exact same intent. Used to make automatic app sync idempotent. */
    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} " +
            "AND payload = #{payload} AND status IN ('pending','delivered','accepted')"})
    int countOpenMatching(@Param("deviceNumber") String deviceNumber, @Param("type") String type,
                          @Param("payload") String payload);

    /** Same intent is either already in flight or was successfully applied. */
    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} " +
            "AND payload = #{payload} AND status IN ('pending','delivered','accepted','done')"})
    int countSatisfiedOrOpenMatching(@Param("deviceNumber") String deviceNumber,
                                     @Param("type") String type, @Param("payload") String payload);

    /** Exact app/version is permanently incompatible with this device's current Android SDK. */
    @Select({"SELECT COUNT(*) FROM agentCommand WHERE deviceNumber = #{deviceNumber} " +
            "AND type = 'app.install' AND payload = #{payload} AND status = 'failed' AND (" +
            "detail LIKE '%INSTALL_FAILED_OLDER_SDK%' OR detail LIKE '%Requires newer sdk version%')"})
    int countSdkIncompatibleMatching(@Param("deviceNumber") String deviceNumber,
                                     @Param("payload") String payload);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} ORDER BY id DESC LIMIT 1"})
    AgentCommand findLatestOfType(@Param("deviceNumber") String deviceNumber, @Param("type") String type);

    @Select({"SELECT * FROM agentCommand WHERE deviceNumber = #{deviceNumber} AND type = #{type} " +
            "AND status = 'done' ORDER BY id DESC LIMIT 1"})
    AgentCommand findLatestCompletedOfType(@Param("deviceNumber") String deviceNumber, @Param("type") String type);
}
