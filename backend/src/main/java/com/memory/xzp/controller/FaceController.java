package com.memory.xzp.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.memory.xzp.common.BaseResponse;
import com.memory.xzp.common.ResultUtil;
import com.memory.xzp.service.FaceService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 人像特征表 前端控制器
 * </p>
 *
 * @author xzp
 * @since 2025-03-07
 */
@RestController
@RequestMapping("/face")
public class FaceController {

    @Resource
    private FaceService faceService;

    @PostMapping("/recluster")
    @Operation(summary = "人物回并重聚类", description = "对当前用户已处理的人脸分组执行一次回并重聚类")
    public BaseResponse<?> recluster() {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        int mergedCount = faceService.reclusterUserFaces(userId);
        return ResultUtil.success(mergedCount, "回并重聚类完成");
    }

    @PostMapping("/rebuild-clusters")
    @Operation(summary = "重建人物聚类", description = "基于当前用户已提取的人脸向量重建人物分组，可拆分错误合并的人物")
    public BaseResponse<?> rebuildClusters() {
        Long userId = Long.parseLong(StpUtil.getLoginId().toString());
        int personCount = faceService.rebuildUserFaceClusters(userId);
        return ResultUtil.success(personCount, "人物聚类重建完成");
    }

}
