package pl.flipbot.listing;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NegotiationRecoveryCandidateServiceTest {

    private final ListingRepository repository = mock(ListingRepository.class);
    private final NegotiationRecoveryCandidateService service =
            new NegotiationRecoveryCandidateService(
                    repository
            );

    @Test
    void onlyTechnicalTerminalStatusesAreWatched() {
        assertTrue(service.isWatchedTerminalStatus(ListingStatus.REJECTED));
        assertTrue(service.isWatchedTerminalStatus(ListingStatus.EXPIRED));
        assertFalse(service.isWatchedTerminalStatus(ListingStatus.UNAVAILABLE));
        assertFalse(
                service.isWatchedTerminalStatus(
                        ListingStatus.CONTACT_UNAVAILABLE
                )
        );

        assertFalse(
                service.isWatchedTerminalStatus(
                        ListingStatus.ACTION_REQUIRED
                )
        );
        assertFalse(service.isWatchedTerminalStatus(ListingStatus.PURCHASED));
        assertFalse(
                service.isWatchedTerminalStatus(
                        ListingStatus.SKIPPED_BY_USER
                )
        );
    }

    @Test
    void bothRecoveryQueueAndSchedulerExcludeUnavailableConversations() {
        service.findDueCandidates(3L);
        service.findBotIdsWithDueCandidates(List.of(3L));
        List<ListingStatus> watched = List.of(ListingStatus.REJECTED, ListingStatus.EXPIRED);
        verify(repository).findDueTerminalConversationCandidates(eq(3L), eq(watched), any(), any(), any());
        verify(repository).findDistinctBotIdsWithDueTerminalConversationCandidates(
                eq(List.of(3L)), eq(watched), any(), any());
    }
}
