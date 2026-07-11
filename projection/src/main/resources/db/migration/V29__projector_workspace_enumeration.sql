-- V29 Workspace enumeration for the projector's cross-tenant sweep (Phase 7 Phase 3, T008/T009).
--
-- The Projector/RebuildJob are background jobs that must visit EVERY active workspace on each run
-- (research R4; tasks.md T008/T009's "for each workspace..."), the same per-workspace sweep shape
-- ReconciliationJob/CaseDeliveredKnowledgeTrigger (orchestration) already use. Those two jobs
-- discover their per-workspace work items from Flowable's engine tables, which carry NO RLS at
-- all — the projector has no engine-table equivalent (it consumes event_outbox + source tables
-- directly, every one of them RLS-scoped per V2/V4/V28).
--
-- Neither d2os_app nor d2os_projector can list `workspace` rows across tenants: V2's `ws_self`
-- policy only ever admits the row matching the ALREADY-bound `app.workspace_id` (or the
-- system-global row) — by design, RLS gives no tenant-enumeration escape hatch for an ordinary
-- SELECT, no matter what value is bound. A background sweep that has not yet bound any workspace
-- cannot ask "which workspaces exist" through the normal RLS-protected path.
--
-- Rather than a blanket RLS bypass (BYPASSRLS on either runtime role, which would silently defeat
-- RLS for every OTHER table those roles can already touch — a large, hard-to-audit security
-- regression), this adds exactly ONE narrowly-scoped SECURITY DEFINER function that returns only
-- the `id` column of `workspace` rows with status = 'active'. A SECURITY DEFINER function runs
-- with the privileges of its OWNER (the migration/table-owner role, d2os_owner — see
-- spring.flyway.user in application.yml), which — like every table owner — is exempt from RLS on
-- tables it owns. Granting EXECUTE on this one function to d2os_app/d2os_projector lets them
-- enumerate workspace ids to sweep without granting them, or any query they run, RLS bypass on
-- `workspace` or any other table: the function is the ENTIRE bypass surface, and it returns
-- nothing but a list of ids.
CREATE FUNCTION list_active_workspace_ids() RETURNS TABLE(id uuid)
LANGUAGE sql SECURITY DEFINER SET search_path = public AS $$
    SELECT w.id FROM workspace w WHERE w.status = 'active';
$$;

GRANT EXECUTE ON FUNCTION list_active_workspace_ids() TO d2os_app, d2os_projector;
