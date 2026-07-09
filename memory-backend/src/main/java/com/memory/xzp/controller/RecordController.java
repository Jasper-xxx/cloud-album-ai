package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.exception.BusinessException;
import com.memory.xzp.exception.StatusCode;
import com.memory.xzp.model.entity.Record;
import com.memory.xzp.service.RecordService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 操作记录表 前端控制器
 * </p>
 *
 * @author xzp
 * @since 2025-03-06
 */
@RestController
@RequestMapping("/record")
public class RecordController {
    @Resource
    private RecordService recordService;
    @PostMapping("/selectAll")
    public BaseResponse<?> RecoverPicture(Integer current ,Integer size) {

        Page<Record> page = new Page<>(current, size);
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        QueryWrapper<Record> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id",userId);
        // 按操作时间倒序排序
        queryWrapper.orderByDesc("operation_time");

        Page<Record> recordPage = recordService.getBaseMapper().selectPage(page, queryWrapper);
        return ResultUtil.success(recordPage,"获取数据成功!") ;
    }

    /**
     * 批量删除操作记录
     * @param recordIds 记录ID列表
     * @return 删除结果
     */
    @PostMapping("/delete")
    public BaseResponse<?> deleteRecords(@RequestBody List<Long> recordIds) {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        boolean success = recordService.deleteRecords(recordIds, userId);
        if (success) {
            return ResultUtil.success("删除成功!");
        } else {
            return ResultUtil.error(StatusCode.PARAMS_ERROR, "删除失败!");
        }
    }

    /**
     * 清空全部操作记录
     * @return 清空结果
     */
    @PostMapping("/clearAll")
    public BaseResponse<?> clearAllRecords() {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        boolean success = recordService.clearAllRecords(userId);
        if (success) {
            return ResultUtil.success("清空成功!");
        } else {
            return ResultUtil.error(StatusCode.PARAMS_ERROR, "清空失败!");
        }
    }

}
