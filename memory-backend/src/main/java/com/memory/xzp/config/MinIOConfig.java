package com.memory.xzp.config;

import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import com.memory.xzp.utils.file.MultipartMinioClient;
import lombok.Data;
import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * @description:
 * @author: xzp
 * @date: 2025/2/10,21:30
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinIOConfig {
    private String accessKey;
    private String secretKey;
    private String url;
    private String bucketName;
    private long connectTimeoutSeconds = 5;
    private long readTimeoutSeconds = 30;
    private long writeTimeoutSeconds = 30;
    private long callTimeoutSeconds = 60;

    @Bean
    public MinioClient minioClient(){
        return MinioClient.builder()
                .endpoint(url)
                .credentials(accessKey,secretKey)
                .httpClient(minioHttpClient())
                .build();
    }

    @Bean
    public MultipartMinioClient multipartMinioClient(){
        MinioAsyncClient client = MinioAsyncClient.builder()
                .endpoint(url)
                .credentials(accessKey,secretKey)
                .httpClient(minioHttpClient())
                .build();
        return new MultipartMinioClient(client);
    }

    private OkHttpClient minioHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }
}
