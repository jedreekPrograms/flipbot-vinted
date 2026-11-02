package pl.flipbot.playwright.negotiation.next;

import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NextOfferPreparationMinimumTest {

    @Test
    public void parsesPolishVintedMinimumFromValidationMessage() {
        assertEquals(
                new BigDecimal("1080.00"),
                NextOfferForm
                        .parseMinimumAllowedPrice(
                                "Wartość jest zbyt niska. Minimalna wartość nie może być niższa niż 1 080,00 zł"
                        )
                        .orElseThrow()
        );
    }

    @Test
    public void genericTooLowMessageWithoutAmountStaysFailClosed() {
        assertTrue(
                NextOfferForm
                        .parseMinimumAllowedPrice(
                                "Wartość jest zbyt niska"
                        )
                        .isEmpty()
        );
    }
}
