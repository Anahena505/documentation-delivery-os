# DR Drill Results (T046)

**Status: not executed.** See `ops/dr-drill.md`'s own **Results** section for the full explanation —
this delivery sandbox has no persistent Docker Compose stack (only ephemeral Testcontainers, torn down
per test run), so there is no real base backup or WAL archive depth to restore from, and no honest way
to measure RPO/RTO without fabricating numbers.

| Field | Value |
|---|---|
| Date | — |
| Operator | — |
| Target RPO | ≤ 15 min |
| Achieved RPO | not measured |
| Target RTO | ≤ 1 h |
| Achieved RTO | not measured |
| Result | **Drill not run — WAL-archiving config (T044) and runbook (T045) are ready for a real execution in a persistent environment.** |

Findings/shortfalls: none recorded yet, since no drill has run. The first real execution should treat
this table as the template to fill in, and update `ops/dr-drill.md`'s **Sign-off** section alongside it.
