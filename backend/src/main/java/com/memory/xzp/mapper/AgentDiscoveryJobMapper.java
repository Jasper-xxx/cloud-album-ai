package com.memory.xzp.mapper;

import org.apache.ibatis.annotations.*;
import java.util.Map;
import java.util.List;

public interface AgentDiscoveryJobMapper {
    @Insert("INSERT INTO agent_discovery_job(job_id,user_id,conversation_id,status) VALUES(#{id},#{user},#{conversation},'QUEUED')")
    int insert(@Param("id") String id,@Param("user") Long user,@Param("conversation") String conversation);
    @Select("SELECT * FROM agent_discovery_job WHERE job_id=#{id} AND user_id=#{user}")
    Map<String,Object> owned(@Param("id") String id,@Param("user") Long user);
    @Select("SELECT job_id FROM agent_discovery_job WHERE user_id=#{user} ORDER BY created_at DESC LIMIT 5")
    List<String> recent(Long user);
    @Update("UPDATE agent_discovery_job SET status='RUNNING' WHERE job_id=#{id} AND status='QUEUED'")
    int start(String id);
    @Update("UPDATE agent_discovery_job SET progress=#{progress} WHERE job_id=#{id} AND status='RUNNING'")
    int progress(@Param("id") String id,@Param("progress") int progress);
    @Update("UPDATE agent_discovery_job SET status=#{status},result_json=#{json},error_message=#{error},progress=IF(#{status}='SUCCEEDED',100,progress) WHERE job_id=#{id} AND status IN ('QUEUED','RUNNING')")
    int finish(@Param("id") String id,@Param("status") String status,@Param("json") String json,@Param("error") String error);
    @Update("UPDATE agent_discovery_job SET status='CANCELLED' WHERE job_id=#{id} AND user_id=#{user} AND status IN ('QUEUED','RUNNING')")
    int cancel(@Param("id") String id,@Param("user") Long user);
    @Update("UPDATE agent_discovery_job SET status='FAILED',error_message='服务已重启，请重新提交发现任务' WHERE status IN ('QUEUED','RUNNING') AND created_at < #{before}")
    int recover(java.time.LocalDateTime before);
}
