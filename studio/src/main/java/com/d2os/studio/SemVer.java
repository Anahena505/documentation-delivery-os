package com.d2os.studio;

/**
 * Minimal MAJOR.MINOR.PATCH semver parsing/comparison (tasks.md T016/T017, FR-006/007/018). No
 * semver utility exists elsewhere in the repo yet — {@code DefinitionResolutionService}'s own
 * javadoc flags its "select the highest published version lexically... semver-aware ordering is a
 * refinement" gap as still open. This class is scoped to exactly what publish governance needs:
 * strict ordering enforcement against the prior published version (FR-006/018) and MAJOR-bump
 * detection for the second architecture-board gate (FR-007) — every version this codebase seeds
 * or accepts through the studio is a plain {@code X.Y.Z} (see {@code CatalogSeedLoader},
 * {@code StudioAuthoringIT}); pre-release/build-metadata suffixes are rejected outright
 * ({@link IllegalArgumentException}, surfaced by {@code PublishService} as a 409 rather than
 * silently truncated or mis-ordered).
 */
final class SemVer implements Comparable<SemVer> {

    private final int major;
    private final int minor;
    private final int patch;

    private SemVer(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    static SemVer parse(String version) {
        String[] parts = version == null ? new String[0] : version.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected MAJOR.MINOR.PATCH semver, got: " + version);
        }
        try {
            return new SemVer(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected MAJOR.MINOR.PATCH semver, got: " + version, e);
        }
    }

    @Override
    public int compareTo(SemVer other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }

    /** {@code compare(next, prior) <= 0} means {@code next} is not a valid successor (FR-006/018). */
    static int compare(String v1, String v2) {
        return parse(v1).compareTo(parse(v2));
    }

    /** True iff {@code next}'s MAJOR component is strictly greater than {@code prior}'s (FR-007). */
    static boolean isMajorBump(String next, String prior) {
        return parse(next).major > parse(prior).major;
    }
}
