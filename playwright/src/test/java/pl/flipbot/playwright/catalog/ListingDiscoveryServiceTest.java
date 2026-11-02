package pl.flipbot.playwright.catalog;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.Test;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.TargetBoundListingClient;
import pl.flipbot.playwright.api.listing.dto.CreateListingRequestDto;
import pl.flipbot.playwright.api.listing.dto.DiscoverListingsRequestDto;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.model.BotDetailsDto;
import pl.flipbot.playwright.scanner.model.Listing;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ListingDiscoveryServiceTest {
    @Test
    public void mainProductUsesItsBoundDiscoveryRoute() {
        verifyDiscoveryRoute(null, "/api/bots/9/listings/discover");
    }

    @Test
    public void additionalProductKeepsItsBoundDiscoveryRoute() {
        verifyDiscoveryRoute(44L, "/api/bots/9/listings/discover/additional/44");
    }

    private void verifyDiscoveryRoute(Long targetId, String expectedPath) {
        BotContext context = mock(BotContext.class);
        BotDetailsDto bot = new BotDetailsDto();
        bot.setId(9L);
        when(context.getBot()).thenReturn(bot);

        Listing listing = new Listing();
        listing.setId("market-1");
        listing.setTitle("Catalog fixture");
        listing.setUrl("https://www.vinted.pl/items/market-1");
        listing.setPrice(new BigDecimal("100.00"));
        ScopeRecordingListingClient client = new ScopeRecordingListingClient(targetId);

        var result = new ListingDiscoveryService(context, client).process(List.of(listing));

        assertEquals(expectedPath, client.path);
        assertEquals(new DiscoverListingsRequestDto(List.of(new CreateListingRequestDto(
                listing.getId(), listing.getTitle(), listing.getUrl(), listing.getPrice()
        ))), client.request);
        assertEquals(1, result.size());
        assertEquals(Long.valueOf(73L), result.getFirst().id());
    }

    private static final class ScopeRecordingListingClient extends TargetBoundListingClient {
        private String path;
        private Object request;

        ScopeRecordingListingClient(Long targetId) {
            super(mock(ListingClient.class), targetId);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected HttpResponse<String> post(String path, Object request) {
            this.path = path;
            this.request = request;
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("[{\"id\":73,\"listingId\":\"market-1\"}]");
            return response;
        }
    }
}
