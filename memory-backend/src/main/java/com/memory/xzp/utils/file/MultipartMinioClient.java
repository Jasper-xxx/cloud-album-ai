package com.memory.xzp.utils.file;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.minio.CreateMultipartUploadResponse;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListPartsResponse;
import io.minio.MinioAsyncClient;
import io.minio.ObjectWriteResponse;
import io.minio.http.Method;
import io.minio.messages.Part;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MultipartMinioClient extends MinioAsyncClient {

    public MultipartMinioClient(MinioAsyncClient client) {
        super(client);
    }

    public String createUpload(String bucket, String objectName, String contentType) throws Exception {
        Multimap<String, String> headers = HashMultimap.create();
        headers.put("Content-Type", contentType);
        CreateMultipartUploadResponse response =
                createMultipartUpload(bucket, null, objectName, headers, null);
        return response.result().uploadId();
    }

    public String presignPart(
            String bucket,
            String objectName,
            String uploadId,
            int partNumber,
            int expirySeconds
    ) throws Exception {
        return getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucket)
                        .object(objectName)
                        .extraQueryParams(Map.of(
                                "uploadId", uploadId,
                                "partNumber", String.valueOf(partNumber)
                        ))
                        .expiry(expirySeconds, TimeUnit.SECONDS)
                        .build()
        );
    }

    public List<Part> listUploadedParts(
            String bucket,
            String objectName,
            String uploadId
    ) throws Exception {
        ListPartsResponse response =
                listParts(bucket, null, objectName, null, null, uploadId, null, null);
        return response.result().partList();
    }

    public ObjectWriteResponse completeUpload(
            String bucket,
            String objectName,
            String uploadId,
            List<Part> parts
    ) throws Exception {
        return completeMultipartUpload(
                bucket,
                null,
                objectName,
                uploadId,
                parts.toArray(Part[]::new),
                null,
                null
        );
    }

    public void abortUpload(String bucket, String objectName, String uploadId) throws Exception {
        abortMultipartUpload(bucket, null, objectName, uploadId, null, null);
    }
}
