-- 008 US5 (T049): per-user accountability on audit entries (data-model.md §2, Principle V "who decided").
--
-- actor_user_id = the IdP `sub` of the authenticated individual who made a trust-sensitive decision;
-- actor_role    = the role they were authorized under (must be one they hold).
--
-- Both NULLABLE and additive: pre-migration rows, and decisions made in the default (non-OIDC)
-- workspace-scoping posture, remain valid with NULL actors. OIDC-authenticated decisions populate both
-- (service layer). Distinct from the pre-existing generic `actor` column, which records the acting
-- component/persona rather than an authenticated end user.
--
-- Populating these into the tamper-evident hash chain (AuditChainCanonicalizer) and enforcing that
-- actor_role is one the principal holds are the DEFERRED cutover steps (T050/T051) — see
-- OidcSecurityConfig's javadoc — because they change the audit-hash contract that the existing
-- integration suites assert and cannot be verified without a Docker-capable IT run.

ALTER TABLE audit_entry ADD COLUMN actor_user_id TEXT;
ALTER TABLE audit_entry ADD COLUMN actor_role TEXT;
