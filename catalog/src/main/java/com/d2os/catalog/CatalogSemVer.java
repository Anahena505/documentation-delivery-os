package com.d2os.catalog;

/**
 * Minimal MAJOR.MINOR.PATCH semver parsing/comparison for {@code catalog} (T022, research R5,
 * FR-011). Duplicated from {@code studio.SemVer} rather than shared (that class is package-private
 * to {@code studio} anyway, and catalog cannot depend on studio — see {@code
 * DefinitionAsset#markPublishedFromReview}'s own javadoc for why the module edge runs the other way)
 * — same "a ~20-line pure function doesn't justify a cross-module dependency" precedent {@code
 * HashUtil} establishes repeatedly in this codebase.
 */
final class CatalogSemVer implements Comparable<CatalogSemVer> {

    private final int major;
    private final int minor;
    private final int patch;

    private CatalogSemVer(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    static CatalogSemVer parse(String version) {
        String[] parts = version == null ? new String[0] : version.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("expected MAJOR.MINOR.PATCH semver, got: " + version);
        }
        try {
            return new CatalogSemVer(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected MAJOR.MINOR.PATCH semver, got: " + version, e);
        }
    }

    @Override
    public int compareTo(CatalogSemVer other) {
        if (major != other.major) return Integer.compare(major, other.major);
        if (minor != other.minor) return Integer.compare(minor, other.minor);
        return Integer.compare(patch, other.patch);
    }
}
