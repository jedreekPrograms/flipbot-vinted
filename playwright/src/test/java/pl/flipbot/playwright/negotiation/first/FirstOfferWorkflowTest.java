package pl.flipbot.playwright.negotiation.first;

import org.junit.Test;
import org.mockito.InOrder;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.api.quota.dto.OfferQuotaReservationResponseDto;
import pl.flipbot.playwright.negotiation.FirstOfferActionGuardCoordinator;
import pl.flipbot.playwright.negotiation.NegotiationPreparationResult;
import pl.flipbot.playwright.negotiation.NegotiationStartResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FirstOfferWorkflowTest {
    @Test
    public void reservesOnlyAfterPreparationAndGuardAndSubmitsOnce() {
        Fixture f = new Fixture();
        assertEquals(FirstOfferWorkflow.Outcome.STARTED, f.execute());
        InOrder order = inOrder(f.action, f.guard, f.quota);
        order.verify(f.action).prepareFirstOffer(f.listing);
        order.verify(f.action).assertPreparedOfferReady(f.listing);
        order.verify(f.guard).acquire(5L, f.listing);
        order.verify(f.quota).reserveSlot(5L, f.request);
        order.verify(f.action).submitPreparedFirstNegotiation(f.listing);
        order.verify(f.guard).releaseAfterConfirmedSuccessBestEffort(5L, f.listing, f.request);
        verify(f.action, times(1)).submitPreparedFirstNegotiation(f.listing);
    }

    @Test
    public void refusedGuardSkipsCandidateWithoutReservingOrSubmitting() {
        Fixture f = new Fixture();
        when(f.guard.acquire(5L, f.listing)).thenReturn(null);
        assertEquals(FirstOfferWorkflow.Outcome.SKIPPED, f.execute());
        verifyNoInteractions(f.quota);
        verify(f.action, never()).submitPreparedFirstNegotiation(any());
        verify(f.action).cancelPreparedOfferSafely();
    }

    @Test
    public void quotaFailureReleasesOnlyThePreSubmitGuardAndCancelsForm() {
        Fixture f = new Fixture();
        RuntimeException error = new IllegalStateException("Quota API unavailable");
        when(f.quota.reserveSlot(5L, f.request)).thenThrow(error);
        assertSame(error, assertThrows(IllegalStateException.class, f::execute));
        verify(f.guard).releaseBeforeSubmitSafely(eq(5L), eq(f.listing), eq(f.request), anyString());
        verify(f.action).cancelPreparedOfferSafely();
        verify(f.action, never()).submitPreparedFirstNegotiation(any());
    }

    @Test
    public void exhaustedQuotaStopsTheRunWithoutASubmit() {
        Fixture f = new Fixture();
        when(f.quota.reserveSlot(5L, f.request)).thenReturn(new OfferQuotaReservationResponseDto(false, 25, 25, 0));
        assertEquals(FirstOfferWorkflow.Outcome.QUOTA_EXHAUSTED, f.execute());
        verify(f.guard).releaseBeforeSubmitSafely(eq(5L), eq(f.listing), eq(f.request), anyString());
        verify(f.action, never()).submitPreparedFirstNegotiation(any());
    }

    @Test
    public void ambiguousUnprovenSubmitKeepsQuotaAndPersistentGuard() {
        Fixture f = new Fixture();
        RuntimeException error = new IllegalStateException("Response lost after click");
        when(f.action.submitPreparedFirstNegotiation(f.listing)).thenThrow(error);
        assertSame(error, assertThrows(IllegalStateException.class, f::execute));
        verify(f.action, times(1)).submitPreparedFirstNegotiation(f.listing);
        verify(f.guard, never()).releaseBeforeSubmitSafely(any(), any(), any(), anyString());
        verify(f.guard, never()).releaseAfterConfirmedSuccessBestEffort(any(), any(), any());
        verify(f.quota).reserveSlot(5L, f.request);
        verifyNoMoreInteractions(f.quota);
    }

    @Test
    public void reconciledDeliveryCompletesWithoutSendingAgain() {
        Fixture f = new Fixture();
        when(f.action.submitPreparedFirstNegotiation(f.listing)).thenThrow(new IllegalStateException("Response lost"));
        when(f.action.reconcilePreparedFirstNegotiationAfterAmbiguousSubmit(f.listing)).thenReturn(true);
        assertEquals(FirstOfferWorkflow.Outcome.STARTED, f.execute());
        verify(f.action, times(1)).submitPreparedFirstNegotiation(f.listing);
        verify(f.guard).releaseAfterConfirmedSuccessBestEffort(5L, f.listing, f.request);
    }

    @Test
    public void noNegotiationActionPersistsSkippedStatusBeforeAnyGuardOrQuota() {
        Fixture f = new Fixture();
        when(f.action.prepareFirstOffer(f.listing)).thenReturn(NegotiationPreparationResult.CANNOT_NEGOTIATE);
        assertEquals(FirstOfferWorkflow.Outcome.SKIPPED, f.execute());
        verify(f.statuses).markCannotNegotiate(5L, f.listing);
        verifyNoInteractions(f.guard, f.quota);
    }

    private static final class Fixture {
        final FirstOfferAction action = mock(FirstOfferAction.class);
        final FirstOfferActionGuardCoordinator guard = mock(FirstOfferActionGuardCoordinator.class);
        final OfferQuotaClient quota = mock(OfferQuotaClient.class);
        final ListingStatusUpdater statuses = mock(ListingStatusUpdater.class);
        final UUID request = UUID.randomUUID();
        final ListingResponseDto listing = new ListingResponseDto(1L, "123", "Galaxy S25", "/items/123", new BigDecimal("700"),
                new BigDecimal("700"), 0, false, null, null, "DISCOVERED", null);

        Fixture() {
            when(action.prepareFirstOffer(listing)).thenReturn(NegotiationPreparationResult.PREPARED);
            when(guard.acquire(5L, listing)).thenReturn(request);
            when(quota.reserveSlot(5L, request)).thenReturn(new OfferQuotaReservationResponseDto(true, 25, 1, 24));
            when(action.submitPreparedFirstNegotiation(listing)).thenReturn(NegotiationStartResult.STARTED);
        }

        FirstOfferWorkflow.Outcome execute() {
            return new FirstOfferWorkflow(action, guard, quota, statuses).execute(5L, listing);
        }
    }
}
