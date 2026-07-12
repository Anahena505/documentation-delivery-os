package com.d2os.intake.attachment;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload-surface policy (T003, T1-d, Principle V — default deny): the content-type allowlist, the
 * hard size cap, and the sandbox extraction timeout. Bound from {@code d2os.intake.attachment.*}.
 */
@ConfigurationProperties(prefix = "d2os.intake.attachment")
public class AttachmentProperties {

  /**
   * Comma-separated (whitespace/newline tolerant) list of allowed content types. Nothing else
   * uploads.
   */
  private String allowlist = "";

  private long maxSizeBytes = 20L * 1024 * 1024;
  private Duration extractionTimeout = Duration.ofSeconds(60);

  public Set<String> allowedContentTypes() {
    Set<String> types = new LinkedHashSet<>();
    for (String raw : allowlist.split(",")) {
      String t = raw.strip();
      if (!t.isEmpty()) types.add(t);
    }
    return types;
  }

  public boolean isAllowed(String contentType) {
    return contentType != null && allowedContentTypes().contains(contentType.strip());
  }

  public String getAllowlist() {
    return allowlist;
  }

  public void setAllowlist(String allowlist) {
    this.allowlist = allowlist == null ? "" : allowlist;
  }

  public long getMaxSizeBytes() {
    return maxSizeBytes;
  }

  public void setMaxSizeBytes(long maxSizeBytes) {
    this.maxSizeBytes = maxSizeBytes;
  }

  public Duration getExtractionTimeout() {
    return extractionTimeout;
  }

  public void setExtractionTimeout(Duration extractionTimeout) {
    this.extractionTimeout = extractionTimeout;
  }

  // Retained for callers/tests that want the raw parsed set without instance state.
  public static Set<String> parse(String csv) {
    AttachmentProperties p = new AttachmentProperties();
    p.setAllowlist(csv);
    return new LinkedHashSet<>(Arrays.asList(p.allowedContentTypes().toArray(new String[0])));
  }
}
