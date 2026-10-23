package com.j2s;

import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionConstraint;
import org.eclipse.aether.version.VersionRange;

/**
 * Nearest-wins version selector that treats single-version ranges as soft requirements.
 *
 * <p>AndroidX publishes Gradle "strict" constraints as singleton ranges in its POMs, e.g.
 * {@code <version>[2.5.1]</version>}. Gradle treats that as "strictly 2.5.1 but a higher version
 * may still satisfy other requirements"; Maven Resolver's default {@code NearestVersionSelector}
 * treats it as a hard Maven range and fails the whole resolution with
 * {@code Could not resolve version conflict} when another dependency requires e.g. 2.4.0.
 *
 * <p>This selector keeps normal ranges (e.g. {@code [1.0,2.0)}) as hard constraints, but ignores
 * singleton ranges ({@code [x]} / {@code [x,x]}) when picking the winner. If no candidate
 * satisfies the remaining hard constraints it falls back to plain nearest-wins instead of
 * aborting the build.
 */
final class RelaxedVersionSelector extends ConflictResolver.VersionSelector {

    @Override
    public void selectVersion(ConflictResolver.ConflictContext context) throws RepositoryException {
        ConflictResolver.ConflictItem winner = null;

        for (ConflictResolver.ConflictItem item : context.getItems()) {
            if (!satisfiesHardRanges(item)) continue;
            if (winner == null || isNearer(item, winner)) winner = item;
        }

        if (winner == null) {
            // No candidate satisfies every real range (AndroidX singleton ranges are already
            // ignored); fall back to nearest-wins rather than failing the build.
            for (ConflictResolver.ConflictItem item : context.getItems()) {
                if (winner == null || isNearer(item, winner)) winner = item;
            }
        }
        context.setWinner(winner);
    }

    private static boolean satisfiesHardRanges(ConflictResolver.ConflictItem item) {
        DependencyNode node = item.getNode();
        VersionConstraint constraint = node.getVersionConstraint();
        if (constraint == null || constraint.getRange() == null) return true;
        if (isSingletonRange(constraint.getRange())) return true;
        Version version = node.getVersion();
        return version != null && constraint.containsVersion(version);
    }

    /** {@code [x]} or {@code [x,x]} — the way Gradle strict versions are published to POMs. */
    private static boolean isSingletonRange(VersionRange range) {
        VersionRange.Bound lower = range.getLowerBound();
        VersionRange.Bound upper = range.getUpperBound();
        if (lower == null || upper == null) return false;
        if (!lower.isInclusive() || !upper.isInclusive()) return false;
        Version lowerVersion = lower.getVersion();
        Version upperVersion = upper.getVersion();
        return lowerVersion != null && upperVersion != null
                && lowerVersion.compareTo(upperVersion) == 0;
    }

    private static boolean isNearer(ConflictResolver.ConflictItem a,
                                    ConflictResolver.ConflictItem b) {
        if (a.isSibling(b)) {
            Version va = a.getNode().getVersion();
            Version vb = b.getNode().getVersion();
            if (va == null || vb == null) return false;
            return va.compareTo(vb) > 0;
        }
        return a.getDepth() < b.getDepth();
    }
}
