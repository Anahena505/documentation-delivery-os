package com.d2os.intake.attachment;

/**
 * Boundary-policy rejection at upload (T041, Principle V — default deny): the file was refused before
 * any record was created because it violated the size cap ({@link Reason#OVERSIZE} → HTTP 413) or the
 * content-type allowlist ({@link Reason#DISALLOWED_TYPE} → HTTP 422). Distinct from a post-acceptance
 * extraction failure, which creates a {@code REJECTED} attachment record rather than an HTTP error.
 */
public class AttachmentPolicyException extends RuntimeException {

    public enum Reason { OVERSIZE, DISALLOWED_TYPE }

    private final Reason reason;

    public AttachmentPolicyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
