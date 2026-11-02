package pl.flipbot.bot.configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.bot.Bot;
import pl.flipbot.bot.BotRepository;
import pl.flipbot.bot.BotStatus;
import pl.flipbot.bot.dto.BotAdditionalTargetResponse;
import pl.flipbot.bot.dto.UpsertBotAdditionalTargetRequest;
import pl.flipbot.exception.BotNotFoundException;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.ListingStatus;
import pl.flipbot.mapper.BotAdditionalTargetMapper;

@Service
@RequiredArgsConstructor
public class BotAdditionalTargetService {

    public static final int MAX_ADDITIONAL_TARGETS = 4;

    private final BotRepository botRepository;
    private final BotAdditionalTargetRepository additionalTargetRepository;
    private final ListingRepository listingRepository;
    private final BotAdditionalTargetMapper additionalTargetMapper;

    @Transactional
    public BotAdditionalTargetResponse create(
            Long botId,
            UpsertBotAdditionalTargetRequest request
    ) {
        Bot bot = requireEditableBot(botId);

        long activeTargets = additionalTargetRepository
                .countByConfigurationBotIdAndActiveTrue(botId);
        if (activeTargets >= MAX_ADDITIONAL_TARGETS) {
            throw new IllegalStateException(
                    "Bot może mieć maksymalnie 4 dodatkowe produkty (5 produktów łącznie z głównym)."
            );
        }

        AdditionalProductValidation.validate(request);

        BotAdditionalTarget target = BotAdditionalTarget.builder()
                .configuration(bot.getConfiguration())
                .active(true)
                .build();

        applyDefinitionFields(target, request);
        ProductNegotiationSteps.forAdditionalProduct(target).replace(request.getNegotiationSteps());

        return additionalTargetMapper.map(
                additionalTargetRepository.save(target)
        );
    }

    @Transactional
    public BotAdditionalTargetResponse update(
            Long botId,
            Long targetId,
            UpsertBotAdditionalTargetRequest request
    ) {
        requireEditableBot(botId);
        AdditionalProductValidation.validate(request);

        BotAdditionalTarget target = requireTarget(botId, targetId);
        if (!Boolean.TRUE.equals(target.getActive())) {
            throw new IllegalStateException(
                    "Wyłączony dodatkowy produkt nie może być edytowany. Dodaj go ponownie jako nowy produkt."
            );
        }

        boolean targetDefinitionChanged = targetDefinitionChanged(target, request);
        boolean priceRangeChanged = !sameDecimal(target.getMinPrice(), request.getMinPrice())
                || !sameDecimal(target.getMaxPrice(), request.getMaxPrice());
        boolean requestedAdaptive = Boolean.TRUE.equals(
                request.getAutoRaiseOfferToVintedMinimum()
        );
        boolean adaptiveModeChanged = Boolean.TRUE.equals(
                target.getAutoRaiseOfferToVintedMinimum()
        ) != requestedAdaptive;
        BigDecimal requestedGlobalCap = requestedAdaptive
                ? request.getMaxAutomaticOffer()
                : null;
        boolean globalCapChanged = !sameDecimal(
                target.getMaxAutomaticOffer(),
                requestedGlobalCap
        );
        boolean capIncreased = isGlobalCapIncreased(
                target.getMaxAutomaticOffer(),
                requestedGlobalCap
        );
        ProductNegotiationSteps steps = ProductNegotiationSteps.forAdditionalProduct(target);
        boolean stepDefinitionChanged = steps.definitionChanged(request.getNegotiationSteps());
        boolean responsePoliciesChanged = steps.policiesChanged(request.getNegotiationSteps());
        boolean strategyChanged = adaptiveModeChanged
                || globalCapChanged
                || stepDefinitionChanged
                || responsePoliciesChanged;

        List<Listing> activeListings = getActiveNegotiationListings(
                botId,
                targetId
        );

        validateActiveNegotiationEdit(
                activeListings,
                targetDefinitionChanged
        );

        applyDefinitionFields(target, request);

        if (stepDefinitionChanged) {
            steps.replace(request.getNegotiationSteps());
        } else if (responsePoliciesChanged) {
            steps.applyPolicies(request.getNegotiationSteps());
        }

        if (strategyChanged) {
            target.setNegotiationStrategyVersion(
                    nextStrategyVersion(target.getNegotiationStrategyVersion())
            );
        }

        if (stepDefinitionChanged || adaptiveModeChanged || capIncreased) {
            resetTargetListingsWithStatus(
                    botId,
                    targetId,
                    ListingStatus.SKIPPED_OFFER_TOO_LOW
            );
        }
        if (priceRangeChanged) {
            resetTargetListingsWithStatus(
                    botId,
                    targetId,
                    ListingStatus.SKIPPED_OUTSIDE_PRICE_RANGE
            );
        }
        if (targetDefinitionChanged) {
            resetTargetListingsWithStatus(
                    botId,
                    targetId,
                    ListingStatus.SKIPPED_TARGET_MISMATCH
            );
        }

        return additionalTargetMapper.map(target);
    }

    /**
     * Deactivation stops future catalog scans only. The row and its negotiation
     * ladder intentionally remain in the database so existing conversations
     * continue with the exact product strategy that started them.
     */
    @Transactional
    public void deactivate(Long botId, Long targetId) {
        requireEditableBot(botId);
        BotAdditionalTarget target = requireTarget(botId, targetId);
        target.setActive(false);
    }

    private Bot requireBot(Long botId) {
        return botRepository.findById(botId)
                .orElseThrow(() -> new BotNotFoundException(botId));
    }

    private Bot requireEditableBot(Long botId) {
        Bot bot = requireBot(botId);
        if (bot.getStatus() != BotStatus.STOPPED) {
            throw new IllegalStateException(
                    "Najpierw zatrzymaj bota, aby zmieniać dodatkowe produkty."
            );
        }
        if (bot.getConfiguration() == null) {
            throw new IllegalStateException("Bot nie ma konfiguracji głównego produktu.");
        }
        return bot;
    }

    private BotAdditionalTarget requireTarget(Long botId, Long targetId) {
        return additionalTargetRepository
                .findByIdAndConfigurationBotId(targetId, botId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "Nie znaleziono dodatkowego produktu " + targetId
                                + " dla bota " + botId + "."
                ));
    }

    private List<Listing> getActiveNegotiationListings(
            Long botId,
            Long targetId
    ) {
        List<Listing> active = new ArrayList<>(
                listingRepository.findByBotIdAndStatusAndAdditionalTargetIdOrderByIdAsc(
                        botId,
                        ListingStatus.NEGOTIATING,
                        targetId
                )
        );
        active.addAll(
                listingRepository.findByBotIdAndStatusAndAdditionalTargetIdOrderByIdAsc(
                        botId,
                        ListingStatus.ACTION_REQUIRED,
                        targetId
                )
        );
        return active;
    }

    private void validateActiveNegotiationEdit(
            List<Listing> activeListings,
            boolean targetDefinitionChanged
    ) {
        if (activeListings.isEmpty()) {
            return;
        }

        if (targetDefinitionChanged) {
            throw new IllegalStateException(
                    "Ten dodatkowy produkt ma aktywne negocjacje, więc nie można teraz zmienić jego kategorii, marki, modelu ani frazy wyszukiwania. "
                            + "Strategia negocjacji jest wersjonowana: ceny, progi, wiadomości, liczba kroków, tryb adaptacyjny, globalny limit i reguły reakcji można zmienić bez wpływu na rozpoczęte rozmowy."
            );
        }
    }

    private void applyDefinitionFields(
            BotAdditionalTarget target,
            UpsertBotAdditionalTargetRequest request
    ) {
        TargetMode mode = request.getTargetMode();
        boolean adaptive = Boolean.TRUE.equals(
                request.getAutoRaiseOfferToVintedMinimum()
        );

        target.setCategoryPath(normalizeCategoryPath(request.getCategoryPath()));
        target.setBrand(normalizeRequired(request.getBrand()));
        target.setTargetMode(mode);
        target.setModel(mode == TargetMode.VINTED_MODEL
                ? normalizeRequired(request.getModel())
                : null);
        target.setSearchQuery(mode == TargetMode.SEARCH_QUERY
                ? normalizeRequired(request.getSearchQuery())
                : null);
        target.setMinPrice(request.getMinPrice());
        target.setMaxPrice(request.getMaxPrice());
        target.setAutoRaiseOfferToVintedMinimum(adaptive);
        target.setMaxAutomaticOffer(adaptive
                ? request.getMaxAutomaticOffer()
                : null);
    }

    private boolean targetDefinitionChanged(
            BotAdditionalTarget target,
            UpsertBotAdditionalTargetRequest request
    ) {
        if (!categoryPathsEqual(target.getCategoryPath(), request.getCategoryPath())
                || !sameNormalizedText(target.getBrand(), request.getBrand())
                || target.getTargetMode() != request.getTargetMode()) {
            return true;
        }

        if (request.getTargetMode() == TargetMode.VINTED_MODEL) {
            return !sameNormalizedText(target.getModel(), request.getModel());
        }
        return !sameNormalizedText(target.getSearchQuery(), request.getSearchQuery());
    }

    private void resetTargetListingsWithStatus(
            Long botId,
            Long targetId,
            ListingStatus status
    ) {
        for (Listing listing :
                listingRepository.findByBotIdAndStatusAndAdditionalTargetIdOrderByIdAsc(
                        botId,
                        status,
                        targetId
                )) {
            listing.setStatus(ListingStatus.DISCOVERED);
        }
    }

    private boolean categoryPathsEqual(
            List<String> left,
            List<String> right
    ) {
        return normalizeCategoryPath(left).equals(normalizeCategoryPath(right));
    }

    private List<String> normalizeCategoryPath(List<String> path) {
        if (path == null) {
            return List.of();
        }
        return path.stream()
                .map(this::normalizeRequired)
                .toList();
    }

    private boolean sameNormalizedText(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return normalizeRequired(left).equalsIgnoreCase(normalizeRequired(right));
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    private int nextStrategyVersion(Integer currentVersion) {
        return currentVersion == null || currentVersion < 1
                ? 2
                : currentVersion + 1;
    }

    private boolean isGlobalCapIncreased(
            BigDecimal current,
            BigDecimal requested
    ) {
        if (requested == null) {
            return false;
        }
        return current == null || requested.compareTo(current) > 0;
    }

    private String normalizeRequired(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

}
