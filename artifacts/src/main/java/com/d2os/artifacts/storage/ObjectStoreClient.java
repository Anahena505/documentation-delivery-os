package com.d2os.artifacts.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Thin S3-API wrapper (T008). Points at AWS S3 in prod, MinIO in dev/test (endpoint override via
 * {@code d2os.storage.endpoint}, configured in {@code S3ClientConfig}). Callers are responsible for
 * computing content hashes (R8) — this class only moves bytes.
 */
@Component
public class ObjectStoreClient {

  private final S3Client s3;
  private final String bucket;

  public ObjectStoreClient(S3Client s3, StorageProperties properties) {
    this.s3 = s3;
    this.bucket = properties.bucket();
  }

  public void put(String key, byte[] content, String contentType) {
    s3.putObject(
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
        RequestBody.fromBytes(content));
  }

  public byte[] get(String key) {
    try (InputStream in =
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      in.transferTo(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException("Failed reading object " + key, e);
    }
  }
}
