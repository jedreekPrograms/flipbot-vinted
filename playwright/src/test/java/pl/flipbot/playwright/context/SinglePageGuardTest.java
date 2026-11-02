package pl.flipbot.playwright.context;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SinglePageGuardTest {
    @Test public void existingVintedPageWinsAndOnlyExtraPagesClose() {
        BrowserContext context = mock(BrowserContext.class);
        Page blank = mock(Page.class);
        Page vinted = mock(Page.class);
        when(blank.url()).thenReturn("about:blank");
        when(vinted.url()).thenReturn("https://www.vinted.pl/inbox/123");
        when(context.pages()).thenReturn(List.of(blank, vinted));
        assertSame(vinted, new SinglePageGuard(context, 3L, new AtomicInteger()).openMainPage());
        verify(blank).close();
        verify(vinted, never()).close();
        verify(context, never()).newPage();
    }

    @Test public void emptyContextCreatesExactlyOneMainPage() {
        BrowserContext context = mock(BrowserContext.class);
        Page page = mock(Page.class);
        when(context.pages()).thenReturn(List.of());
        when(context.newPage()).thenReturn(page);
        assertSame(page, new SinglePageGuard(context, 3L, new AtomicInteger()).openMainPage());
        verify(context).newPage();
        verify(page, never()).close();
    }

    @SuppressWarnings("unchecked")
    @Test public void popupAndContextCallbacksShareOneMainPageAndOneExtraPageCounter() {
        BrowserContext context = mock(BrowserContext.class);
        Page main = mock(Page.class);
        Page popup = mock(Page.class);
        AtomicBoolean closed = new AtomicBoolean();
        when(main.url()).thenReturn("https://www.vinted.pl/");
        when(context.pages()).thenReturn(List.of(main));
        when(popup.isClosed()).thenAnswer(call -> closed.get());
        doAnswer(call -> { closed.set(true); return null; }).when(popup).close();
        AtomicInteger events = new AtomicInteger();
        new SinglePageGuard(context, 3L, events).openMainPage();
        ArgumentCaptor<Consumer<Page>> onPage = ArgumentCaptor.forClass(Consumer.class);
        ArgumentCaptor<Consumer<Page>> onPopup = ArgumentCaptor.forClass(Consumer.class);
        verify(context).onPage(onPage.capture());
        verify(main).onPopup(onPopup.capture());
        onPage.getValue().accept(main);
        onPage.getValue().accept(popup);
        onPopup.getValue().accept(popup);
        assertEquals(1, events.get());
        verify(popup).close();
        verify(main, never()).close();
    }
}
