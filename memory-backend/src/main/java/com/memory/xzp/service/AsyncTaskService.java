package com.memory.xzp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.model.vo.task.AsyncTaskBatchActionVO;
import com.memory.xzp.model.vo.task.AsyncTaskVO;

import java.util.List;

public interface AsyncTaskService {

    Long enqueueImageFeature(String fileId, Long userId, String objectName);

    Long enqueueFaceAnalysis(Long faceId, String fileId, Long userId);

    int enqueuePendingFaceAnalyses(int batchSize);

    Long enqueueVideoProcessing(String fileId, Long userId);

    int enqueuePendingVideoProcessing(int batchSize);

    Long enqueueGeocoding(String fileId, Long userId);

    int enqueuePendingGeocoding(int batchSize);

    Long enqueueImageTag(String fileId, Long userId, boolean autoAddTag);

    int enqueuePendingImageTags(int batchSize);

    Page<AsyncTaskVO> listUserTasks(Long userId, long current, long size, String status);

    AsyncTaskVO getUserTask(Long taskId, Long userId);

    void retryTask(Long taskId, Long userId);

    Page<AsyncTaskVO> listAdminTasks(
            long current,
            long size,
            String status,
            String taskType,
            Long userId,
            String fileId
    );

    AsyncTaskBatchActionVO retryTasks(List<Long> taskIds);

    AsyncTaskBatchActionVO cancelDeadTasks(List<Long> taskIds);
}
