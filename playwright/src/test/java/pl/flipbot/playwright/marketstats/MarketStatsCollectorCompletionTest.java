package pl.flipbot.playwright.marketstats;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.slf4j.LoggerFactory;
import pl.flipbot.playwright.browser.BrowserManager;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.marketstats.dto.KnownMarketListingIdsDto;
import pl.flipbot.playwright.marketstats.dto.MarketObservationBatchResponseDto;
import pl.flipbot.playwright.marketstats.dto.MarketStatsTargetDto;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MarketStatsCollectorCompletionTest {
    @Test public void aStoredPartialScanIsNotReportedAsCompleteSuccess() {
        try (Fixture f = new Fixture(false)) {
            f.collector.collectOnce();
            assertTrue(f.logs().contains("incomplete=1"));
            assertTrue(f.logs().contains("Stored partial results"));
            assertFalse(f.logs().contains("complete scans for all"));
            verify(f.api).recordObservations(eq(1L), eq(List.of("123")), anyMap(), eq(true));
        }
    }

    @Test public void aBackendConfirmedCompleteScanReportsCompleteCoverage() {
        try (Fixture f = new Fixture(true)) {
            f.collector.collectOnce();
            assertTrue(f.logs().contains("incomplete=0"));
            assertTrue(f.logs().contains("complete scans for all 1 target(s)"));
            assertFalse(f.logs().contains("Stored partial results"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MarketStatsApiClient api = mock(MarketStatsApiClient.class);
        final Logger logger = (Logger) LoggerFactory.getLogger(MarketStatsCollector.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        final MockedConstruction<BrowserManager> browsers;
        final MockedConstruction<BotContext> contexts;
        final MockedConstruction<MarketObserverPage> observers;
        final MockedConstruction<MarketTargetFilters> filters;
        final MockedConstruction<MarketCatalogScan> scans;
        final MarketStatsCollector collector;

        Fixture(boolean complete) {
            appender.start();
            logger.addAppender(appender);
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(5L);
            bot.setName("Anonymous Market Observer");
            bot.setConfiguration(new BotConfigurationDto());
            when(api.getObserverBot()).thenReturn(bot);
            when(api.getTargets()).thenReturn(List.of(new MarketStatsTargetDto(1L, "Google", "Pixel 9",
                    "VINTED_MODEL", List.of("Elektronika", "Telefony komórkowe"), true, null, null, 1)));
            when(api.getKnownListingIds(1L)).thenReturn(new KnownMarketListingIdsDto(1L, List.of(), true));
            when(api.recordObservations(eq(1L), anyList(), anyMap(), anyBoolean())).thenReturn(
                    new MarketObservationBatchResponseDto(1L, false, 1, 0, "2026-10-17T23:21:16", complete));
            browsers = mockConstruction(BrowserManager.class);
            contexts = mockConstruction(BotContext.class);
            observers = mockConstruction(MarketObserverPage.class);
            filters = mockConstruction(MarketTargetFilters.class, (mock, construction) ->
                    when(mock.prepareScan(any(), any(), any())).thenReturn(
                            new MarketTargetFilters.PreparedScan(bot, "DICTIONARY_FILTERS", false)));
            scans = mockConstruction(MarketCatalogScan.class, (mock, construction) ->
                    when(mock.scan(any(), anyLong(), any(), anyBoolean(), anyBoolean(), anySet(), anyBoolean()))
                            .thenReturn(new MarketCatalogScan.ScanResult(List.of("123"), Map.of(), true)));
            collector = new MarketStatsCollector(new MarketStatsRuntimeConfig(true, true, 15L, 600, 20, 0L, 6), api);
        }

        String logs() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        public void close() {
            scans.close(); filters.close(); observers.close(); contexts.close(); browsers.close();
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
