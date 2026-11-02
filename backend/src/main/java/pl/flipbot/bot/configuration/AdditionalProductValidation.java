package pl.flipbot.bot.configuration;

import java.math.BigDecimal;
import java.util.Objects;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.bot.dto.UpsertBotAdditionalTargetRequest;
import pl.flipbot.negotiation.dto.CreateNegotiationStepRequest;

import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.resolvePolicy;
import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.validateResolvedPolicy;

/** Additional-product input rules, independent of persistence and conversation changes. */
final class AdditionalProductValidation {
    private AdditionalProductValidation() {}

    static void validate(UpsertBotAdditionalTargetRequest request) {
        Objects.requireNonNull(request, "Dodatkowy produkt jest wymagany.");

        if (request.getCategoryPath() == null
                || request.getCategoryPath().isEmpty()
                || request.getCategoryPath().stream()
                .anyMatch(value -> value == null || value.isBlank())) {
            throw new InvalidRequestException("Kategoria dodatkowego produktu jest wymagana.");
        }
        if (request.getBrand() == null || request.getBrand().isBlank()) {
            throw new InvalidRequestException("Marka dodatkowego produktu jest wymagana.");
        }
        if (request.getTargetMode() == null) {
            throw new InvalidRequestException("Tryb celu dodatkowego produktu jest wymagany.");
        }
        if (request.getTargetMode() == TargetMode.VINTED_MODEL
                && (request.getModel() == null || request.getModel().isBlank())) {
            throw new InvalidRequestException("Model jest wymagany w trybie VINTED_MODEL.");
        }
        if (request.getTargetMode() == TargetMode.SEARCH_QUERY
                && (request.getSearchQuery() == null || request.getSearchQuery().isBlank())) {
            throw new InvalidRequestException("Fraza wyszukiwania jest wymagana w trybie SEARCH_QUERY.");
        }

        validatePriceRange(request.getMinPrice(), request.getMaxPrice());

        if (request.getNegotiationSteps() == null
                || request.getNegotiationSteps().isEmpty()) {
            throw new InvalidRequestException(
                    "Dodatkowy produkt musi mieć co najmniej jeden krok negocjacji."
            );
        }
        if (request.getNegotiationSteps().size() > 25) {
            throw new InvalidRequestException(
                    "Dodatkowy produkt może mieć maksymalnie 25 kroków negocjacji."
            );
        }

        boolean adaptive = Boolean.TRUE.equals(
                request.getAutoRaiseOfferToVintedMinimum()
        );
        if (adaptive) {
            if (request.getMaxAutomaticOffer() == null
                    || request.getMaxAutomaticOffer().signum() <= 0) {
                throw new InvalidRequestException(
                        "Globalny limit negocjacji musi być większy od zera."
                );
            }
            if (request.getMaxAutomaticOffer().compareTo(request.getMaxPrice()) > 0) {
                throw new InvalidRequestException(
                        "Globalny limit negocjacji nie może przekraczać maksymalnej ceny ogłoszenia."
                );
            }
        }

        BigDecimal previousOffer = null;
        for (int index = 0; index < request.getNegotiationSteps().size(); index++) {
            CreateNegotiationStepRequest step = request.getNegotiationSteps().get(index);
            int stepNumber = index + 1;
            if (step == null
                    || step.getOfferPrice() == null
                    || step.getOfferPrice().signum() <= 0) {
                throw new InvalidRequestException(
                        "Cena kroku " + stepNumber + " musi być większa od zera."
                );
            }
            if (step.getMaxAcceptedCounterOffer() == null
                    || step.getMaxAcceptedCounterOffer().signum() <= 0) {
                throw new InvalidRequestException(
                        "Próg akceptowanej kontroferty kroku " + stepNumber
                                + " musi być większy od zera."
                );
            }
            if (step.getMessage() == null || step.getMessage().isBlank()) {
                throw new InvalidRequestException(
                        "Wiadomość kroku " + stepNumber + " jest wymagana."
                );
            }
            if (adaptive
                    && previousOffer != null
                    && step.getOfferPrice().compareTo(previousOffer) <= 0) {
                throw new InvalidRequestException(
                        "Ceny kroków muszą rosnąć w trybie adaptacyjnym."
                );
            }
            previousOffer = step.getOfferPrice();

            validateResolvedPolicy(resolvePolicy(step, stepNumber), stepNumber);
        }

        if (adaptive
                && request.getMaxAutomaticOffer().compareTo(
                request.getNegotiationSteps().getFirst().getOfferPrice()
        ) < 0) {
            throw new InvalidRequestException(
                    "Globalny limit negocjacji nie może być niższy niż pierwszy krok."
            );
        }
    }

    private static void validatePriceRange(
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
        if (minPrice == null || maxPrice == null) {
            throw new InvalidRequestException("Minimalna i maksymalna cena są wymagane.");
        }
        if (minPrice.signum() < 0 || maxPrice.signum() < 0) {
            throw new InvalidRequestException("Zakres cen nie może zawierać wartości ujemnych.");
        }
        if (minPrice.compareTo(maxPrice) > 0) {
            throw new InvalidRequestException(
                    "Cena minimalna nie może być wyższa od ceny maksymalnej."
            );
        }
    }
}
