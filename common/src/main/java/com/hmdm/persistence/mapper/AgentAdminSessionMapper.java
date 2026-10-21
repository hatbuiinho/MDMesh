package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentAdminRefreshSession;
import org.apache.ibatis.annotations.*;

public interface AgentAdminSessionMapper {
    @Select("SELECT id FROM users WHERE id=#{user} FOR UPDATE") Integer lockUser(int user);
    @Delete("DELETE FROM agentAdminRefreshSession WHERE userId=#{user} AND (installationId=#{installation} OR expiresAt<=#{now})")
    void replace(@Param("user") int user,@Param("installation") String installation,@Param("now") long now);
    @Insert("INSERT INTO agentAdminRefreshSession(id,userId,customerId,installationId,credentialVersion,expiresAt) VALUES(#{id},#{userId},#{customerId},#{installationId},#{credentialVersion},#{expiresAt})")
    void insert(AgentAdminRefreshSession row);
    @Insert("INSERT INTO agentAdminRefreshToken(tokenHash,sessionId,createdAt) VALUES(#{hash},#{session},#{now})")
    void token(@Param("hash") String hash,@Param("session") String session,@Param("now") long now);
    @Select("SELECT s.*,t.usedAt FROM agentAdminRefreshSession s JOIN agentAdminRefreshToken t ON t.sessionId=s.id WHERE t.tokenHash=#{hash} FOR UPDATE OF s,t")
    AgentAdminRefreshSession lockToken(String hash);
    @Update("UPDATE agentAdminRefreshToken SET usedAt=#{now} WHERE tokenHash=#{hash} AND usedAt IS NULL")
    int consume(@Param("hash") String hash,@Param("now") long now);
    @Update("UPDATE agentAdminRefreshSession SET revokedAt=#{now} WHERE id=#{id} AND revokedAt IS NULL")
    void revoke(@Param("id") String id,@Param("now") long now);
    @Select("SELECT * FROM agentAdminRefreshSession WHERE id=#{id}") AgentAdminRefreshSession get(String id);
    @Select("SELECT sessionId FROM agentAdminRefreshToken WHERE tokenHash=#{hash}") String family(String hash);
}
