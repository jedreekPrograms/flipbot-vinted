package pl.flipbot.playwright.worker;

import org.junit.Test;

import static org.junit.Assert.*;

public class BotScheduleTest {
    @Test public void negotiationsWinTiesAndCatalogRunsAfterNegotiationsAreRescheduled() {
        BotSchedule schedule = new BotSchedule(true, 1000L);
        assertEquals(ScheduledJobType.NEGOTIATION_CHECK, schedule.nextJob().jobType());
        schedule.complete(ScheduledJobType.NEGOTIATION_CHECK, 1000L, 500L, false);
        assertEquals(ScheduledJobType.CATALOG_SCAN, schedule.nextJob().jobType());
        schedule.complete(ScheduledJobType.CATALOG_SCAN, 1000L, 500L, false);
        assertEquals(new BotSchedule.NextJob(ScheduledJobType.NEGOTIATION_CHECK, 1500L), schedule.nextJob());
    }

    @Test public void allJobCooldownRetainsLongerExistingDeadlines() {
        BotSchedule schedule = new BotSchedule(true, 1000L);
        schedule.complete(ScheduledJobType.CATALOG_SCAN, 1000L, 5000L, false);
        schedule.complete(ScheduledJobType.NEGOTIATION_CHECK, 1000L, 2000L, true);
        assertEquals(new BotSchedule.NextJob(ScheduledJobType.NEGOTIATION_CHECK, 3000L), schedule.nextJob());
        schedule.complete(ScheduledJobType.NEGOTIATION_CHECK, 3000L, 5000L, false);
        assertEquals(new BotSchedule.NextJob(ScheduledJobType.CATALOG_SCAN, 6000L), schedule.nextJob());
    }

    @Test public void staleTaskCannotClaimAReplacedQueueEntry() {
        BotSchedule schedule = new BotSchedule(false, 1000L);
        ScheduledBotTask old = new ScheduledBotTask(3L, ScheduledJobType.CATALOG_SCAN, 10L);
        ScheduledBotTask current = new ScheduledBotTask(3L, ScheduledJobType.CATALOG_SCAN, 20L);
        schedule.queued(current);
        assertFalse(schedule.matches(old));
        assertTrue(schedule.matches(current));
        schedule.claim(current.jobType());
        assertFalse(schedule.matches(current));
    }

    @Test public void makingDueNeverDropsAnExistingWorkingClaim() {
        BotSchedule schedule = new BotSchedule(true, 1000L);
        schedule.claim(ScheduledJobType.CATALOG_SCAN);
        schedule.makeDue(500L);
        assertTrue(schedule.isWorking());
        assertTrue(schedule.isWorkingCatalog());
        assertEquals(500L, schedule.nextJob().runAtEpochMs());
    }

    @Test public void inactiveNegotiationsStayDisabledUntilRefreshed() {
        BotSchedule schedule = new BotSchedule(false, 1000L);
        schedule.complete(ScheduledJobType.CATALOG_SCAN, 1000L, 1000L, true);
        schedule.makeDue(1500L);
        assertEquals(ScheduledJobType.CATALOG_SCAN, schedule.nextJob().jobType());
        assertTrue(schedule.refresh(true, 1400L));
        assertEquals(new BotSchedule.NextJob(ScheduledJobType.NEGOTIATION_CHECK, 1400L), schedule.nextJob());
    }

    @Test public void hugeDelaySaturatesInsteadOfWrappingIntoThePast() {
        BotSchedule schedule = new BotSchedule(false, 1000L);
        schedule.complete(ScheduledJobType.CATALOG_SCAN, 1000L, Long.MAX_VALUE, false);
        assertEquals(Long.MAX_VALUE, schedule.nextJob().runAtEpochMs());
    }
}
