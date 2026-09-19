package com.memory.xzp.service;

import com.memory.xzp.mapper.FileMapper;
import com.memory.xzp.mapper.FileObjectGcMapper;
import com.memory.xzp.utils.file.MinioOSSUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;

/** Retryable object deletion after the relation, quota and file-row transaction committed. */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileObjectGcService {
    private final FileObjectGcMapper mapper;
    private final FileMapper fileMapper;
    private final MinioOSSUtil minio;
    @Value("${app.file.gc.enabled:true}")
    private boolean enabled;

    @Scheduled(initialDelayString = "${app.file.gc.initial-delay-ms:300000}", fixedDelayString = "${app.file.gc.delay-ms:300000}")
    public void cleanCommittedObjects() {
        if (!enabled) return;
        for (var row : mapper.pending()) {
            String object = String.valueOf(row.get("object_name"));
            try {
                if (fileMapper.selectKnownObjectNames(List.of(object)).isEmpty()) minio.delete(object);
                mapper.complete(((Number) row.get("id")).longValue());
            } catch (RuntimeException error) {
                log.warn("Committed object cleanup will retry: queueId={}", row.get("id"));
            }
        }
    }
}
