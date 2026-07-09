package com.memory.xzp.utils.file;

import com.memory.xzp.config.MinIOConfig;
import com.memory.xzp.metrics.BusinessMetrics;
import com.memory.xzp.model.entity.FileEntity;
import com.memory.xzp.service.ExternalServiceExecutor;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.compress.utils.IOUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cn.dev33.satoken.SaManager.log;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/10,21:31
 */
@Component
public class MinioOSSUtil {

    @Resource
    private MinioClient minioClient;
    @Resource
    private MinIOConfig configuration;
    @Resource
    private ExternalServiceExecutor externalServiceExecutor;
    @Resource
    private BusinessMetrics businessMetrics;

    private <T> T minioCall(String operation, String backend, MinioOperation<T> action) {
        long startedAt = System.nanoTime();
        try {
            T result = externalServiceExecutor.execute(backend, action::execute);
            businessMetrics.recordMinioOperation(operation, "SUCCESS", System.nanoTime() - startedAt);
            return result;
        } catch (RuntimeException e) {
            businessMetrics.recordMinioOperation(operation, "ERROR", System.nanoTime() - startedAt);
            throw e;
        }
    }

    private void minioRun(String operation, String backend, MinioRunnable action) {
        minioCall(operation, backend, () -> {
            action.run();
            return null;
        });
    }

    public void uploadToOSS(String objectName, InputStream inputstream, long objectSize,
                               String contentType)  {
        try {
            // 检查存储桶是否存在
            ensureBucket();

            // 上传文件对象
            minioRun("put_object", ExternalServiceExecutor.MINIO_UPLOAD, () ->
                    minioClient.putObject(PutObjectArgs.builder()
                            .bucket(configuration.getBucketName())
                            .object(objectName)
                            .stream(inputstream, objectSize, -1)
                            .contentType(contentType)
                            .build()));

        }
        catch (Exception e) {
            throw new RuntimeException("上传文件错误: "+objectName + e.getMessage(), e);
        }
        finally {
            try {
                inputstream.close();
            } catch (IOException e) {
                log.error("Failed to close upload stream: " + objectName + ", " + e.getMessage());
            }
        }
    }




    /**
     * 获取文件访问地址
     */
    public String getFileUrl(String objectName) {
        try {
            return minioCall("presign_get_object", ExternalServiceExecutor.MINIO, () ->
                    minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(configuration.getBucketName())
                            .object(objectName)
                            .build()
                    )
            );
        }  catch (Exception e) {
            throw new RuntimeException("获取文件Url错误: "+objectName + e.getMessage(), e);
        }
    }
    public byte[] getFileBytes(String objectName){
        long startedAt = System.nanoTime();
        // 获取文件流
        try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(configuration.getBucketName())
                            .object(objectName)
                            .build())) {

            byte[] bytes = inputStream.readAllBytes();
            businessMetrics.recordMinioOperation("get_object_bytes", "SUCCESS", System.nanoTime() - startedAt);
            return bytes;
        }
        catch (Exception e) {
            businessMetrics.recordMinioOperation("get_object_bytes", "ERROR", System.nanoTime() - startedAt);
            throw new RuntimeException("获取文件流错误: "+objectName + e.getMessage(), e);
        }
    }

    public void downloadToFile(String objectName, Path target) {
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(configuration.getBucketName())
                        .object(objectName)
                        .build())) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new RuntimeException("下载对象到临时文件失败: " + objectName, e);
        }
    }

    public void ensureBucket() {
        try {
            boolean found = minioCall("bucket_exists", ExternalServiceExecutor.MINIO, () ->
                    minioClient.bucketExists(BucketExistsArgs.builder()
                            .bucket(configuration.getBucketName())
                            .build()));
            if (!found) {
                minioRun("make_bucket", ExternalServiceExecutor.MINIO, () ->
                        minioClient.makeBucket(MakeBucketArgs.builder()
                                .bucket(configuration.getBucketName())
                                .build()));
            }
        } catch (Exception e) {
            throw new RuntimeException("创建对象存储桶失败", e);
        }
    }

    /**
     * 下载文件
     */
    public boolean objectExists(String objectName) {
        if (objectName == null || objectName.isBlank()) {
            return false;
        }
        try {
            return minioCall("stat_object", ExternalServiceExecutor.MINIO, () -> {
                minioClient.statObject(StatObjectArgs.builder()
                        .bucket(configuration.getBucketName())
                        .object(objectName)
                        .build());
                return true;
            });
        } catch (RuntimeException e) {
            if (isObjectNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    public List<String> listObjectNames(String prefix, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String safePrefix = prefix == null ? "" : prefix;
        return minioCall("list_objects", ExternalServiceExecutor.MINIO, () -> {
            List<String> objectNames = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(configuration.getBucketName())
                            .prefix(safePrefix)
                            .recursive(true)
                            .build()
            );
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                objectNames.add(item.objectName());
                if (objectNames.size() >= limit) {
                    break;
                }
            }
            return objectNames;
        });
    }

    private boolean isObjectNotFound(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ErrorResponseException errorResponseException) {
                String code = errorResponseException.errorResponse().code();
                return "NoSuchKey".equals(code)
                        || "NoSuchObject".equals(code)
                        || "NoSuchBucket".equals(code);
            }
            current = current.getCause();
        }
        return false;
    }

    public void download(HttpServletResponse response, FileEntity fileEntity) {
        InputStream in = null;
        try {
            String objectName = fileEntity.getFileObjectName();
            String originFileName = fileEntity.getOriginFileName();
            // 获取对象信息
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder().bucket(configuration.getBucketName()).object(objectName).build());
            response.setContentType(stat.contentType());
            response.setContentLengthLong(fileEntity.getSize());
            response.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(originFileName, StandardCharsets.UTF_8));
            // 文件下载
            in = minioClient.getObject(GetObjectArgs.builder().bucket(configuration.getBucketName()).object(objectName).build());
            IOUtils.copy(in, response.getOutputStream());
            in.close();
        } catch (Exception e) {
            throw new RuntimeException("文件下载错误: " + e.getMessage(), e);
        }
    }

    /**
     * 下载压缩文件
     */
    public void downloadFileToZip(ZipOutputStream zipOut, FileEntity fileEntity,Map<String, Integer> fileNameCounter) {

        InputStream inputStream = null;
        try {
            // 获取文件流
            inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(configuration.getBucketName())
                            .object(fileEntity.getFileObjectName())
                            .build());

            // 创建压缩条目（使用原始文件名）
            String uniqueName = generateUniqueName(fileEntity.getOriginFileName(), fileNameCounter);
            // 计算CRC32校验和
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[4096];
            int len;
            while ((len = inputStream.read(buffer)) > 0) {
                crc.update(buffer, 0, len);
            }

            // 重置输入流
            inputStream.close();
            InputStream dataStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(configuration.getBucketName())
                            .object(fileEntity.getFileObjectName())
                            .build());

            // 创建ZipEntry并设置STORED参数
            ZipEntry zipEntry = new ZipEntry((uniqueName));
            zipEntry.setMethod(ZipEntry.STORED); // 关键：使用STORED模式
            zipEntry.setSize(fileEntity.getSize()); // 文件原始大小
            zipEntry.setCompressedSize(fileEntity.getSize()); // 压缩后大小（同原始）
            zipEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(zipEntry);

            // 写入文件内容
            buffer = new byte[4096];
            while ((len = dataStream.read(buffer)) > 0) {
                zipOut.write(buffer, 0, len);
            }
            zipOut.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException("下载压缩文件失败: " + fileEntity.getOriginFileName(), e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (IOException e) {
                log.error("关闭文件流失败", e);
            }
        }
    }

    // 文件名生成器（关键逻辑）
    public String generateUniqueName(String originName, Map<String, Integer> counter) {
        // 分割文件名和扩展名（兼容无扩展名文件）
        int dotIndex = originName.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? originName.substring(0, dotIndex) : originName;
        String extension = (dotIndex > 0) ? originName.substring(dotIndex) : "";

        // 计算出现次数并生成新文件名
        int count = counter.getOrDefault(originName, 0);
        counter.put(originName, count + 1);

        return (count == 0) ? originName
                : String.format("%s(%d)%s", baseName, count, extension);  // 格式：test(1).jpg[5,8](@ref)
    }
    public long calculateZipTotalSize(List<FileEntity> files) throws Exception {
        long totalSize = 0;
        Map<String, Integer> nameCounter = new HashMap<>();
        // ZIP目录结尾固定22字节
        final long END_SIZE = 22;
        for (FileEntity file : files) {
            String uniqueName = generateUniqueName(file.getOriginFileName(), nameCounter);

            byte[] nameBytes = uniqueName.getBytes(StandardCharsets.UTF_8);
            // 本地文件头大小 = 30 + 文件名长度 + 额外字段（此处为0）
            long localHeaderSize = 30 + nameBytes.length;
            // 中央目录条目大小 = 46 + 文件名长度 + 额外字段（此处为0）
            long centralDirEntrySize = 46 + nameBytes.length;

            totalSize += localHeaderSize + file.getSize() + centralDirEntrySize;
        }
        return totalSize + END_SIZE; // 总大小 = 所有条目 + 目录结尾
    }

    /**
     * 删除文件
     */
    public void delete(String objectName) {
        try {
            minioRun("remove_object", ExternalServiceExecutor.MINIO, () ->
                    minioClient.removeObject(RemoveObjectArgs.builder().bucket(configuration.getBucketName()).object(objectName).build()));

        } catch (Exception e) {
            throw new RuntimeException("删除文件错误: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface MinioOperation<T> {
        T execute() throws Exception;
    }

    @FunctionalInterface
    private interface MinioRunnable {
        void run() throws Exception;
    }
}
