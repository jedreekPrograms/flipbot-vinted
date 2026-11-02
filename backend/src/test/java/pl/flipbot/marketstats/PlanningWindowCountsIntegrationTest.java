package pl.flipbot.marketstats;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PlanningWindowCountsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MarketListingObservationRepository observations;

    @Test void batchedCountsPreserveCalendarBoundariesBaselineAndGeneration() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 12, 0);
        var windows = MarketStatsPlanningCalculator.windows(now);
        LocalDateTime baseline = now.minusDays(30);
        jdbc.update("insert into dictionary_brand(id, name) values (-8801, 'counts fixture')");
        jdbc.update("insert into dictionary_model(id, brand_id, name, target_mode) values (-8801, -8801, 'fixture', 'VINTED_MODEL')");
        jdbc.update("""
                insert into market_model_scan_state(model_id, tracking_generation, initialized_at,
                    last_scan_at, last_scan_complete, baseline_complete_at)
                values (-8801, 2, ?, ?, true, ?)
                """, baseline, now, baseline);
        observation("today", 2, windows.todayStart());
        observation("this-week", 2, windows.currentWeekStart());
        observation("previous-week", 2, windows.previousWeekStart());
        observation("baseline", 2, baseline);
        observation("old-generation", 1, windows.todayStart());
        observation("future", 2, now.plusSeconds(1));
        observation("unknown-time", 2, null);
        observation("before-tracking", 2, baseline.minusSeconds(1));

        var counts = observations.countPlanningWindows(windows.todayStart(), windows.currentWeekStart(), windows.previousWeekStart(), now)
                .stream().filter(row -> row.getModelId().equals(-8801L)).findFirst().orElseThrow();
        assertEquals(1L, counts.getToday());
        assertEquals(2L, counts.getCurrentWeek());
        assertEquals(1L, counts.getPreviousWeek());
        assertEquals(4L, counts.getSinceBaseline());
    }

    private void observation(String id, int generation, LocalDateTime published) {
        jdbc.update("""
                insert into market_listing_observation(model_id, marketplace_listing_id, tracking_generation,
                    baseline, first_seen_at, last_seen_at, published_at)
                values (-8801, ?, ?, false, current_timestamp, current_timestamp, ?)
                """, id, generation, published);
    }
}
