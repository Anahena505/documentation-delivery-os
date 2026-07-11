/**
 * Catalog Studio (Phase 6, the 13th module — plan.md Project Structure). Presentation-only:
 * server-rendered Thymeleaf pages + htmx partials over the {@code catalog}/{@code governance}/
 * {@code casecore} services. All catalog semantics (draft, publish, fork, deprecate, subscribe)
 * live as services in {@code catalog} so they stay API-testable without this UI layer — an
 * ArchUnit rule (Phase 8, T031) later enforces that those services take no dependency back on
 * this package.
 *
 * <p>Controllers/editors/pages land in later tasks (Phase 3+, tasks.md): {@code DraftController}
 * (T008), {@code editor/} typed-slot models + the dmn-js bridge (T009/T010), the Thymeleaf pages
 * (T011), {@code PublishController} (T013/T018), {@code LifecycleController} (T023), {@code
 * SubscriptionController} (T026). This package-info exists only so Gradle/Java recognize the
 * module before any of that code lands (T001).
 */
package com.d2os.studio;
