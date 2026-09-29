package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AppUsageOverride;
import com.hmdm.persistence.domain.AppUsagePolicyRow;
import com.hmdm.persistence.domain.DeviceAppUsageDaily;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AppUsageMapper {
    @Select("SELECT * FROM appUsagePolicy WHERE configurationId=#{id} ORDER BY packageName")
    List<AppUsagePolicyRow> policies(@Param("id") int configurationId);

    @Delete("DELETE FROM appUsagePolicy WHERE configurationId=#{id}")
    void deletePolicies(@Param("id") int configurationId);

    @Insert("INSERT INTO appUsagePolicy(configurationId,timezone,packageName,dailyLimitMinutes,warningMinutes,action,enabled,allowedWindows) " +
            "VALUES(#{configurationId},#{timezone},#{packageName},#{dailyLimitMinutes},#{warningMinutes},#{action},#{enabled},#{allowedWindows})")
    @SelectKey(statement="SELECT currval('appusagepolicy_id_seq')", keyProperty="id", before=false, resultType=int.class)
    void insertPolicy(AppUsagePolicyRow row);

    @Insert("INSERT INTO deviceAppUsageDaily(deviceNumber,packageName,usageDate,foregroundMs,limitReachedAt,status,updatedAt) " +
            "VALUES(#{deviceNumber},#{packageName},CAST(#{usageDate} AS DATE),#{foregroundMs},#{limitReachedAt},#{status},#{updatedAt}) " +
            "ON CONFLICT(deviceNumber,packageName,usageDate) DO UPDATE SET foregroundMs=GREATEST(deviceAppUsageDaily.foregroundMs,EXCLUDED.foregroundMs), " +
            "limitReachedAt=COALESCE(deviceAppUsageDaily.limitReachedAt,EXCLUDED.limitReachedAt),status=EXCLUDED.status,updatedAt=EXCLUDED.updatedAt")
    void upsertDaily(DeviceAppUsageDaily row);

    @Select("SELECT u.deviceNumber,u.packageName,u.usageDate::text AS usageDate,u.foregroundMs,u.limitReachedAt,u.status,u.updatedAt " +
            "FROM deviceAppUsageDaily u JOIN devices d ON d.number=u.deviceNumber " +
            "WHERE d.customerId=#{customerId} AND (#{configurationId} IS NULL OR d.configurationId=#{configurationId}) " +
            "AND u.usageDate BETWEEN CAST(#{from} AS DATE) AND CAST(#{to} AS DATE) ORDER BY u.usageDate DESC,u.deviceNumber,u.packageName")
    List<DeviceAppUsageDaily> report(@Param("customerId") int customerId, @Param("configurationId") Integer configurationId,
                                     @Param("from") String from, @Param("to") String to);

    @Select("SELECT deviceNumber,packageName,usageDate::text AS usageDate,foregroundMs,limitReachedAt,status,updatedAt " +
            "FROM deviceAppUsageDaily WHERE deviceNumber=#{deviceNumber} " +
            "AND usageDate BETWEEN CAST(#{from} AS DATE) AND CAST(#{to} AS DATE) " +
            "ORDER BY usageDate DESC,foregroundMs DESC,packageName")
    List<DeviceAppUsageDaily> reportDevice(@Param("deviceNumber") String deviceNumber,
                                           @Param("from") String from, @Param("to") String to);

    @Insert("INSERT INTO appUsageOverride(deviceNumber,packageName,extraMinutes,expiresAt,createdAt) VALUES(#{deviceNumber},#{packageName},#{extraMinutes},#{expiresAt},#{createdAt}) " +
            "ON CONFLICT(deviceNumber,packageName) DO UPDATE SET extraMinutes=EXCLUDED.extraMinutes,expiresAt=EXCLUDED.expiresAt,createdAt=EXCLUDED.createdAt")
    void upsertOverride(AppUsageOverride row);

    @Select("SELECT o.* FROM appUsageOverride o JOIN devices d ON d.number=o.deviceNumber " +
            "WHERE d.configurationId=#{configurationId} AND o.expiresAt>#{now} " +
            "ORDER BY o.deviceNumber,o.packageName")
    List<AppUsageOverride> activeOverrides(@Param("configurationId") int configurationId, @Param("now") long now);
}
