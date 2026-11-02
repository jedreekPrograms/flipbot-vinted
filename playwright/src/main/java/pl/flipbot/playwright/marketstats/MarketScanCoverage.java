package pl.flipbot.playwright.marketstats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MarketScanCoverage {
    private static final int ABSOLUTE_MAX_LISTINGS_PER_MODEL = 5_000;

    private static final int DYNAMIC_LIMIT_MULTIPLIER = 4;

    private MarketScanCoverage() {}

    static int dynamicMaxListingsPerModel(int configuredLimit) {
        if (configuredLimit <= 0) {
            throw new IllegalArgumentException(
                    "Configured market-statistics listing limit must be positive."
            );
        }

        long expanded = (long) configuredLimit * DYNAMIC_LIMIT_MULTIPLIER;

        return (int) Math.min(ABSOLUTE_MAX_LISTINGS_PER_MODEL, Math.max(configuredLimit, expanded));
    }

    static LocalDateTime earliestRelevantPublicationAt(LocalDate marketToday) {
        if (marketToday == null) {
            throw new IllegalArgumentException("Market date cannot be null.");
        }

        LocalDate currentWeekStart = marketToday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        return currentWeekStart
                .minusWeeks(1)
                .atStartOfDay();
    }

    static boolean hasHistoricalPublicationBoundary(
            List<String> orderedListingIds,
            Map<String, LocalDateTime> publishedAtByListingId,
            LocalDateTime earliestRelevantPublishedAt,
            int requiredConsecutiveOld
    ) {
        if (orderedListingIds == null
                || orderedListingIds.isEmpty()
                || publishedAtByListingId == null
                || publishedAtByListingId.isEmpty()
                || earliestRelevantPublishedAt == null
                || requiredConsecutiveOld <= 0) {
            return false;
        }

        int consecutiveOld = 0;

        for (int index = orderedListingIds.size() - 1; index >= 0; index--) {
            String listingId = orderedListingIds.get(index);

            if (listingId == null || listingId.isBlank()) {
                return false;
            }

            LocalDateTime publishedAt = publishedAtByListingId.get(listingId.trim());

            if (publishedAt == null || !publishedAt.isBefore(earliestRelevantPublishedAt)) {
                return false;
            }

            consecutiveOld++;

            if (consecutiveOld >= requiredConsecutiveOld) {
                return true;
            }
        }

        return false;
    }

    static boolean containsKnownBoundary(
            Collection<String> orderedListingIds,
            Set<String> knownListingIds,
            int requiredConsecutiveKnown
    ) {
        int consecutiveKnown = 0;

        for (String listingId : orderedListingIds) {
            if (knownListingIds.contains(listingId)) {
                consecutiveKnown++;

                if (consecutiveKnown >= requiredConsecutiveKnown) {
                    return true;
                }
            } else {
                consecutiveKnown = 0;
            }
        }

        return false;
    }
}
