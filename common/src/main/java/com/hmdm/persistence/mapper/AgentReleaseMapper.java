package com.hmdm.persistence.mapper;

import com.hmdm.persistence.domain.AgentRelease;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface AgentReleaseMapper {
    @Select("SELECT EXISTS(SELECT 1 FROM agentRelease WHERE filePath = #{path})")
    boolean isPublishedPath(@Param("path") String path);

    @Select("SELECT id FROM customers WHERE id = #{customerId} FOR UPDATE")
    Integer lockCustomer(@Param("customerId") int customerId);
    @Select("SELECT * FROM agentRelease WHERE customerId = #{customerId} ORDER BY versionCode DESC, id DESC")
    List<AgentRelease> list(@Param("customerId") int customerId);

    @Select("SELECT * FROM agentRelease WHERE id = #{id} AND customerId = #{customerId}")
    AgentRelease find(@Param("customerId") int customerId, @Param("id") int id);

    @Insert("INSERT INTO agentRelease(customerId, packageName, versionName, versionCode, sha256, signatureChecksum, filePath, createdAt) " +
            "VALUES(#{customerId}, #{packageName}, #{versionName}, #{versionCode}, #{sha256}, #{signatureChecksum}, #{filePath}, #{createdAt})")
    @SelectKey(statement = "SELECT currval('agentrelease_id_seq')", keyProperty = "id", before = false, resultType = int.class)
    void insert(AgentRelease release);
}
