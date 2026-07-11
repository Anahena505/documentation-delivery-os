package com.d2os.intake.attachment;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

/**
 * Bounded, crash-contained text extraction for uploaded attachments (US5, T042, T1-d, research R9).
 *
 * <p>The threat: a hostile upload (zip-bomb, malformed PDF, parser-exploit payload) must never
 * crash the app, hang a request thread, or exhaust the heap — and its raw bytes must never reach a
 * persona. This extractor enforces three containment bounds around Tika's {@link AutoDetectParser}:
 *
 * <ol>
 *   <li><b>Time</b>: the parse runs on a dedicated worker with a hard wall-clock timeout; on
 *       overrun the task is cancelled and the attachment is rejected, so the request thread is
 *       always released.
 *   <li><b>Memory</b>: input is size-capped at upload; extracted output is capped at {@link
 *       #MAX_EXTRACTED_CHARS} via a write-limited handler, bounding heap use regardless of input.
 *   <li><b>Failure</b>: ANY throwable — parse error, or a resource error a bomb might provoke — is
 *       caught and converted to {@link ExtractionException}; the caller marks the attachment {@code
 *       REJECTED} and moves on (default-deny, Principle V). Extraction failure never propagates.
 * </ol>
 *
 * <p><b>Isolation note (honest scope):</b> extraction runs in-process, not in a separate address
 * space. The originally-planned Tika {@code ForkParser} (a child JVM) was not adopted because its
 * API requires a {@code Serializable} {@code ContentHandler} — which the standard body handlers are
 * not — and its classpath propagation is unreliable on JDK 9+, making it fail for every file in
 * this runtime. The bounds above give time/memory/crash containment in-process; true per-parse
 * process isolation is a documented production-hardening follow-up. Critically, the FR-015
 * guarantee that raw content never reaches a persona does <em>not</em> depend on process isolation
 * — it is enforced structurally by the summary-only envelope slot ({@code AttachmentSummaryPort} /
 * {@code ExecutionEnvelopeBuilder}).
 */
@Component
public class SandboxedExtractor {

  /** Extraction failed or timed out; the attachment is rejected without affecting anything else. */
  public static class ExtractionException extends RuntimeException {
    public ExtractionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  // Cap on extracted characters — bounds app-side memory and how much untrusted text ever reaches
  // the summarize pass. Hitting the cap is treated as (benign) truncation, not a rejection.
  private static final int MAX_EXTRACTED_CHARS = 500_000;

  private final AttachmentProperties properties;
  // Bounds the wall-clock wait per extraction; a hung parse can't pin the calling request thread.
  private final ExecutorService extractionExecutor =
      Executors.newCachedThreadPool(
          r -> {
            Thread t = new Thread(r, "attachment-extract");
            t.setDaemon(true);
            return t;
          });

  public SandboxedExtractor(AttachmentProperties properties) {
    this.properties = properties;
  }

  /**
   * Extract plain text from {@code bytes} within the containment bounds. Blocks up to the
   * configured {@code extraction-timeout}. Returns the extracted text (possibly empty for a valid
   * but content-free file, or truncated at the character cap). Throws {@link ExtractionException}
   * on failure/timeout.
   */
  public String extract(byte[] bytes, String contentType) {
    long timeoutMillis = properties.getExtractionTimeout().toMillis();
    Future<String> future = extractionExecutor.submit(() -> parse(bytes, contentType));
    try {
      return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new ExtractionException("extraction timed out after " + timeoutMillis + "ms", e);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new ExtractionException("extraction interrupted", e);
    } catch (Throwable e) { // ExecutionException + any error surfaced from the worker
      throw new ExtractionException("extraction failed: " + rootMessage(e), e);
    }
  }

  private String parse(byte[] bytes, String contentType) {
    BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
    Metadata metadata = new Metadata();
    if (contentType != null) {
      metadata.set(Metadata.CONTENT_TYPE, contentType);
    }
    Parser parser = new AutoDetectParser();
    try (InputStream in = new ByteArrayInputStream(bytes)) {
      parser.parse(in, handler, metadata, new ParseContext());
    } catch (Throwable t) {
      // Hitting the write limit is a benign truncation — return what we captured, don't reject.
      if (isWriteLimitReached(t)) {
        return handler.toString();
      }
      // Everything else (TikaException, SAXException, IOException, resource errors) → reject.
      throw new ExtractionException("parse failed: " + rootMessage(t), t);
    }
    return handler.toString();
  }

  // Match by simple name rather than importing the class: Tika moved WriteLimitReachedException
  // between packages across 2.x, and it is not on this module's compile classpath directly.
  private static boolean isWriteLimitReached(Throwable t) {
    for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
      if ("WriteLimitReachedException".equals(c.getClass().getSimpleName())) return true;
    }
    return false;
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) {
      cur = cur.getCause();
    }
    return cur.getClass().getSimpleName()
        + (cur.getMessage() == null ? "" : ": " + cur.getMessage());
  }

  @PreDestroy
  void shutdown() {
    extractionExecutor.shutdownNow();
  }
}
