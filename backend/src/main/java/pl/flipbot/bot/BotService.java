package pl.flipbot.bot;

import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.BotConfigurationRepository;
import pl.flipbot.bot.configuration.ProductNegotiationSteps;
import pl.flipbot.bot.configuration.TargetMode;
import pl.flipbot.bot.dto.*;
import pl.flipbot.bot.runtime.BotSessionPreviewService;
import pl.flipbot.exception.BotAlreadyExistsException;
import pl.flipbot.exception.BotNotFoundException;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.ListingStatus;
import pl.flipbot.mapper.BotMapper;
import pl.flipbot.negotiation.NegotiationStep;

import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.resolvePolicy;
import static pl.flipbot.negotiation.policy.NegotiationStepPolicies.validateResolvedPolicy;

@Service
@RequiredArgsConstructor
public class BotService {

    private final BotRepository botRepository;
    private final BotConfigurationRepository botConfigurationRepository;
    private final ListingRepository listingRepository;
    private final BotMapper botMapper;
    private final BotSessionPreviewService sessionPreviewService;

    public List<BotResponse> getAllBots() {
        return botRepository.findAll().stream().map(botMapper::map).toList();
    }

    public BotResponse getBot(Long botId) {
        return botMapper.map(getBotEntity(botId));
    }

    public BotEditCapabilitiesResponse getEditCapabilities(Long botId) {
        Bot bot = getBotEntity(botId);
        List<Listing> active = getActiveNegotiationListings(botId);
        List<Listing> mainActive = active.stream().filter(listing -> listing.getAdditionalTarget() == null).toList();
        return BotEditCapabilitiesResponse.builder()
                .hasActiveNegotiations(!active.isEmpty())
                .hasMainProductActiveNegotiations(!mainActive.isEmpty())
                .minimumNegotiationCap(minimumNegotiationCap(bot))
                .build();
    }

    @Transactional
    public BotResponse createBot(CreateBotRequest request) {
        if (botRepository.existsByEmail(request.getEmail())) {
            throw new BotAlreadyExistsException(request.getEmail());
        }
        CreateBotConfigurationRequest requested = request.getConfiguration();
        validateConfiguration(requested);
        TargetMode mode = resolveTargetMode(requested);
        boolean adaptive = Boolean.TRUE.equals(requested.getAutoRaiseOfferToVintedMinimum());
        Bot bot = Bot.builder()
                .name(normalizeRequiredText(request.getName()))
                .email(normalizeRequiredText(request.getEmail()))
                .password(request.getPassword())
                .status(BotStatus.STOPPED)
                .build();
        Bot savedBot = botRepository.save(bot);
        BotConfiguration configuration = BotConfiguration.builder()
                .marketplace(requested.getMarketplace())
                .categoryPath(new ArrayList<>(requested.getCategoryPath()))
                .brand(normalizeRequiredText(requested.getBrand()))
                .targetMode(mode)
                .model(mode == TargetMode.VINTED_MODEL ? normalizeRequiredText(requested.getModel()) : null)
                .searchQuery(mode == TargetMode.SEARCH_QUERY ? normalizeRequiredText(requested.getSearchQuery()) : null)
                .minPrice(requested.getMinPrice())
                .maxPrice(requested.getMaxPrice())
                .autoRaiseOfferToVintedMinimum(adaptive)
                .maxAutomaticOffer(adaptive ? requested.getMaxAutomaticOffer() : null)
                .dailyNegotiationBudget(requested.getDailyNegotiationBudget())
                .bot(savedBot)
                .build();
        savedBot.setConfiguration(configuration);
        ProductNegotiationSteps.forMainProduct(configuration).replace(requested.getNegotiationSteps());
        botConfigurationRepository.save(configuration);
        return botMapper.map(savedBot);
    }

    @Transactional
    public BotResponse updateBot(Long botId, UpdateBotRequest request) {
        Bot bot = getBotEntity(botId);
        if (bot.getStatus() != BotStatus.STOPPED) {
            throw new IllegalStateException("Only a stopped bot can be edited. Stop the bot first.");
        }
        BotConfiguration configuration = bot.getConfiguration();
        if (configuration == null) {
            throw new IllegalStateException("Bot configuration does not exist.");
        }
        CreateBotConfigurationRequest requested = request.getConfiguration();
        validateConfiguration(requested);
        TargetMode requestedMode = resolveTargetMode(requested);
        boolean requestedAdaptive = Boolean.TRUE.equals(requested.getAutoRaiseOfferToVintedMinimum());
        String normalizedEmail = normalizeRequiredText(request.getEmail());
        ProductNegotiationSteps steps = ProductNegotiationSteps.forMainProduct(configuration);
        boolean stepDefinitionChanged = steps.definitionChanged(requested.getNegotiationSteps());
        boolean responsePoliciesChanged = steps.policiesChanged(requested.getNegotiationSteps());
        boolean priceRangeChanged = !sameDecimal(configuration.getMinPrice(), requested.getMinPrice())
                || !sameDecimal(configuration.getMaxPrice(), requested.getMaxPrice());
        boolean adaptiveModeChanged = Boolean.TRUE.equals(configuration.getAutoRaiseOfferToVintedMinimum()) != requestedAdaptive;
        BigDecimal requestedGlobalCap = requestedAdaptive
                ? requested.getMaxAutomaticOffer()
                : null;
        boolean globalCapChanged = !sameDecimal(
                configuration.getMaxAutomaticOffer(),
                requestedGlobalCap
        );
        boolean globalCapIncreased = isGlobalCapIncreased(
                configuration.getMaxAutomaticOffer(),
                requestedGlobalCap
        );
        boolean strategyChanged = stepDefinitionChanged
                || responsePoliciesChanged
                || adaptiveModeChanged
                || globalCapChanged;
        boolean targetDefinitionChanged = targetDefinitionChanged(configuration, requested, requestedMode);
        boolean accountIdentityChanged = !sameNormalizedText(bot.getEmail(), normalizedEmail)
                || (request.getPassword() != null && !request.getPassword().isBlank());
        List<Listing> active = getActiveNegotiationListings(botId);
        List<Listing> mainActive = active.stream().filter(listing -> listing.getAdditionalTarget() == null).toList();
        validateActiveNegotiationEdit(
                bot,
                configuration,
                request,
                requested,
                requestedMode,
                normalizedEmail,
                active,
                mainActive
        );
        if (botRepository.existsByEmailAndIdNot(normalizedEmail, botId)) {
            throw new BotAlreadyExistsException(normalizedEmail);
        }
        bot.setName(normalizeRequiredText(request.getName()));
        bot.setEmail(normalizedEmail);
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            bot.setPassword(request.getPassword());
        }
        configuration.setMarketplace(requested.getMarketplace());
        configuration.setCategoryPath(new ArrayList<>(requested.getCategoryPath()));
        configuration.setBrand(normalizeRequiredText(requested.getBrand()));
        configuration.setTargetMode(requestedMode);
        configuration.setModel(requestedMode == TargetMode.VINTED_MODEL ? normalizeRequiredText(requested.getModel()) : null);
        configuration.setSearchQuery(requestedMode == TargetMode.SEARCH_QUERY ? normalizeRequiredText(requested.getSearchQuery()) : null);
        configuration.setMinPrice(requested.getMinPrice());
        configuration.setMaxPrice(requested.getMaxPrice());
        configuration.setAutoRaiseOfferToVintedMinimum(requestedAdaptive);
        configuration.setMaxAutomaticOffer(requestedAdaptive ? requested.getMaxAutomaticOffer() : null);
        configuration.setDailyNegotiationBudget(requested.getDailyNegotiationBudget());
        if (stepDefinitionChanged) {
            steps.replace(requested.getNegotiationSteps());
        } else if (responsePoliciesChanged) {
            steps.applyPolicies(requested.getNegotiationSteps());
        }
        if (strategyChanged) {
            configuration.setNegotiationStrategyVersion(
                    nextStrategyVersion(configuration.getNegotiationStrategyVersion())
            );
        }
        if (stepDefinitionChanged || adaptiveModeChanged || globalCapIncreased) {
            resetMainProductListingsWithStatus(botId, ListingStatus.SKIPPED_OFFER_TOO_LOW);
        }
        if (priceRangeChanged) {
            resetMainProductListingsWithStatus(botId, ListingStatus.SKIPPED_OUTSIDE_PRICE_RANGE);
        }
        if (targetDefinitionChanged) {
            resetMainProductListingsWithStatus(botId, ListingStatus.SKIPPED_TARGET_MISMATCH);
        }
        if (accountIdentityChanged) {
            resetAllListingsWithStatus(botId, ListingStatus.SKIPPED_CANNOT_NEGOTIATE);
        }
        return botMapper.map(bot);
    }

    @Transactional
    public void startBot(Long botId) {
        getBotEntity(botId).setStatus(BotStatus.RUNNING);
    }

    @Transactional
    public void stopBot(Long botId) {
        getBotEntity(botId).setStatus(BotStatus.STOPPED);
        // Do not depend on the scheduler observing STOPPED between STOP/START.
        sessionPreviewService.setPreviewRequested(botId, false);
    }

    public BotPlaywrightResponse getPlaywrightBot(Long botId) {
        Bot bot = getBotEntity(botId);
        if (bot.getStatus() != BotStatus.RUNNING) {
            throw new IllegalStateException("Bot is not running.");
        }
        return botMapper.mapPlaywright(bot);
    }

    private Bot getBotEntity(Long botId) {
        return botRepository.findById(botId).orElseThrow(() -> new BotNotFoundException(botId));
    }

    private List<Listing> getActiveNegotiationListings(Long botId) {
        List<Listing> result = new ArrayList<>(listingRepository.findByBotIdAndStatusOrderByIdAsc(botId, ListingStatus.NEGOTIATING));
        result.addAll(listingRepository.findByBotIdAndStatusOrderByIdAsc(botId, ListingStatus.ACTION_REQUIRED));
        return result;
    }

    private BigDecimal minimumNegotiationCap(Bot bot) {
        BotConfiguration configuration = bot.getConfiguration();
        if (configuration == null || !Boolean.TRUE.equals(configuration.getAutoRaiseOfferToVintedMinimum())) {
            return null;
        }
        return configuration.getNegotiationSteps().stream()
                .filter(Objects::nonNull)
                .filter(step -> step.getStepNumber() != null)
                .min(Comparator.comparing(NegotiationStep::getStepNumber))
                .map(NegotiationStep::getOfferPrice)
                .orElse(null);
    }

    private void validateActiveNegotiationEdit(
            Bot bot,
            BotConfiguration configuration,
            UpdateBotRequest request,
            CreateBotConfigurationRequest requested,
            TargetMode requestedMode,
            String normalizedEmail,
            List<Listing> active,
            List<Listing> mainActive
    ) {
        if (active.isEmpty()) {
            return;
        }
        List<String> locked = new ArrayList<>();
        if (!sameNormalizedText(bot.getEmail(), normalizedEmail)) locked.add("Vinted e-mail");
        if (request.getPassword() != null && !request.getPassword().isBlank()) locked.add("Vinted password");
        if (!Objects.equals(configuration.getMarketplace(), requested.getMarketplace())) locked.add("marketplace");
        if (!mainActive.isEmpty()) {
            if (!Objects.equals(configuration.getCategoryPath(), requested.getCategoryPath())) locked.add("category");
            if (!sameNormalizedText(configuration.getBrand(), requested.getBrand())) locked.add("brand");
            TargetMode currentMode = configuration.getTargetMode() == null ? TargetMode.VINTED_MODEL : configuration.getTargetMode();
            if (currentMode != requestedMode) locked.add("target mode");
            if (requestedMode == TargetMode.VINTED_MODEL) {
                if (!sameNormalizedText(configuration.getModel(), requested.getModel())) locked.add("model");
            } else if (!sameNormalizedText(configuration.getSearchQuery(), requested.getSearchQuery())) {
                locked.add("search query");
            }
        }
        if (!locked.isEmpty()) {
            throw new IllegalStateException(
                    "Bot has active negotiations. These fields cannot be changed while the affected conversations are active: "
                            + String.join(", ", locked)
                            + ". Shared Vinted account fields are locked by negotiations from any product; "
                            + "main-product target identity is locked only by main-product negotiations. "
                            + "Negotiation prices, messages, limits and response policies are versioned: "
                            + "active conversations keep their pinned strategy while new conversations use the saved version.");
        }
    }

    private boolean targetDefinitionChanged(BotConfiguration current, CreateBotConfigurationRequest requested, TargetMode requestedMode) {
        if (!Objects.equals(current.getMarketplace(), requested.getMarketplace())
                || !Objects.equals(current.getCategoryPath(), requested.getCategoryPath())
                || !sameNormalizedText(current.getBrand(), requested.getBrand())) return true;
        TargetMode currentMode = current.getTargetMode() == null ? TargetMode.VINTED_MODEL : current.getTargetMode();
        if (currentMode != requestedMode) return true;
        return requestedMode == TargetMode.VINTED_MODEL
                ? !sameNormalizedText(current.getModel(), requested.getModel())
                : !sameNormalizedText(current.getSearchQuery(), requested.getSearchQuery());
    }

    private boolean isGlobalCapIncreased(BigDecimal currentCap, BigDecimal requestedCap) {
        return requestedCap != null && (currentCap == null || requestedCap.compareTo(currentCap) > 0);
    }

    private int nextStrategyVersion(Integer currentVersion) {
        return currentVersion == null || currentVersion < 1
                ? 2
                : currentVersion + 1;
    }

    private boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? left == right : left.compareTo(right) == 0;
    }

    private boolean sameNormalizedText(String left, String right) {
        return left == null || right == null ? left == right : normalizeRequiredText(left).equals(normalizeRequiredText(right));
    }

    private void resetMainProductListingsWithStatus(Long botId, ListingStatus status) {
        for (Listing listing : listingRepository.findByBotIdAndStatusAndAdditionalTargetIsNullOrderByIdAsc(botId, status)) {
            listing.setStatus(ListingStatus.DISCOVERED);
        }
    }

    private void resetAllListingsWithStatus(Long botId, ListingStatus status) {
        for (Listing listing : listingRepository.findByBotIdAndStatusOrderByIdAsc(botId, status)) {
            listing.setStatus(ListingStatus.DISCOVERED);
        }
    }

    private void validateConfiguration(CreateBotConfigurationRequest request) {
        TargetMode mode = resolveTargetMode(request);
        validatePriceRange(request.getMinPrice(), request.getMaxPrice());
        if (request.getDailyNegotiationBudget() == null || request.getDailyNegotiationBudget() <= 0) {
            throw new InvalidRequestException("Daily negotiation budget must be greater than 0.");
        }
        if (request.getNegotiationSteps() == null || request.getNegotiationSteps().isEmpty()) {
            throw new InvalidRequestException("At least one negotiation step is required.");
        }
        if (mode == TargetMode.VINTED_MODEL) {
            requireNonBlank(request.getModel(), "Model is required for target mode VINTED_MODEL.");
        } else if (mode == TargetMode.SEARCH_QUERY) {
            requireNonBlank(request.getSearchQuery(), "Search query is required for target mode SEARCH_QUERY.");
        }
        if (Boolean.TRUE.equals(request.getAutoRaiseOfferToVintedMinimum())) {
            BigDecimal cap = request.getMaxAutomaticOffer();
            if (cap == null || cap.signum() <= 0) {
                throw new InvalidRequestException("Max automatic offer must be greater than 0 when adaptive pricing is enabled.");
            }
            if (request.getMaxPrice() != null && cap.compareTo(request.getMaxPrice()) > 0) {
                throw new InvalidRequestException("Max automatic offer cannot be greater than the configured maximum listing price.");
            }
        }
        for (int i = 0; i < request.getNegotiationSteps().size(); i++) {
            validateResolvedPolicy(resolvePolicy(request.getNegotiationSteps().get(i), i + 1), i + 1);
        }
    }

    private TargetMode resolveTargetMode(CreateBotConfigurationRequest request) {
        return request.getTargetMode() == null ? TargetMode.VINTED_MODEL : request.getTargetMode();
    }

    private void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice == null || maxPrice == null) return;
        if (minPrice.signum() < 0 || maxPrice.signum() < 0) throw new InvalidRequestException("Listing prices cannot be negative.");
        if (minPrice.compareTo(maxPrice) > 0) throw new InvalidRequestException("Minimum listing price cannot be greater than maximum listing price.");
    }

    private void requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) throw new InvalidRequestException(message);
    }

    private String normalizeRequiredText(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

}
