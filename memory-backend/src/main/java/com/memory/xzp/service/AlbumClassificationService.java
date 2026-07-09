package com.memory.xzp.service;

import com.memory.xzp.model.dto.album.AlbumBatchAddFilesRequest;
import com.memory.xzp.model.dto.album.AlbumBatchCreateRequest;
import com.memory.xzp.model.dto.album.AlbumBatchUpdateRequest;
import com.memory.xzp.model.dto.album.SaveClassificationRequest;
import com.memory.xzp.model.vo.album.AlbumBatchAddFilesResultVO;
import com.memory.xzp.model.vo.album.AlbumBatchUpdateResultVO;
import com.memory.xzp.model.vo.album.AlbumVO;
import com.memory.xzp.model.vo.album.SaveClassificationResultVO;

import java.util.List;

public interface AlbumClassificationService {
    List<Long> batchCreateAlbums(AlbumBatchCreateRequest request, Long userId);

    AlbumBatchUpdateResultVO batchUpdateAlbums(AlbumBatchUpdateRequest request, Long userId);

    AlbumBatchAddFilesResultVO batchAddFiles(AlbumBatchAddFilesRequest request, Long userId);

    AlbumVO getAlbumByTagName(String tagName, Long userId);

    SaveClassificationResultVO saveClassification(SaveClassificationRequest request, Long userId);
}
