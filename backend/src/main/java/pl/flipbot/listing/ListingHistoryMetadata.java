package pl.flipbot.listing;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

public final class ListingHistoryMetadata {

    private static final Set<ListingStatus> HISTORY_STATUSES =
            EnumSet.of(
                    ListingStatus.PURCHASED,
                    ListingStatus.SKIPPED_BY_USER,
                    ListingStatus.UNAVAILABLE,
                    ListingStatus.CONTACT_UNAVAILABLE,
                    ListingStatus.REJECTED,
                    ListingStatus.EXPIRED
            );

    private ListingHistoryMetadata() {
    }

    public static boolean isHistoryListing(Listing listing) {
        return listing != null
                && listing.getBuyCandidateAt() != null
                && listing.getStatus() != null
                && HISTORY_STATUSES.contains(listing.getStatus());
    }

    public static HistoryOutcome effectiveOutcome(Listing listing) {
        if (listing == null) {
            return HistoryOutcome.UNCLASSIFIED;
        }

        if (listing.getHistoryOutcome() != null) {
            return listing.getHistoryOutcome();
        }

        if (listing.getStatus() == ListingStatus.PURCHASED) {
            return HistoryOutcome.PURCHASED;
        }

        if (listing.getStatus() == ListingStatus.SKIPPED_BY_USER) {
            return HistoryOutcome.REJECTED;
        }

        return HistoryOutcome.UNCLASSIFIED;
    }

    public static OfferAssessment effectiveAssessment(Listing listing) {
        return listing == null || listing.getOfferAssessment() == null
                ? OfferAssessment.UNASSESSED
                : listing.getOfferAssessment();
    }

    public static LocalDateTime effectiveHistoryDate(Listing listing) {
        if (listing == null) {
            return null;
        }

        if (listing.getDecisionAt() != null) {
            return listing.getDecisionAt();
        }

        if (listing.getBuyCandidateAt() != null) {
            return listing.getBuyCandidateAt();
        }

        if (listing.getCurrentStepStartedAt() != null) {
            return listing.getCurrentStepStartedAt();
        }

        return listing.getLastFreshDiscoveryAt();
    }


    public static String modelLabel(
            String brand,
            String model
    ) {
        String normalizedBrand = normalize(brand);
        String normalizedModel = normalize(model);

        if (normalizedBrand == null) {
            return normalizedModel;
        }

        if (normalizedModel == null) {
            return normalizedBrand;
        }

        return normalizedBrand + " → " + normalizedModel;
    }

    public static String normalizeLabel(String value) {
        String normalized = normalize(value);

        return normalized == null
                ? null
                : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value
                .trim()
                .replaceAll("\\s+", " ");
    }
}