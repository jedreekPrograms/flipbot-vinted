package pl.flipbot.playwright.filters.model;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ModelOptionSelectionTest {
    @Test
    public void alreadySelectedExactCheckboxIsNeverToggled() {
        Page page = mock(Page.class);
        Locator row = mock(Locator.class);
        Locator checkbox = mock(Locator.class);
        when(row.locator(ModelOptionIdentity.exactModelCheckboxSelector("9977"))).thenReturn(checkbox);
        when(checkbox.first()).thenReturn(checkbox);
        when(checkbox.count()).thenReturn(1);
        when(checkbox.isChecked()).thenReturn(true);

        new ModelOptionSelection(page).select(row, "Galaxy S25 FE", "9977");

        verify(row, never()).click(any(Locator.ClickOptions.class));
        verify(checkbox, never()).evaluate(anyString());
        verifyNoInteractions(page);
    }

    @Test
    public void rowFallbackStillUsesTheProvenIdentityWhenSuffixClickFails() {
        Page page = mock(Page.class);
        Locator row = mock(Locator.class);
        Locator checkbox = mock(Locator.class);
        Locator suffix = mock(Locator.class);
        java.util.concurrent.atomic.AtomicBoolean selected = new java.util.concurrent.atomic.AtomicBoolean();
        when(row.locator(ModelOptionIdentity.exactModelCheckboxSelector("9977"))).thenReturn(checkbox);
        when(checkbox.first()).thenReturn(checkbox);
        when(checkbox.count()).thenReturn(1);
        when(checkbox.isChecked()).thenAnswer(call -> selected.get());
        when(row.locator("[data-testid='selectable-item-brand_collection-9977--suffix']")).thenReturn(suffix);
        when(suffix.first()).thenReturn(suffix);
        when(suffix.count()).thenReturn(1);
        when(suffix.isVisible()).thenReturn(true);
        doThrow(new IllegalStateException("Suffix intercepted")).when(suffix).click(any(Locator.ClickOptions.class));
        doAnswer(call -> { selected.set(true); return null; }).when(row).click(any(Locator.ClickOptions.class));

        new ModelOptionSelection(page).select(row, "Galaxy S25 FE", "9977");

        verify(suffix).click(any(Locator.ClickOptions.class));
        verify(row).click(any(Locator.ClickOptions.class));
        verify(row, never()).evaluate(anyString());
        verify(row, never()).press(anyString());
        verifyNoInteractions(page);
    }

    @Test
    public void successfulExactSuffixClickStopsBeforeRowAndKeyboardFallbacks() {
        Page page = mock(Page.class);
        Locator row = mock(Locator.class);
        Locator checkbox = mock(Locator.class);
        Locator suffix = mock(Locator.class);
        when(row.locator(ModelOptionIdentity.exactModelCheckboxSelector("9977"))).thenReturn(checkbox);
        when(checkbox.first()).thenReturn(checkbox);
        when(checkbox.count()).thenReturn(1);
        when(checkbox.isChecked()).thenReturn(false, true);
        when(row.locator("[data-testid='selectable-item-brand_collection-9977--suffix']")).thenReturn(suffix);
        when(suffix.first()).thenReturn(suffix);
        when(suffix.count()).thenReturn(1);
        when(suffix.isVisible()).thenReturn(true);

        new ModelOptionSelection(page).select(row, "Galaxy S25 FE", "9977");

        verify(suffix, times(1)).click(any(Locator.ClickOptions.class));
        verify(row, never()).click(any(Locator.ClickOptions.class));
        verify(row, never()).press(anyString());
        verify(row, never()).evaluate(anyString());
        verifyNoInteractions(page);
    }
}
