package com.memory.xzp.service;

import com.memory.xzp.model.entity.Face;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 人像特征表 服务类
 * </p>
 *
 * @author xzp
 * @since 2025-03-07
 */
public interface FaceService extends IService<Face> {

    /**
     * 处理单张图片的人脸信息
     * @param face 待处理的人脸记录
     */
    void processOneFace(Face face);

    int reclusterUserFaces(Long userId);

}

