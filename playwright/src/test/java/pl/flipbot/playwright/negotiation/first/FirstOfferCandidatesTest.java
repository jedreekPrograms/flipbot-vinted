package pl.flipbot.playwright.negotiation.first;

import com.microsoft.playwright.Page;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.negotiation.CatalogDetailInspectionBudget;
import pl.flipbot.playwright.target.ListingDetailTargetInspector;
import pl.flipbot.playwright.target.ListingTargetAssessment;
import pl.flipbot.playwright.target.ListingTargetMatcher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FirstOfferCandidatesTest {
    @Test
    public void nativeModeAcceptsOnlyCurrentScanProvenanceWithoutSellerTextChecks() {
        Fixture f = new Fixture("VINTED_MODEL");
        ListingResponseDto current = listing("123");
        List<ListingResponseDto> selected = f.selector().select(List.of(current, listing("124")), f.configuration, Set.of("123"));
        assertEquals(List.of(current), selected);
        assertEquals(List.of(current), f.verifier().verify(selected, f.configuration, Set.of("123"), 1).verifiedListings());
        assertEquals(0, f.budget.used());
        verifyNoInteractions(f.matcher, f.inspector, f.statuses, f.page);
    }

    @Test
    public void initialAndFinalChecksShareOneBudgetAndReuseLiveIdentityCache() {
        Fixture f = new Fixture("SEARCH_QUERY");
        ListingResponseDto first = listing("123");
        when(f.matcher.assessCatalogListing(any(), any())).thenReturn(ListingTargetAssessment.NEEDS_DETAIL_INSPECTION);
        when(f.matcher.assessListingUrl(any(), any())).thenReturn(ListingTargetAssessment.NEEDS_DETAIL_INSPECTION);
        when(f.inspector.hasCachedFullTitle("123")).thenReturn(false, true);
        when(f.inspector.matchesConfiguredTarget(first, f.configuration)).thenReturn(true);
        List<ListingResponseDto> selected = f.selector().select(List.of(first, listing("124")), f.configuration, Set.of());
        assertEquals(List.of(first), selected);
        assertEquals(List.of(first), f.verifier().verify(selected, f.configuration, Set.of(), 1).verifiedListings());
        assertEquals(1, f.budget.used());
        verify(f.inspector, never()).matchesConfiguredTarget(eq(listing("124")), any());
        verify(f.inspector, times(2)).matchesConfiguredTarget(first, f.configuration);
    }

    @Test
    public void conclusiveTextMismatchIsPersistedWithoutSpendingDetailBudget() {
        Fixture f = new Fixture("SEARCH_QUERY");
        ListingResponseDto wrong = listing("123");
        when(f.matcher.assessCatalogListing(wrong, f.configuration)).thenReturn(ListingTargetAssessment.MISMATCH);
        assertTrue(f.selector().select(List.of(wrong), f.configuration, Set.of()).isEmpty());
        verify(f.statuses).markTargetMismatch(5L, wrong);
        verifyNoInteractions(f.inspector);
        assertEquals(0, f.budget.used());
    }

    private static ListingResponseDto listing(String id) {
        return new ListingResponseDto(Long.valueOf(id), id, "Galaxy S25", "/items/" + id, new BigDecimal("700"),
                new BigDecimal("700"), 0, false, null, null, "DISCOVERED", null);
    }

    private static final class Fixture {
        final BotContext context = mock(BotContext.class);
        final Page page = mock(Page.class);
        final ListingStatusUpdater statuses = mock(ListingStatusUpdater.class);
        final ListingTargetMatcher matcher = mock(ListingTargetMatcher.class);
        final ListingDetailTargetInspector inspector = mock(ListingDetailTargetInspector.class);
        final CatalogDetailInspectionBudget budget = new CatalogDetailInspectionBudget(1);
        final BotConfigurationDto configuration = new BotConfigurationDto();

        Fixture(String mode) {
            configuration.setTargetMode(mode);
            BotDetailsDto bot = new BotDetailsDto();
            bot.setId(5L);
            when(context.getBot()).thenReturn(bot);
            when(context.getPage()).thenReturn(page);
        }
        FirstOfferCandidates selector() { return new FirstOfferCandidates(context, statuses, matcher, inspector, budget); }
        FirstOfferTargetVerifier verifier() { return new FirstOfferTargetVerifier(context, statuses, inspector, budget); }
    }
}
