package com.hmdm.persistence.mapper;
import com.hmdm.persistence.domain.NetworkAccessRequest;
import org.apache.ibatis.annotations.*;
import java.util.*;
public interface NetworkAccessMapper {
    @Select("SELECT fingerprint,generation FROM networkDevicePolicyVersion WHERE deviceNumber=#{device}")
    Map<String,Object> version(String device);
    @Insert("INSERT INTO networkDevicePolicyVersion(deviceNumber,fingerprint) VALUES(#{device},#{hash}) ON CONFLICT DO NOTHING")
    void initVersion(@Param("device") String device,@Param("hash") String hash);
    @Update("UPDATE networkDevicePolicyVersion SET fingerprint=#{hash},generation=generation+1 WHERE deviceNumber=#{device} AND fingerprint!=#{hash}")
    void advanceVersion(@Param("device") String device,@Param("hash") String hash);
    @Select("SELECT rules FROM networkAccessPolicy WHERE configurationId=#{id}")
    String rules(int id);
    @Insert("INSERT INTO networkAccessPolicy(configurationId,rules) VALUES(#{id},#{rules}) ON CONFLICT(configurationId) DO UPDATE SET rules=EXCLUDED.rules")
    void save(@Param("id") int id,@Param("rules") String rules);
    @Select("SELECT r.*, COALESCE(NULLIF(d.description,''),d.number) AS deviceName FROM networkAccessRequest r JOIN devices d ON d.number=r.deviceNumber WHERE d.customerId=#{customer} AND (CAST(#{device} AS TEXT) IS NULL OR r.deviceNumber=#{device}) ORDER BY r.createdAt DESC,r.id DESC LIMIT 500 OFFSET #{offset}")
    List<NetworkAccessRequest> list(@Param("customer") int customer,@Param("device") String device,@Param("offset") int offset);
    @Select("SELECT * FROM networkAccessRequest WHERE id=#{id}")
    NetworkAccessRequest get(int id);
    @Select("SELECT * FROM networkAccessRequest WHERE deviceNumber=#{device} ORDER BY createdAt DESC,id DESC LIMIT 100")
    List<NetworkAccessRequest> deviceRequests(String device);
    @Select("SELECT number FROM devices WHERE number=#{device} FOR UPDATE")
    String lockDevice(String device);
    @Select("SELECT count(*) FROM networkAccessRequest WHERE deviceNumber=#{device} AND createdAt>#{since}")
    int recentCount(@Param("device") String device,@Param("since") long since);
    @Select("SELECT count(*) FROM networkAccessRequest WHERE deviceNumber=#{deviceNumber} AND configurationId=#{configurationId} AND policyHash=#{policyHash} AND packageName=#{packageName} AND domain=#{domain} AND status='pending'")
    int pending(NetworkAccessRequest row);
    @Insert("INSERT INTO networkAccessRequest(deviceNumber,configurationId,policyHash,packageName,domain,reason,status,createdAt) VALUES(#{deviceNumber},#{configurationId},#{policyHash},#{packageName},#{domain},#{reason},'pending',#{createdAt}) ON CONFLICT DO NOTHING")
    int insert(NetworkAccessRequest row);
    @Update("UPDATE networkAccessRequest SET status=#{row.status},decisionBy=#{row.decisionBy},decisionNote=#{row.decisionNote},grantPackages=#{row.grantPackages},expiresAt=#{row.expiresAt},decidedAt=#{row.decidedAt} WHERE id=#{row.id} AND status=#{expected}")
    int decide(@Param("row") NetworkAccessRequest row,@Param("expected") String expected);
    @Insert("INSERT INTO networkAccessAudit(requestId,status,actor,note,grantPackages,expiresAt,createdAt) VALUES(#{id},#{status},#{decisionBy},#{decisionNote},#{grantPackages},#{expiresAt},#{decidedAt})")
    void audit(NetworkAccessRequest row);
    @Select("SELECT status,actor,note,grantPackages,expiresAt,createdAt FROM networkAccessAudit WHERE requestId=#{id} ORDER BY id")
    List<Map<String,Object>> auditTrail(int id);
    @Select("SELECT * FROM networkAccessRequest WHERE deviceNumber=#{device} AND configurationId=#{config} AND policyHash=#{hash} AND status='approved' AND (expiresAt IS NULL OR expiresAt>#{now}) ORDER BY id")
    List<NetworkAccessRequest> grants(@Param("device") String device,@Param("config") int config,@Param("hash") String hash,@Param("now") long now);
}
