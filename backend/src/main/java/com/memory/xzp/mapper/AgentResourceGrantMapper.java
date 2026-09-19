package com.memory.xzp.mapper;

import com.memory.xzp.model.entity.AgentResourceGrant;
import org.apache.ibatis.annotations.*;

public interface AgentResourceGrantMapper {
    @Insert("""
            INSERT INTO agent_resource_grant (token_hash, user_id, kind, file_ids_json, expires_at)
            VALUES (#{tokenHash}, #{userId}, #{kind}, #{fileIdsJson}, #{expiresAt})
            """)
    int insert(AgentResourceGrant grant);

    @Select("""
            SELECT * FROM agent_resource_grant WHERE token_hash = #{hash} AND kind = #{kind}
            AND revoked = FALSE AND expires_at > NOW(3)
            """)
    AgentResourceGrant findActive(@Param("hash") String hash, @Param("kind") String kind);
}
