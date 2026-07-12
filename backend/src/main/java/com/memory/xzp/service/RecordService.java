package com.memory.xzp.service;

import com.memory.xzp.model.entity.Record;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 操作记录表 服务类
 * </p>
 *
 * @author xzp
 * @since 2025-03-06
 */
public interface RecordService extends IService<Record> {

    void createRecordLog(String operation,  Integer number,  Long userId, HttpServletRequest request);

    /**
     * 批量删除操作记录
     * @param recordIds 记录ID列表
     * @param userId 用户ID
     * @return 是否删除成功
     */
    boolean deleteRecords(List<Long> recordIds, Long userId);

    /**
     * 清空用户的所有操作记录
     * @param userId 用户ID
     * @return 是否清空成功
     */
    boolean clearAllRecords(Long userId);

}
