package com.d2os.artifacts.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code d2os.storage.*} (endpoint, bucket, credentials) for MinIO/S3 (T008). */
@ConfigurationProperties(prefix = "d2os.storage")
public record StorageProperties(
        String endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey
) {
    public StorageProperties {
        if (bucket == null) bucket = "d2os-artifacts";
        if (region == null) region = "us-east-1";
    }
}
