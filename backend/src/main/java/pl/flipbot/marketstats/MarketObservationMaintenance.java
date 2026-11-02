package pl.flipbot.marketstats;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Retention is maintenance, independent of catalog ingestion batch frequency. */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class MarketObservationMaintenance {
    private final MarketListingObservationRepository observations;

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT24H")
    @Transactional
    public void removeExpiredObservations() {
        long removed = observations.deleteByLastSeenAtBefore(LocalDateTime.now().minusDays(400));
        if (removed > 0) log.info("[MARKET STATS] Removed {} expired observations", removed);
    }
}
