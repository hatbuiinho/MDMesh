package com.hmdm.persistence.mapper;
import com.hmdm.persistence.domain.AppInstallRequest;
import org.apache.ibatis.annotations.*;
import java.util.*;
public interface AppInstallMapper {
    @Update("UPDATE appInstallRequest SET status='stale' WHERE deviceNumber=#{device} AND configurationId!=#{config} AND status IN ('pending','approved')")
    void invalidate(@Param("device") String device,@Param("config") int config);
    @Select("SELECT number FROM devices WHERE number=#{device} FOR UPDATE") String lockDevice(String device);
    @Select("SELECT count(*) FROM appInstallRequest WHERE deviceNumber=#{device} AND createdAt>#{since}")
    int recentCount(@Param("device") String device,@Param("since") long since);
    @Select("SELECT count(*) FROM appInstallRequest WHERE deviceNumber=#{deviceNumber} AND packageName=#{packageName} AND configurationId=#{configurationId} AND status IN ('pending','approved')") int open(AppInstallRequest row);
    @Insert("INSERT INTO appInstallRequest(deviceNumber,configurationId,packageName,appName,developer,reason,status,createdAt) VALUES(#{deviceNumber},#{configurationId},#{packageName},#{appName},#{developer},#{reason},'pending',#{createdAt})") int insert(AppInstallRequest row);
    @Select("SELECT r.*,COALESCE(NULLIF(d.description,''),d.number) AS deviceName FROM appInstallRequest r JOIN devices d ON d.number=r.deviceNumber WHERE d.customerId=#{customer} ORDER BY r.createdAt DESC,r.id DESC LIMIT 100 OFFSET #{offset}")
    List<AppInstallRequest> list(@Param("customer") int customer,@Param("offset") int offset);
    @Select("SELECT r.* FROM appInstallRequest r JOIN devices d ON d.number=r.deviceNumber WHERE d.customerId=#{customer} AND r.configurationId=d.configurationId AND r.status='pending' ORDER BY r.id DESC LIMIT 2000") List<AppInstallRequest> notificationCandidates(int customer);
    @Select("SELECT r.*,COALESCE(NULLIF(d.description,''),d.number) AS deviceName FROM appInstallRequest r JOIN devices d ON d.number=r.deviceNumber WHERE r.deviceNumber=#{device} AND ((r.status='approved' AND r.installedAt IS NULL) OR r.id IN (SELECT recent.id FROM appInstallRequest recent WHERE recent.deviceNumber=#{device} ORDER BY recent.createdAt DESC,recent.id DESC LIMIT 100)) ORDER BY r.createdAt DESC,r.id DESC") List<AppInstallRequest> deviceRequests(String device);
    @Select("SELECT * FROM appInstallRequest WHERE id=#{id}") AppInstallRequest get(int id);
    @Update("UPDATE appInstallRequest SET status=#{row.status},decisionBy=#{row.decisionBy},decisionNote=#{row.decisionNote},decidedAt=#{row.decidedAt} WHERE id=#{row.id} AND status=#{expected} AND configurationId=(SELECT configurationId FROM devices WHERE number=appInstallRequest.deviceNumber)")
    int decide(@Param("row") AppInstallRequest row,@Param("expected") String expected);
    @Insert("INSERT INTO appInstallAudit(requestId,status,actor,note,createdAt) VALUES(#{id},#{status},#{decisionBy},#{decisionNote},#{decidedAt})") void audit(AppInstallRequest row);
    @Select("SELECT status,actor,note,createdAt FROM appInstallAudit WHERE requestId=#{id} ORDER BY id") List<Map<String,Object>> history(int id);
    @Update("UPDATE appInstallRequest SET installedAt=#{now} WHERE id=#{id} AND deviceNumber=#{device} AND status='approved' AND installedAt IS NULL AND configurationId=(SELECT configurationId FROM devices WHERE number=#{device})")
    int installed(@Param("id") int id,@Param("device") String device,@Param("now") long now);
    @Select("SELECT packageName FROM appInstallRequest WHERE deviceNumber=#{device} AND configurationId=#{config} AND status='approved' ORDER BY packageName") List<String> grants(@Param("device") String device,@Param("config") int config);
}
