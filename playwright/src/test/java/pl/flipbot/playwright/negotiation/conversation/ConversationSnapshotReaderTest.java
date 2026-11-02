package pl.flipbot.playwright.negotiation.conversation;

import com.microsoft.playwright.Page;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.mockito.MockedStatic;
import pl.flipbot.playwright.api.listing.dto.ListingResponseDto;
import pl.flipbot.playwright.negotiation.NegotiationConversationSnapshot;
import pl.flipbot.playwright.privatecore.PrivateVintedCoreBridge;
import pl.flipbot.playwright.session.VintedAuthenticationRequiredException;
import pl.flipbot.playwright.verification.HumanVerificationHandler;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ConversationSnapshotReaderTest {
    @Test
    public void delegatesIdentityPriceAndVerificationWithoutChangingThePrivateResult() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.vinted.pl/inbox/123");
        HumanVerificationHandler verification = mock(HumanVerificationHandler.class);
        ListingResponseDto listing = listing();
        NegotiationConversationSnapshot expected =
                NegotiationConversationSnapshot.sellerCounterOffer(new BigDecimal("250"));

        try (MockedStatic<PrivateVintedCoreBridge> bridge = mockStatic(PrivateVintedCoreBridge.class)) {
            bridge.when(() -> PrivateVintedCoreBridge.inspectConversation(
                    same(page), eq("456"), eq("123"), eq(new BigDecimal("300")), any(Runnable.class)))
                    .thenAnswer(call -> {
                        call.<Runnable>getArgument(4).run();
                        return expected;
                    });

            assertSame(expected, new ConversationSnapshotReader(verification).read(page, listing));
            verify(verification).waitUntilVerified(page);
            bridge.verify(() -> PrivateVintedCoreBridge.inspectConversation(
                    same(page), eq("456"), eq("123"), eq(new BigDecimal("300")), any(Runnable.class)));
        }
    }

    @Test
    public void authenticationRedirectStopsInspectionBeforeCallingTheCore() {
        Page page = mock(Page.class);
        when(page.url()).thenReturn("https://www.vinted.pl/member/login/email");
        HumanVerificationHandler verification = mock(HumanVerificationHandler.class);

        try (MockedStatic<PrivateVintedCoreBridge> bridge = mockStatic(PrivateVintedCoreBridge.class)) {
            assertThrows(VintedAuthenticationRequiredException.class,
                    () -> new ConversationSnapshotReader(verification).read(page, listing()));
            bridge.verifyNoInteractions();
            verifyNoInteractions(verification);
        }
    }

    @Test
    public void authenticationLossDuringPollingStopsInsteadOfReturningAStaleResult() {
        Page page = mock(Page.class);
        AtomicReference<String> url = new AtomicReference<>("https://www.vinted.pl/inbox/123");
        when(page.url()).thenAnswer(call -> url.get());
        HumanVerificationHandler verification = mock(HumanVerificationHandler.class);

        try (MockedStatic<PrivateVintedCoreBridge> bridge = mockStatic(PrivateVintedCoreBridge.class)) {
            bridge.when(() -> PrivateVintedCoreBridge.inspectConversation(
                    any(), anyString(), anyString(), any(), any()))
                    .thenAnswer(call -> {
                        url.set("https://www.vinted.pl/member/login/email");
                        call.<Runnable>getArgument(4).run();
                        return NegotiationConversationSnapshot.pending("Pending");
                    });

            assertThrows(VintedAuthenticationRequiredException.class,
                    () -> new ConversationSnapshotReader(verification).read(page, listing()));
            verifyNoInteractions(verification);
        }
    }

    private ListingResponseDto listing() {
        ListingResponseDto listing = mock(ListingResponseDto.class);
        when(listing.listingId()).thenReturn("456");
        when(listing.conversationId()).thenReturn("123");
        when(listing.originalPrice()).thenReturn(new BigDecimal("300"));
        return listing;
    }
}
