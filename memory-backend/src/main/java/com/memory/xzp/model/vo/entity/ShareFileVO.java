package com.memory.xzp.model.vo.entity;

import com.memory.xzp.model.vo.FileInfoListVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @description:
 * @author: xzp
 * @date: 2025/3/3,22:41
 */
@Data
public class ShareFileVO {
    //分享的文件信息
    private List<FileInfoListVO> fileInfoList;
    //分享人昵称
    private String sharePersonName;
    //分享人头像Url
    private String sharePersonAvatar;
    //分享链接访问人数
    private Long visitCount;

    private LocalDateTime expireTime;
}
