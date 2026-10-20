package com.hmdm.persistence.mapper;
import com.hmdm.persistence.domain.NetworkAdminSubscription;
import org.apache.ibatis.annotations.*;
public interface NetworkAdminSubscriptionMapper {
    @Insert("INSERT INTO networkAdminSubscription(userId,customerId,installationId,tokenHash,credentialVersion,expiresAt) VALUES(#{userId},#{customerId},#{installationId},#{tokenHash},#{credentialVersion},#{expiresAt}) ON CONFLICT(userId,installationId) DO UPDATE SET tokenHash=EXCLUDED.tokenHash,credentialVersion=EXCLUDED.credentialVersion,expiresAt=EXCLUDED.expiresAt")
    void register(NetworkAdminSubscription row);
    @Select("SELECT * FROM networkAdminSubscription WHERE tokenHash=#{hash} AND expiresAt>#{now}")
    NetworkAdminSubscription find(@Param("hash") String hash,@Param("now") long now);
    @Delete("DELETE FROM networkAdminSubscription WHERE tokenHash=#{hash}")
    void revoke(String hash);
}
