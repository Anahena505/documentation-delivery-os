package com.d2os.artifacts.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/** Builds the {@link S3Client} bean, pointed at MinIO when an endpoint override is set (T008). */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)   // required for MinIO
                        .build());

        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        if (properties.accessKey() != null && properties.secretKey() != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())));
        }
        return builder.build();
    }
}
