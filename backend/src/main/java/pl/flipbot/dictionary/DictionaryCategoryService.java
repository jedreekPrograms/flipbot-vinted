package pl.flipbot.dictionary;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.dictionary.dto.CreateDictionaryCategoryRequest;
import pl.flipbot.dictionary.dto.DictionaryCategoryResponse;
import pl.flipbot.marketstats.MarketStatsService;

@Service
@RequiredArgsConstructor
public class DictionaryCategoryService {
    private final DictionaryModelRepository modelRepository;
    private final DictionaryBotUsage botUsage;
    private final MarketStatsService marketStatsService;
    private static final String PATH_SEPARATOR = " > ";
    private final DictionaryCategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public List<DictionaryCategoryResponse> getAllCategories() {
        return categoryRepository
                .findAllByOrderByPathAsc()
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional
    public DictionaryCategoryResponse createCategory(CreateDictionaryCategoryRequest request) {
        List<String> normalizedCategoryPath = normalizeCategoryPath(request.getCategoryPath());
        String normalizedPath = String.join(PATH_SEPARATOR, normalizedCategoryPath);
        if (categoryRepository
                .existsByPathIgnoreCase(normalizedPath)) {
            throw new DictionaryEntryAlreadyExistsException("Category already exists: "
                    + normalizedPath);
        }
        String categoryName = normalizedCategoryPath.get(normalizedCategoryPath.size() - 1);
        DictionaryCategory category = DictionaryCategory.builder()
                .name(categoryName)
                .path(normalizedPath)
                .build();
        try {
            DictionaryCategory savedCategory = categoryRepository
                    .saveAndFlush(category);
            return map(savedCategory);
        } catch (DataIntegrityViolationException exception) {
            throw new DictionaryEntryAlreadyExistsException("Category already exists: "
                    + normalizedPath);
        }
    }

    private List<String> normalizeCategoryPath(List<String> categoryPath) {
        if (categoryPath == null
                || categoryPath.isEmpty()) {
            throw new InvalidRequestException("Category path cannot be empty");
        }
        return categoryPath.stream()
                .map(this::normalizePathElement)
                .toList();
    }

    private String normalizePathElement(String pathElement) {
        if (pathElement == null) {
            throw new InvalidRequestException("Category path element cannot be null");
        }
        String normalizedElement = pathElement
                .trim()
                .replaceAll("\\s+", " ");
        if (normalizedElement.isBlank()) {
            throw new InvalidRequestException("Category path element cannot be blank");
        }
        if (normalizedElement.contains(">")) {
            throw new InvalidRequestException("Category path element cannot contain  the '>' character: "
                    + normalizedElement);
        }
        return normalizedElement;
    }

    private DictionaryCategoryResponse map(DictionaryCategory category) {
        List<String> categoryPath = splitPath(category.getPath());
        return new DictionaryCategoryResponse(category.getId(), category.getName(), category.getPath(), categoryPath);
    }

    private List<String> splitPath(String path) {
        if (path == null
                || path.isBlank()) {
            throw new IllegalStateException("Stored category path cannot be blank");
        }
        return DictionaryValues.splitPath(path);
    }

    @Transactional
    public DictionaryCategoryResponse updateCategory(Long categoryId, CreateDictionaryCategoryRequest request) {
        DictionaryCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Category was not found: " + categoryId));
        List<String> oldPath = DictionaryValues.splitPath(category.getPath());
        List<String> newPath = DictionaryValues.normalizeCategoryPath(request.getCategoryPath());
        List<Long> observerModelIds = modelRepository.findAll()
                .stream()
                .filter(model -> model.getCategory() != null)
                .filter(model -> categoryId.equals(model.getCategory().getId()))
                .map(DictionaryModel::getId)
                .toList();
        String newStoredPath = String.join(PATH_SEPARATOR, newPath);
        boolean duplicate = categoryRepository.findAll().stream()
                .anyMatch(other -> !other.getId().equals(categoryId)
                        && other.getPath().equalsIgnoreCase(newStoredPath));
        if (duplicate) {
            throw new DictionaryEntryAlreadyExistsException("Category already exists: " + newStoredPath);
        }
        List<BotConfiguration> affected = botUsage.usingCategory(oldPath);
        botUsage.requireEditable(affected);
        category.setPath(newStoredPath);
        category.setName(newPath.get(newPath.size() - 1));
        affected.forEach(configuration ->
                configuration.setCategoryPath(new ArrayList<>(newPath)));
        observerModelIds.forEach(marketStatsService::resetModelTracking);
        return new DictionaryCategoryResponse(category.getId(), category.getName(), category.getPath(), newPath);
    }

    @Transactional
    public void deleteCategory(Long categoryId) {
        DictionaryCategory category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Category was not found: " + categoryId));
        List<String> categoryPath = DictionaryValues.splitPath(category.getPath());
        boolean usedByBot = !botUsage.usingCategory(categoryPath).isEmpty();
        if (usedByBot) {
            throw new IllegalStateException("Category cannot be deleted because it is used by at least one bot configuration.");
        }
        categoryRepository.delete(category);
    }
}
