package com.memory.xzp.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.memory.xzp.model.vo.FileInfoListVO;
import com.memory.xzp.model.vo.entity.FileMetaDataVO;
import com.memory.xzp.model.vo.entity.ShareFileVO;
import com.memory.xzp.model.vo.picture.BatchGetPictureTagResponseVO;
import com.memory.xzp.model.vo.task.ImageTagTaskVO;
import com.memory.xzp.model.vo.visual.FileTagVO;
import com.memory.xzp.model.dto.upload.DirectUploadRegistration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;


@Service
public interface FileService {
    Boolean uploadImageFile(Long userId, MultipartFile multipartFile, String fileSuffix, LocalDateTime lastModifiedTime,Long albumId);

    Boolean uploadVideoFile(Long userId, MultipartFile multipartFile, String fileSuffix, LocalDateTime lastModifiedTime,Long albumId);

    String registerDirectUpload(DirectUploadRegistration registration);

    String reuseOwnedFileIfPresent(Long userId, Long albumId, String md5);

    Boolean checkIsUpload(Long userId, Long albumId,String fileMd5);

    void saveSharePicture(Long userId, Long albumId, List<String> fileIds, String shareToken);

    Long validateShareAccess(String shareToken, List<String> fileIds);



    Page<FileInfoListVO> getFileInfoList(Integer current,Integer size,String orderType,String orderKeyword,String imageTypeText,String locationLevel,String locationValue, String tagFilter, Long userId, Long albumId,Boolean isDeleted);

    Page<FileInfoListVO> getTagFileInfo(Integer current, Integer size,Long userId, String orderType, String orderKeyword, String imageTypeText, String tag);

    List<FileInfoListVO> getSimilarFileList(String imageTypeText,Long userId);

    void createSimilarFileList(Double similarity,Integer size,Long userId);
    Boolean setIsDeleted(List<String> fileIds,boolean isDeleted,Long useId);

    void downloadFileByIds(HttpServletResponse response, List<String> fileIds, Long useId);
    String getDownloadAlbumToken(Long albumId, Long userId);
    String getDownloadToken(List<String> fileIds,Long userId);

    void downloadAlbumByToken(HttpServletRequest request, HttpServletResponse response, Long albumId, Long userId);
    void downloadZipFileByIds(HttpServletResponse response, List<String> fileIds, Long useId,String downloadZipName);

    FileMetaDataVO selectFileMetaDataById(String fileId, Long userId);

    FileMetaDataVO selectSharedFileMetaDataById(String fileId, String shareToken);

    String createShareUrl(List<String> fileIds,Long userId,Integer shareDay);

    String createAlbumShareUrl(List<Long> albumIds, Long userId, Integer shareDay);

    ShareFileVO getShareInfo(String shareKey);

    boolean addTag(List<String> fileIds,String imageType,String tagName,Long userId);

    boolean removeTag(List<String> fileIds,String tag,Long userId);

    ImageTagTaskVO getPictureTag(String fileId, String thumbnailObjectName, Boolean autoAddTag, Long userId);

    BatchGetPictureTagResponseVO batchGetPictureTag(List<String> fileIds, Boolean autoAddTag, Long userId);

    List<String> selectTagByFileId(String fileId,Long userId);
    List<String> selectAllModels(Long userId);

    void cronUpdateFileUrl();

    /**
     * 手动修正图片的地理位置。
     * @param userId        当前登录用户ID（权限校验）
     * @param fileId        需要修正的文件ID
     * @param locationValue 用户输入的城市/地址名称
     */
    void updateLocation(Long userId, String fileId, String locationValue);

    /**
     * 手动更新图片/视频的 GPS 坐标，并重新触发逆地理编码以更新 location 文字
     *
     * @param userId    当前登录用户
     * @param fileId    目标文件 ID
     * @param latitude  纬度（-90 ~ 90，南纬为负）
     * @param longitude 经度（-180 ~ 180，西经为负）
     */
    void updateGpsCoordinate(Long userId, String fileId, Double latitude, Double longitude);

}
