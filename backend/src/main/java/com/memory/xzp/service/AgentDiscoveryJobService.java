package com.memory.xzp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memory.xzp.config.AgentConversation;
import com.memory.xzp.exception.*;
import com.memory.xzp.mapper.AgentDiscoveryJobMapper;
import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.model.vo.SimilarFileInfoListVO;
import com.memory.xzp.service.impl.SimilarDetectServiceImpl;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.*;

@Service
public class AgentDiscoveryJobService {
    private final AgentDiscoveryJobMapper mapper;
    private final SimilarDetectServiceImpl discovery;
    private final FileMapper files;
    private final ObjectMapper json;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), runnable -> {Thread thread=new Thread(runnable,"personal-similar-discovery");thread.setDaemon(true);return thread;});
    private final Map<String,Future<?>> futures=new ConcurrentHashMap<>();
    private final Map<Long,String> activeUsers=new ConcurrentHashMap<>();
    public AgentDiscoveryJobService(AgentDiscoveryJobMapper mapper, SimilarDetectServiceImpl discovery, FileMapper files, ObjectMapper json) {
        this.mapper=mapper;this.discovery=discovery;this.files=files;this.json=json;
    }
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recover() {mapper.recover(java.time.LocalDateTime.now());}
    @PreDestroy public void close(){worker.shutdownNow();}

    public Map<String,Object> submit(Long user,double threshold,int groups) {
        if(!Double.isFinite(threshold)||threshold<0.5||threshold>1||groups<1||groups>50)
            throw new BusinessException(StatusCode.PARAMS_ERROR,"相似发现参数无效");
        String conversation=AgentConversation.current();
        String id=UUID.randomUUID().toString();
        String current=activeUsers.putIfAbsent(user,id);
        if(current!=null) throw new BusinessException(StatusCode.CONFLICT_ERROR,"已有相似发现任务，请等待完成或取消后重新提交");
        try {
            mapper.insert(id,user,conversation);
            FutureTask<Void> task=new FutureTask<>(()-> {run(id,user,threshold,groups);return null;});
            futures.put(id,task);worker.execute(task);
        } catch(RuntimeException error) {
            futures.remove(id);activeUsers.remove(user,id);
            mapper.finish(id,"FAILED",null,"实例任务队列已满，请稍后重试");
            throw new BusinessException(StatusCode.RATE_LIMIT_ERROR,"实例任务队列已满，请稍后重试");
        }
        return status(id,user);
    }
    private void run(String id,Long user,double threshold,int groups) {
        try {
            if(mapper.start(id)!=1)return;
            int[] last={-1};
            var result=discovery.discover(threshold,groups,user,10000,120,progress->{
                if(last[0]!=progress) {
                    last[0]=progress;
                    if(mapper.progress(id,progress)!=1) throw new CancellationException();
                }
            });
            mapper.finish(id,"SUCCEEDED",json.writeValueAsString(result),null);
        } catch(CancellationException error) {mapper.finish(id,"CANCELLED",null,"任务已取消");}
        catch(Exception error) {mapper.finish(id,"FAILED",null,error instanceof BusinessException?error.getMessage():"发现未完成，可能已超时；请稍后重试或缩小范围");}
        finally {futures.remove(id);activeUsers.remove(user,id);}
    }
    public List<Map<String,Object>> recent(Long user) {return mapper.recent(user).stream().map(id->status(id,user)).toList();}
    public Map<String,Object> cancel(String id,Long user) {
        status(id,user);
        if(mapper.cancel(id,user)==1) {
            Future<?> task=futures.remove(id);
            if(task!=null) task.cancel(true);
            worker.purge();
            activeUsers.remove(user,id);
        }
        return status(id,user);
    }
    public Map<String,Object> status(String id,Long user) {
        Map<String,Object> row=mapper.owned(id,user);
        if(row==null) throw new BusinessException(StatusCode.NOT_FOUND_ERROR,"找不到该相似发现任务");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("jobId",id);result.put("status",row.get("status"));result.put("progress",row.get("progress"));
        result.put("message",row.getOrDefault("error_message","相似组仅是候选，不代表可安全删除的重复文件。最多显示50组、每组50张。"));
        if("SUCCEEDED".equals(row.get("status"))) {
            try {
                List<SimilarFileInfoListVO> groups=json.readValue(String.valueOf(row.get("result_json")),new TypeReference<>(){});
                List<String> ids=groups.stream().flatMap(g->g.getFileList().stream()).map(f->f.getFileId()).distinct().toList();
                Set<String> owned=ids.isEmpty()?Set.of():new HashSet<>(files.selectOwnedActiveFileIds(ids,user));
                for(var group:groups)group.setFileList(group.getFileList().stream().filter(f->owned.contains(f.getFileId())).toList());
                result.put("groups",groups.stream().filter(g->g.getFileList().size()>1).toList());
            }catch(Exception error){result.put("status","UNKNOWN");result.put("message","无法验证历史结果，请重新查询");}
        }
        return result;
    }
}
