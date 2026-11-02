package pl.flipbot.marketstats;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketListingObservationRepository
        extends JpaRepository<MarketListingObservation, Long>, JpaSpecificationExecutor<MarketListingObservation> {

    interface PublicationCounts {
        Long getModelId();
        long getToday();
        long getCurrentWeek();
        long getPreviousWeek();
        long getSinceBaseline();
    }

    @Query(value = """
            select observation.model_id as "modelId",
                   count(*) filter (where observation.published_at >= :today) as "today",
                   count(*) filter (where observation.published_at >= :currentWeek) as "currentWeek",
                   count(*) filter (where observation.published_at >= :previousWeek
                                      and observation.published_at < :currentWeek) as "previousWeek",
                   count(*) filter (where observation.published_at >= state.baseline_complete_at) as "sinceBaseline"
            from market_listing_observation observation
            join market_model_scan_state state on state.model_id = observation.model_id
            where observation.tracking_generation = greatest(coalesce(state.tracking_generation, 1), 1)
              and state.baseline_complete_at is not null
              and observation.published_at >= least(state.baseline_complete_at, :previousWeek)
              and observation.published_at < :now
            group by observation.model_id
            """, nativeQuery = true)
    List<PublicationCounts> countPlanningWindows(
            @Param("today") LocalDateTime today,
            @Param("currentWeek") LocalDateTime currentWeek,
            @Param("previousWeek") LocalDateTime previousWeek,
            @Param("now") LocalDateTime now
    );

    List<MarketListingObservation>
    findAllByModel_IdAndTrackingGenerationAndMarketplaceListingIdIn(
            Long modelId,
            Integer trackingGeneration,
            Collection<String> marketplaceListingIds
    );

    List<MarketListingObservation>
    findAllByModel_IdAndTrackingGenerationAndPublishedAtIsNotNull(
            Long modelId,
            Integer trackingGeneration
    );

    @Query(value = """
            select observation.marketplace_listing_id
            from market_listing_observation observation
            where observation.model_id = :modelId
              and observation.tracking_generation = :trackingGeneration
              and observation.published_at is null
            order by observation.last_seen_at desc
            """, nativeQuery = true)
    List<String> findListingIdsMissingPublishedAt(
            @Param("modelId") Long modelId,
            @Param("trackingGeneration") Integer trackingGeneration
    );

    @Modifying
    @Query(value = """
            update market_listing_observation
            set published_at = :publishedAt
            where model_id = :modelId
              and tracking_generation = :trackingGeneration
              and marketplace_listing_id = :listingId
            """, nativeQuery = true)
    int updatePublishedAt(
            @Param("modelId") Long modelId,
            @Param("trackingGeneration") Integer trackingGeneration,
            @Param("listingId") String listingId,
            @Param("publishedAt") LocalDateTime publishedAt
    );

    long countByModel_IdAndTrackingGenerationAndBaselineTrue(
            Long modelId,
            Integer trackingGeneration
    );

    long deleteByModel_Id(
            Long modelId
    );

    long deleteByLastSeenAtBefore(
            LocalDateTime cutoff
    );

    @Query("""
            select observation.marketplaceListingId
            from MarketListingObservation observation
            where observation.model.id = :modelId
              and observation.trackingGeneration = :trackingGeneration
              and observation.lastSeenAt >= :cutoff
            order by observation.lastSeenAt desc
            """)
    List<String> findKnownListingIds(
            @Param("modelId") Long modelId,
            @Param("trackingGeneration") Integer trackingGeneration,
            @Param("cutoff") LocalDateTime cutoff
    );
}
