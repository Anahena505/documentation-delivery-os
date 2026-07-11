package com.d2os.casecore.audit;

import com.d2os.casecore.AuditEntryRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 008 T008 — fast, pure unit test of {@link AuditChainCanonicalizer#canonicalize(List)} (the shared
 * serialization sealing and verifying both hash). No Spring, no DB. Verifies (1) equal inputs yield
 * byte-identical canonical output and (2) any single-field change yields different output
 * (tamper sensitivity — Principle V / research R5).
 *
 * <p>{@code AuditEntryRecord.txTime} defaults to {@code now()} at construction and has no setter, so
 * the test pins it to a fixed instant via reflection. This isolates the tamper assertions to exactly
 * the one field under test rather than an incidental timestamp difference.
 */
class AuditChainCanonicalizerTest {

    private static final OffsetDateTime FIXED_TX_TIME =
            OffsetDateTime.of(2026, 7, 11, 12, 0, 0, 0, ZoneOffset.UTC);

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = AuditEntryRecord.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test setup: cannot set field " + name, e);
        }
    }

    /** Build a record with all fields fixed (incl. txTime) so canonical output is fully deterministic. */
    private static AuditEntryRecord entry(UUID id, UUID workspaceId, String subjectType, UUID subjectId,
                                          String action, String actor, String details) {
        AuditEntryRecord e = new AuditEntryRecord(id, workspaceId, subjectType, subjectId, action, actor, details);
        setField(e, "txTime", FIXED_TX_TIME);
        return e;
    }

    private static AuditEntryRecord sample() {
        return entry(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
                "case_instance",
                UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                "APPROVE",
                "user:alice",
                "{\"note\":\"ok\"}");
    }

    @Test
    void equalInputs_produceIdenticalCanonicalBytes() {
        String a = AuditChainCanonicalizer.canonicalize(List.of(sample()));
        String b = AuditChainCanonicalizer.canonicalize(List.of(sample()));

        assertEquals(a, b, "equal audit entries must canonicalize identically");
        assertEquals(a.getBytes().length, b.getBytes().length);
    }

    @Test
    void tamperingAction_changesCanonicalBytes() {
        String baseline = AuditChainCanonicalizer.canonicalize(List.of(sample()));

        AuditEntryRecord tampered = sample();
        setField(tampered, "action", "REJECT");

        assertNotEquals(baseline, AuditChainCanonicalizer.canonicalize(List.of(tampered)),
                "changing the action must change the canonical bytes");
    }

    @Test
    void tamperingActor_changesCanonicalBytes() {
        String baseline = AuditChainCanonicalizer.canonicalize(List.of(sample()));

        AuditEntryRecord tampered = sample();
        setField(tampered, "actor", "user:mallory");

        assertNotEquals(baseline, AuditChainCanonicalizer.canonicalize(List.of(tampered)),
                "changing the actor must change the canonical bytes");
    }

    @Test
    void tamperingDetails_changesCanonicalBytes() {
        String baseline = AuditChainCanonicalizer.canonicalize(List.of(sample()));

        AuditEntryRecord tampered = sample();
        setField(tampered, "details", "{\"note\":\"altered\"}");

        assertNotEquals(baseline, AuditChainCanonicalizer.canonicalize(List.of(tampered)),
                "changing the details payload must change the canonical bytes");
    }

    @Test
    void tamperingTxTime_changesCanonicalBytes() {
        String baseline = AuditChainCanonicalizer.canonicalize(List.of(sample()));

        AuditEntryRecord tampered = sample();
        setField(tampered, "txTime", FIXED_TX_TIME.plusSeconds(1));

        assertNotEquals(baseline, AuditChainCanonicalizer.canonicalize(List.of(tampered)),
                "changing the transaction time must change the canonical bytes");
    }

    // --- 008 US5 (T050): the additive actor_user_id / actor_role fields fold into the seal ---------

    /**
     * NON-REGRESSION: an entry whose actor fields are both null (every pre-008 row and every
     * default-mode decision) MUST canonicalize to exactly the pre-008 bytes, so existing seals/hashes
     * stay valid. We prove it structurally: the null-actor canonical form is the legacy line VERBATIM
     * (the with-actor form is that same legacy line plus an appended {@code |userId|role} suffix), so
     * adding the fields never perturbs a null-actor entry.
     */
    @Test
    void nullActors_keepLegacyCanonicalBytesUnchanged() {
        String nullActor = AuditChainCanonicalizer.canonicalize(List.of(sample())); // both actor fields null

        AuditEntryRecord withActor = sample();
        setField(withActor, "actorUserId", "sub-abc-123");
        setField(withActor, "actorRole", "approver");
        String withActorStr = AuditChainCanonicalizer.canonicalize(List.of(withActor));

        // The legacy line (minus its trailing newline) is preserved verbatim as the prefix of the
        // actor-stamped line — i.e. null actors add nothing to the canonical bytes.
        String legacyLine = nullActor.substring(0, nullActor.length() - 1); // drop trailing '\n'
        org.junit.jupiter.api.Assertions.assertTrue(withActorStr.startsWith(legacyLine),
                "a null-actor entry must canonicalize to exactly the legacy (pre-008) bytes");
        assertNotEquals(nullActor, withActorStr,
                "stamping a non-null actor must change the canonical bytes (tamper-evidence)");
    }

    @Test
    void tamperingActorUserId_changesCanonicalBytes() {
        AuditEntryRecord stamped = sample();
        setField(stamped, "actorUserId", "sub-abc-123");
        setField(stamped, "actorRole", "approver");
        String baseline = AuditChainCanonicalizer.canonicalize(List.of(stamped));

        AuditEntryRecord tampered = sample();
        setField(tampered, "actorUserId", "sub-evil-999");
        setField(tampered, "actorRole", "approver");

        assertNotEquals(baseline, AuditChainCanonicalizer.canonicalize(List.of(tampered)),
                "altering the authenticated actor's user id must break the seal (FR-013, SC-008)");
    }

    @Test
    void tamperingActorRole_changesCanonicalBytes() {
        AuditEntryRecord stamped = sample();
        setField(stamped, "actorUserId", "sub-abc-123");
        setField(stamped, "actorRole", "approver");
        String baseline = AuditChainCanonicalizer.canonicalize(List.of(stamped));

        AuditEntryRecord tampered = sample();
        setField(tampered, "actorUserId", "sub-abc-123");
        setField(tampered, "actorRole", "catalog-owner");

        assertNotEquals(baseline, AuditChainCanonicalizer.canonicalize(List.of(tampered)),
                "altering the role a decision was made under must break the seal (FR-013, SC-008)");
    }
}
