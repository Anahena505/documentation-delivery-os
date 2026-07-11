package com.d2os.knowledge.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deterministic sensitivity/PII pre-filter (T022, FR-010, research R7). Runs a fixed set of
 * regex detectors — EMAIL, PHONE, ID_NUMBER (IBAN / national-id-ish), CREDENTIAL (api-key / secret
 * shapes) — plus propagation of intake field-level sensitivity tags (T3-a): any caller-supplied
 * sensitive term found in the content is a {@code TAGGED_SENSITIVE} finding sourced {@code
 * INTAKE_TAG:<field>}.
 *
 * <p>Default-deny (T3-c): every detected span is redacted OUT of the candidate's content before the
 * Curator ever sees it. The pre-filter therefore both records {@link PrefilterFinding} rows (the
 * audit trail of what was detected, at their spans in the ORIGINAL content) and rewrites the
 * candidate content with those spans replaced by a fixed {@code [REDACTED:<category>]} marker. The
 * candidate transitions CAPTURED → PREFILTERED in the same transaction.
 *
 * <p>Findings are append-only (V13 REVOKEs UPDATE/DELETE); the content rewrite is an allowed {@code
 * capture_candidate} UPDATE (status transitions + this default-exclusion edit on revision 1).
 */
@Service
public class SensitivityPreFilter {

  /** A detector: a category + a compiled pattern + the {@code PATTERN:<name>} provenance tag. */
  private record Detector(PrefilterFinding.Category category, Pattern pattern, String source) {}

  // Deterministic detectors. Ordering fixed so redaction/finding output is reproducible (SC-005).
  private static final List<Detector> DETECTORS =
      List.of(
          new Detector(
              PrefilterFinding.Category.EMAIL,
              Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
              "PATTERN:email"),
          // E.164-ish / grouped phone numbers: optional +, 7–15 digits with separators.
          new Detector(
              PrefilterFinding.Category.PHONE,
              Pattern.compile("\\+?\\d[\\d\\-\\s().]{6,}\\d"),
              "PATTERN:phone"),
          // IBAN-ish: 2 letters + 2 check digits + 11–30 alphanumerics.
          new Detector(
              PrefilterFinding.Category.ID_NUMBER,
              Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b"),
              "PATTERN:iban"),
          // Credential shapes: api-key / secret / token = <value>, or long opaque secrets.
          new Detector(
              PrefilterFinding.Category.CREDENTIAL,
              Pattern.compile("(?i)(?:api[_-]?key|secret|token|password)\\s*[:=]\\s*\\S+"),
              "PATTERN:credential"));

  private final PrefilterFindingRepository findingRepository;
  private final CaptureCandidateRepository candidateRepository;
  private final PromotionGateService promotionGateService;

  public SensitivityPreFilter(
      PrefilterFindingRepository findingRepository,
      CaptureCandidateRepository candidateRepository,
      PromotionGateService promotionGateService) {
    this.findingRepository = findingRepository;
    this.candidateRepository = candidateRepository;
    this.promotionGateService = promotionGateService;
  }

  /** Overload for the common case with no propagated intake sensitivity terms. */
  @Transactional
  public List<PrefilterFinding> prefilter(UUID candidateId) {
    return prefilter(candidateId, List.of());
  }

  /**
   * Run the pre-filter over a candidate's content: detect + record findings, redact detected spans
   * out of the content by default (T3-c), and transition the candidate CAPTURED → PREFILTERED.
   *
   * @param sensitiveTerms caller-supplied intake field-level sensitivity terms (T3-a). Each
   *     occurrence in the content is recorded as a {@code TAGGED_SENSITIVE} finding and redacted.
   * @return the findings recorded (span offsets are into the ORIGINAL, pre-redaction content).
   */
  @Transactional
  public List<PrefilterFinding> prefilter(UUID candidateId, List<String> sensitiveTerms) {
    CaptureCandidate candidate =
        candidateRepository
            .findById(candidateId)
            .orElseThrow(() -> new NoSuchElementException("candidate " + candidateId));
    String content = candidate.getContent();

    // Collect every detected span first (against the original content), then redact and persist.
    List<Span> spans = new ArrayList<>();
    for (Detector d : DETECTORS) {
      Matcher m = d.pattern().matcher(content);
      while (m.find()) {
        spans.add(new Span(m.start(), m.end(), d.category(), d.source()));
      }
    }
    if (sensitiveTerms != null) {
      for (String term : sensitiveTerms) {
        if (term == null || term.isEmpty()) continue;
        int idx = 0;
        while ((idx = content.indexOf(term, idx)) >= 0) {
          spans.add(
              new Span(
                  idx,
                  idx + term.length(),
                  PrefilterFinding.Category.TAGGED_SENSITIVE,
                  "INTAKE_TAG:" + term));
          idx += term.length();
        }
      }
    }

    List<PrefilterFinding> findings = new ArrayList<>();
    for (Span s : spans) {
      PrefilterFinding finding =
          new PrefilterFinding(
              UUID.randomUUID(),
              candidate.getWorkspaceId(),
              candidateId,
              s.category(),
              s.start(),
              s.end(),
              s.source());
      findingRepository.save(finding);
      findings.add(finding);
    }

    // Redact detected spans out of the content by default (T3-c). Rewrites right-to-left so earlier
    // span offsets stay valid while later ones are replaced. Content + status change persist
    // together
    // through the entity (one save) so a stale JPA write-back cannot clobber a separate content
    // edit.
    String redacted = redact(content, spans);
    candidate.applyPrefilterRedaction(redacted);
    candidateRepository.save(candidate);

    // The pre-filter is the first promotion gate: record its PASS (default-deny — the gate PASSes
    // because the sensitive spans have been excluded, not because none were found).
    promotionGateService.recordPrefilterPass(
        candidate, findings.size() + " finding(s) detected and redacted by default");
    return findings;
  }

  private String redact(String content, List<Span> spans) {
    // Replace each detected span with a stable marker, applying from the rightmost span leftward so
    // the surviving offsets are not shifted by earlier replacements.
    List<Span> ordered = new ArrayList<>(spans);
    ordered.sort((a, b) -> Integer.compare(b.start(), a.start()));
    StringBuilder sb = new StringBuilder(content);
    int lastStart = Integer.MAX_VALUE;
    for (Span s : ordered) {
      // Skip a span that overlaps one already redacted (defensive against overlapping detectors).
      if (s.end() > lastStart) continue;
      sb.replace(s.start(), s.end(), "[REDACTED:" + s.category().name() + "]");
      lastStart = s.start();
    }
    return sb.toString();
  }

  private record Span(int start, int end, PrefilterFinding.Category category, String source) {}
}
