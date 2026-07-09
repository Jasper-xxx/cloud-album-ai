import request from "@/api/request";

export interface MultipartUploadPart {
  partNumber: number;
  url: string;
  expiresAt?: number;
}

export interface MultipartUploadSession {
  sessionId?: string;
  objectName?: string;
  fileId?: string;
  instantUpload: boolean;
  partSize: number;
  partCount: number;
  parts: MultipartUploadPart[];
  uploadedParts?: number[];
  urlsExpireAt?: number;
  completed?: boolean;
}

export interface MultipartUploadInitRequest {
  fileName: string;
  fileSize: number;
  contentType: string;
  lastModified: number;
  albumId: number;
  md5: string;
  sha256: string;
}

export async function initializeMultipartUpload(data: MultipartUploadInitRequest) {
  return request<API.BaseResponse>('/file/multipart/init', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
  });
}

export async function completeMultipartUpload(sessionId: string) {
  return request<API.BaseResponse>('/file/multipart/complete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { sessionId },
  });
}

export async function getMultipartUploadStatus(sessionId: string) {
  return request<API.BaseResponse>('/file/multipart/status', {
    method: 'GET',
    params: { sessionId },
  });
}

export async function refreshMultipartUploadUrls(sessionId: string, partNumbers?: number[]) {
  return request<API.BaseResponse>('/file/multipart/refresh', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { sessionId, partNumbers },
  });
}

export async function abortMultipartUpload(sessionId: string) {
  return request<API.BaseResponse>('/file/multipart/abort', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: { sessionId },
  });
}
export async function selectAllFileInfo(data: object) {
  return request<API.BaseResponse>('/file/selectAllFileInfo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

// 定义进度回调类型
type ProgressCallback = (percentage: number) => void;

// 修改请求方法的接口定义
export async function createSimilarFileList(data: object) {
  return request<API.BaseResponse>('/file/createSimilarFileList', {
    method: 'POST',
    headers: {
      'Content-Type': 'multipart/form-data', // 👈 建议修改为适用于文件上传的类型
    },
    timeout: -1,
    data: data,
  });
}
export async function getSimilarFileList(data: object) {
  return request<API.BaseResponse>('/file/getSimilarFileList', {
    method: 'POST',
    headers: {
      'Content-Type': 'multipart/form-data', // 👈 建议修改为适用于文件上传的类型
    },
    timeout: -1,
    data: data,
  });
}

export async function getDownloadToken(data: object) {
  return request<API.BaseResponse>('/file/getDownloadToken', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  })
}

export async function deleteFileByIds(data: object) {
  return request<API.BaseResponse>('/file/deleteFileByIds', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

export async function selectMetaDataByFileId(data: object) {
  return request<API.BaseResponse>('/file/selectMetaDataByFileId', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}



export async function createShareUrl(data: object) {
  return request<API.BaseResponse>('/file/createShareUrl', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

export async function getShareFileInfo(data: object) {
  return request<API.BaseResponse>('/file/getShareFileInfo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}
export async function saveSharePicture(data: object) {
  return request<API.BaseResponse>('/file/saveSharePicture', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  })
}
export async function selectSharedMetaDataByFileId(data: { fileId: string; shareToken: string }) {
  return request<API.BaseResponse>('/file/selectSharedMetaDataByFileId', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}
export async function getDownloadSharedFileToken(data: object) {
  return request<API.BaseResponse>('/file/getDownloadSharedFileToken', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  })
}

export async function addPictureTag(data: object) {
  return request<API.BaseResponse>('/file/addPictureTag', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}
export async function addSomePictureTag(data: object) {
  
  return request<API.BaseResponse>('/file/addSomePictureTag', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    }, 
    data: data,
  })
}

export async function removePictureTag(data: object) {
  return request<API.BaseResponse>('/file/removePictureTag', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

export async function selectFileListByKeyword(data: object) {
  return request<API.BaseResponse>('/search/selectFileListByKeyword', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

export async function selectTagByFileId(data: object) {
  return request<API.BaseResponse>('/file/selectTagByFileId', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

export async function getPictureTag(data: object) {
  return request<API.BaseResponse>('/file/getPictureTag', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    data: data,
  });
}

export async function batchGetPictureTag(data: { fileIds: string[]; autoAddTag?: boolean }) {
  return request<API.BaseResponse>('/file/batchGetPictureTag', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data,
  });
}

/**
 * 以图搜图（核心接口）
 * 上传查询图片，在当前用户相册中搜索相似图片
 * 按搜索模式返回相似图片：模糊/精确匹配返回全部达标结果，局部匹配最多 20 条
 *
 * @param formData  FormData 对象，包含 image 字段（File 类型）
 * @param onUploadProgress  上传进度回调（可选）
 */
export async function searchSimilarImages(formData: FormData, onUploadProgress?: ProgressCallback) {
  return request<API.BaseResponse>('/imageSearch/search', {
    method: 'POST',
    headers: {
      'Content-Type': 'multipart/form-data',
    },
    data: formData,
    timeout: 60000, // AI 推理最长 60 秒
  });
}

/** 手动修正图片地理位置（输入城市名/地址文字） */
export async function updateLocation(data: { fileId: string; locationValue: string }) {
  return request<API.BaseResponse>('/file/updateLocation', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data,
  });
}

/** 手动设置 GPS 坐标（输入经纬度），后端自动触发逆地理编码重新解析地址 */
export async function updateGpsCoordinate(data: {
  fileId: string;
  latitude: number;
  longitude: number;
}) {
  return request<API.BaseResponse>('/file/updateGpsCoordinate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data,
  });
}
