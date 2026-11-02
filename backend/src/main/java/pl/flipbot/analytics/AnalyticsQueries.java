package pl.flipbot.analytics;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import pl.flipbot.listing.Listing;
import pl.flipbot.marketstats.MarketListingObservation;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** SQL preselection only; legacy model mapping, outcomes and coverage remain in the loader. */
final class AnalyticsQueries {
    private AnalyticsQueries() {}

    static Specification<Listing> history(AnalyticsTimeRange range) {
        return (listing, query, cb) -> cb.and(
                cb.isNotNull(listing.get("buyCandidateAt")),
                cb.isFalse(listing.get("historyHidden")),
                within(cb, cb.<LocalDateTime>coalesce(listing.get("decisionAt"), listing.get("buyCandidateAt")), range));
    }

    static Specification<MarketListingObservation> market(Set<Long> modelIds, AnalyticsTimeRange range) {
        return (observation, query, cb) -> {
            Predicate dates = within(cb, observation.get("publishedAt"), range);
            return modelIds.isEmpty() ? dates : cb.and(dates, observation.get("model").get("id").in(modelIds));
        };
    }

    private static Predicate within(CriteriaBuilder cb, Expression<LocalDateTime> date, AnalyticsTimeRange range) {
        // timestamp(6) rows have microsecond precision. Floor the inclusive upper bound
        // so the JDBC driver's rounding cannot accidentally include midnight of the next day.
        Predicate upper = cb.lessThanOrEqualTo(date, range.to().truncatedTo(ChronoUnit.MICROS));
        // ALL has no lower bound. LocalDateTime.MIN cannot be represented by PostgreSQL.
        return LocalDateTime.MIN.equals(range.from()) ? upper
                : cb.and(cb.greaterThanOrEqualTo(date, range.from()), upper);
    }
}
