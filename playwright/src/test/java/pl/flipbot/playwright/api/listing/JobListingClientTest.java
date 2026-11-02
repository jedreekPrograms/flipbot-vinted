package pl.flipbot.playwright.api.listing;

import java.net.http.HttpResponse;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JobListingClientTest {
    @Test public void catalogBacklogReadsKeepMainAndAdditionalProductRoutesSeparate() {
        var main = new BacklogClient(null);
        var additional = new BacklogClient(7L);
        assertTrue(main.getDiscoveredListings(3L).isEmpty());
        assertEquals("/api/bots/3/listings/discovered/primary", main.path);
        assertTrue(additional.getDiscoveredListings(3L).isEmpty());
        assertEquals("/api/bots/3/listings/discovered/additional/7", additional.path);
    }

    @Test public void aSharedReadNeverMixesMainAndAdditionalProductConversations() throws Exception {
        ListingClient backend = mock(ListingClient.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var main = mapper.readValue("{\"id\":1,\"additionalTargetId\":null}",
                pl.flipbot.playwright.api.listing.dto.ListingResponseDto.class);
        var additional = mapper.readValue("{\"id\":2,\"additionalTargetId\":7}",
                pl.flipbot.playwright.api.listing.dto.ListingResponseDto.class);
        when(backend.getNegotiatingListings(3L)).thenReturn(List.of(main, additional));
        JobListingClient job = new JobListingClient(3L, backend);
        assertEquals(List.of(main), new TargetBoundListingClient(job, null).getNegotiatingListings(3L));
        assertEquals(List.of(additional), new TargetBoundListingClient(job, 7L).getNegotiatingListings(3L));
        verify(backend, times(1)).getNegotiatingListings(3L);
    }

    @Test public void productsShareReadsButAnotherJobGetsFreshState() {
        ListingClient backend = mock(ListingClient.class);
        when(backend.getNegotiatingListings(3L)).thenReturn(List.of());
        when(backend.getNegotiationRecoveryCandidates(3L)).thenReturn(List.of());
        JobListingClient job = new JobListingClient(3L, backend);
        for (Long product : new Long[]{null, 1L, 2L, 3L, 4L}) {
            TargetBoundListingClient client = new TargetBoundListingClient(job, product);
            assertTrue(client.getNegotiatingListings(3L).isEmpty());
            assertTrue(client.getNegotiationRecoveryCandidates(3L).isEmpty());
        }
        verify(backend, times(1)).getNegotiatingListings(3L);
        verify(backend, times(1)).getNegotiationRecoveryCandidates(3L);
        new JobListingClient(3L, backend).getNegotiatingListings(3L);
        verify(backend, times(2)).getNegotiatingListings(3L);
        assertThrows(IllegalArgumentException.class, () -> job.getNegotiatingListings(4L));
        verify(backend, never()).getNegotiatingListings(4L);
    }

    @Test public void failedReadsAreNeverCachedAsAnEmptySuccessfulResponse() {
        ListingClient backend = mock(ListingClient.class);
        when(backend.getNegotiatingListings(3L)).thenThrow(new IllegalStateException("offline")).thenReturn(List.of());
        JobListingClient job = new JobListingClient(3L, backend);
        assertThrows(IllegalStateException.class, () -> job.getNegotiatingListings(3L));
        assertTrue(job.getNegotiatingListings(3L).isEmpty());
        verify(backend, times(2)).getNegotiatingListings(3L);
    }

    private static class BacklogClient extends TargetBoundListingClient {
        private String path;

        BacklogClient(Long targetId) {
            super(mock(ListingClient.class), targetId);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected HttpResponse<String> get(String path) {
            this.path = path;
            HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("[]");
            return response;
        }
    }
}
