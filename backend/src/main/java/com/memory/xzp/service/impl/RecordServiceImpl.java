package com.memory.xzp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.memory.xzp.model.entity.Record;
import com.memory.xzp.mapper.RecordMapper;
import com.memory.xzp.service.RecordService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.memory.xzp.utils.auth.IpUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 操作记录表 服务实现类
 * </p>
 *
 * @author xzp
 * @since 2025-03-06
 */
@Service
public class RecordServiceImpl extends ServiceImpl<RecordMapper, Record> implements RecordService {
    @Resource
    IpUtil ipUtil;

    @Override
    public void createRecordLog(String operation,  Integer number, Long userId, HttpServletRequest request) {
        Record record = new Record();
        record.setNumber(number);
        record.setOperation(operation);
        record.setUserId(userId);
        record.setOperationTime(LocalDateTime.now());
        record.setIpv4(ipUtil.getClientIpAddress(request));
        this.save(record);
    }

    @Override
    public boolean deleteRecords(List<Long> recordIds, Long userId) {
        // 构建查询条件：记录ID在列表中且属于当前用户
        QueryWrapper<Record> queryWrapper = new QueryWrapper<>();
        queryWrapper.in("id", recordIds);
        queryWrapper.eq("user_id", userId);
        
        // 执行物理删除
        int deleteCount = this.baseMapper.delete(queryWrapper);
        return deleteCount > 0;
    }

    @Override
    public boolean clearAllRecords(Long userId) {
        // 构建查询条件：属于当前用户的所有记录
        QueryWrapper<Record> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        
        // 执行物理删除
        int deleteCount = this.baseMapper.delete(queryWrapper);
        return deleteCount > 0;
    }
}
