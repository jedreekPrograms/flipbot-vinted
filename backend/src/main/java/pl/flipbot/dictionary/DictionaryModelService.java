package pl.flipbot.dictionary;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.TargetMode;
import pl.flipbot.dictionary.dto.CreateDictionaryModelRequest;
import pl.flipbot.dictionary.dto.DictionaryModelResponse;
import pl.flipbot.dictionary.dto.UpdateDictionaryModelCategoryRequest;
import pl.flipbot.dictionary.dto.UpdateDictionaryModelPricingRequest;
import pl.flipbot.marketstats.MarketStatsService;

import static pl.flipbot.dictionary.DictionaryValues.sameText;

@Service
@RequiredArgsConstructor
public class DictionaryModelService {
    private final DictionaryBotUsage botUsage;
    private final MarketStatsService marketStatsService;
    private final DictionaryModelRepository modelRepository;
    private final DictionaryBrandRepository brandRepository;
    private final DictionaryCategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<DictionaryModelResponse> getModelsByBrand(Long brandId) {
        validateBrandExists(brandId);
        return modelRepository
                .findAllByBrand_IdOrderByNameAsc(brandId)
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional
    public DictionaryModelResponse createModel(Long brandId, CreateDictionaryModelRequest request) {
        DictionaryBrand brand = brandRepository
                .findById(brandId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Brand was not found: "
                                                + brandId));
        Long categoryId = request.getCategoryId();
        if (categoryId == null || categoryId <= 0) {
            throw new InvalidRequestException("Dictionary model category is required.");
        }
        DictionaryCategory category = categoryRepository
                .findById(categoryId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Category was not found: "
                                                + categoryId));
        String normalizedName = DictionaryValues.normalizeName(request.getName(), "Model");
        boolean modelAlreadyExists = modelRepository
                .existsByBrand_IdAndNameIgnoreCase(brandId, normalizedName);
        if (modelAlreadyExists) {
            throw new DictionaryEntryAlreadyExistsException("Model already exists for brand "
                            + brand.getName()
                            + ": "
                            + normalizedName);
        }
        DictionaryModel model = DictionaryModel.builder()
                .name(normalizedName)
                .brand(brand)
                .category(category)
                .targetMode(request.getTargetMode() == null
                                        ? TargetMode.VINTED_MODEL
                                        : request.getTargetMode())
                .build();
        try {
            DictionaryModel savedModel = modelRepository
                    .saveAndFlush(model);
            return map(savedModel);
        } catch (DataIntegrityViolationException exception) {
            throw new DictionaryEntryAlreadyExistsException("Model already exists for brand "
                            + brand.getName()
                            + ": "
                            + normalizedName);
        }
    }

    private void validateBrandExists(Long brandId) {
        if (!brandRepository.existsById(brandId)) {
            throw new DictionaryEntryNotFoundException("Brand was not found: "
                            + brandId);
        }
    }

    DictionaryModelResponse map(DictionaryModel model) {
        DictionaryCategory category = model.getCategory();
        return new DictionaryModelResponse(
                model.getId(), model.getName(), model.getBrand().getId(), model.getBrand().getName(),
                category == null ? null : category.getId(),
                category == null ? null : category.getName(),
                category == null ? null : category.getPath(),
                category == null ? List.of() : DictionaryValues.splitPath(category.getPath()),
                model.getTargetMode() == null ? TargetMode.VINTED_MODEL : model.getTargetMode(),
                model.getProposedOfferPrice(), model.getExpectedResalePrice(),
                model.getMarketMinPrice(), model.getMarketMaxPrice()
        );
    }

    @Transactional
    public DictionaryModelResponse updateModel(Long brandId, Long modelId, CreateDictionaryModelRequest request) {
        DictionaryBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Brand was not found: " + brandId));
        DictionaryModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Model was not found: " + modelId));
        ensureModelBelongsToBrand(model, brandId);
        String oldName = model.getName();
        String newName = DictionaryValues.normalizeName(request.getName(), "Model");
        TargetMode oldTargetMode = resolveModelTargetMode(model);
        TargetMode newTargetMode = request.getTargetMode() == null
                ? oldTargetMode
                : request.getTargetMode();
        boolean duplicate = modelRepository
                .findAllByBrand_IdOrderByNameAsc(brandId)
                .stream()
                .anyMatch(other -> !other.getId().equals(modelId)
                        && other.getName().equalsIgnoreCase(newName));
        if (duplicate) {
            throw new DictionaryEntryAlreadyExistsException("Model already exists for brand "
                            + brand.getName()
                            + ": "
                            + newName);
        }
        List<BotConfiguration> affected = botUsage.usingModel(brand.getName(), oldName);
        boolean targetDefinitionChanged = !sameText(oldName, newName)
                        || oldTargetMode != newTargetMode;
        if (targetDefinitionChanged) {
            botUsage.requireEditable(affected);
        }
        model.setName(newName);
        model.setTargetMode(newTargetMode);
        if (targetDefinitionChanged) {
            affected.forEach(configuration -> applyModelTarget(configuration, newTargetMode, newName));
            marketStatsService.resetModelTracking(modelId);
        }
        return map(model);
    }

    @Transactional
    public DictionaryModelResponse updateModelPricing(Long brandId, Long modelId, UpdateDictionaryModelPricingRequest request) {
        DictionaryModel model = modelRepository.findByIdForUpdate(modelId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Model was not found: " + modelId));
        ensureModelBelongsToBrand(model, brandId);
        BigDecimal marketMinPrice = request.getMarketMinPrice();
        BigDecimal marketMaxPrice = request.getMarketMaxPrice();
        if (marketMinPrice != null
                && marketMaxPrice != null
                && marketMinPrice.compareTo(marketMaxPrice) > 0) {
            throw new InvalidRequestException("Market minimum price cannot be greater than market maximum price.");
        }
        boolean marketRangeChanged = !samePrice(model.getMarketMinPrice(), marketMinPrice)
                        || !samePrice(model.getMarketMaxPrice(), marketMaxPrice);
        model.setProposedOfferPrice(request.getProposedOfferPrice());
        model.setExpectedResalePrice(request.getExpectedResalePrice());
        model.setMarketMinPrice(marketMinPrice);
        model.setMarketMaxPrice(marketMaxPrice);
        if (marketRangeChanged) {
            marketStatsService.resetModelTracking(modelId);
        }
        return map(model);
    }

    @Transactional
    public void deleteModel(Long brandId, Long modelId) {
        DictionaryModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Model was not found: " + modelId));
        ensureModelBelongsToBrand(model, brandId);
        boolean usedByBot = !botUsage.usingModel(model.getBrand().getName(), model.getName()).isEmpty();
        if (usedByBot) {
            throw new IllegalStateException("Model cannot be deleted because it is used by at least one bot configuration.");
        }
        modelRepository.delete(model);
    }

    private void ensureModelBelongsToBrand(DictionaryModel model, Long brandId) {
        if (!model.getBrand().getId().equals(brandId)) {
            throw new DictionaryEntryNotFoundException("Model "
                            + model.getId()
                            + " does not belong to brand "
                            + brandId);
        }
    }

    private void applyModelTarget(BotConfiguration configuration, TargetMode targetMode, String modelName) {
        configuration.setTargetMode(targetMode);
        if (targetMode == TargetMode.SEARCH_QUERY) {
            configuration.setModel(null);
            configuration.setSearchQuery(modelName);
            return;
        }
        configuration.setModel(modelName);
        configuration.setSearchQuery(null);
    }

    private TargetMode resolveModelTargetMode(DictionaryModel model) {
        return model.getTargetMode() == null
                ? TargetMode.VINTED_MODEL
                : model.getTargetMode();
    }

    private boolean samePrice(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    @Transactional
    public DictionaryModelResponse updateModelCategory(Long brandId, Long modelId, UpdateDictionaryModelCategoryRequest request) {
        DictionaryModel model = modelRepository.findById(modelId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Model was not found: " + modelId));
        if (!model.getBrand().getId().equals(brandId)) {
            throw new DictionaryEntryNotFoundException("Model " + modelId + " does not belong to brand " + brandId);
        }
        DictionaryCategory category = categoryRepository
                .findById(request.getCategoryId())
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Category was not found: " + request.getCategoryId()));
        model.setCategory(category);
        return map(model);
    }
}
