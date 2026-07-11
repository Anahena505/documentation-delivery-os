package com.d2os.app.support;

import com.d2os.catalog.DefinitionPublishService;
import com.d2os.catalog.DraftService;

import java.util.List;
import java.util.UUID;

/**
 * Seeds a realistic-scale catalog for {@code ResolutionBenchmarkIT} (T029, US5, research R7,
 * NFR-9): 500 Published versions across the 8 definition types, through the REAL seed path —
 * {@link DraftService#create} then {@link DefinitionPublishService#publish} — the same two-step
 * Draft-then-Published sequence any real author's content goes through (not a raw INSERT), so the
 * benchmark measures resolution against genuinely realistic rows.
 */
public final class BenchmarkSeeder {

    private static final List<String> TYPES = List.of(
            "case_type", "workflow", "template", "rule", "rubric", "prompt", "persona", "SUBPROCESS");

    private BenchmarkSeeder() {}

    /**
     * Seeds {@code totalVersions} rows spread across {@link #TYPES} and {@code keysPerType} distinct
     * keys, each key getting a run of sequential {@code X.0.0} versions (a realistic distribution —
     * most keys have a handful of versions, not one each) — so resolution has real "pick the latest
     * of several" work to do, not just "look up the only row."
     */
    public static void seed(DraftService draftService, DefinitionPublishService publishService,
                            UUID workspaceId, int totalVersions, int keysPerType) {
        int perTypeTarget = totalVersions / TYPES.size();
        for (String type : TYPES) {
            int seeded = 0;
            int keyIndex = 0;
            while (seeded < perTypeTarget) {
                String key = type + "-bench-" + keyIndex;
                int versionsForThisKey = Math.min(perTypeTarget - seeded, 1 + (keyIndex % 5));
                for (int v = 1; v <= versionsForThisKey; v++) {
                    String version = v + ".0.0";
                    var draft = draftService.create(type, key, version,
                            "{\"name\":\"" + key + " v" + version + "\",\"benchmarkSeed\":true}",
                            workspaceId, "benchmark-seeder");
                    publishService.publish(draft.getId());
                    seeded++;
                    if (seeded >= perTypeTarget) break;
                }
                keyIndex++;
            }
        }
    }
}
