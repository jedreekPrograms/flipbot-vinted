package pl.flipbot.dictionary;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.flipbot.exception.InvalidRequestException;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.dictionary.dto.CreateDictionaryBrandRequest;
import pl.flipbot.dictionary.dto.DictionaryBrandResponse;


@Service
@RequiredArgsConstructor
public class DictionaryBrandService {
    private final DictionaryModelRepository modelRepository;
    private final DictionaryBotUsage botUsage;
    private final DictionaryBrandRepository brandRepository;

    @Transactional(readOnly = true)
    public List<DictionaryBrandResponse> getAllBrands() {
        return brandRepository
                .findAllByOrderByNameAsc()
                .stream()
                .map(this::map)
                .toList();
    }

    @Transactional
    public DictionaryBrandResponse createBrand(CreateDictionaryBrandRequest request) {
        String normalizedName = normalizeName(request.getName());
        if (brandRepository
                .existsByNameIgnoreCase(normalizedName)) {
            throw new DictionaryEntryAlreadyExistsException("Brand already exists: "
                            + normalizedName);
        }
        DictionaryBrand brand = DictionaryBrand.builder()
                .name(normalizedName)
                .build();
        try {
            DictionaryBrand savedBrand = brandRepository.saveAndFlush(brand);
            return map(savedBrand);
        } catch (DataIntegrityViolationException exception) {
            /*
             * To dodatkowe zabezpieczenie na wypadek, gdyby dwa żądania
             * próbowały dodać tę samą markę w tym samym momencie.
             */
            throw new DictionaryEntryAlreadyExistsException("Brand already exists: "
                            + normalizedName);
        }
    }

    private String normalizeName(String name) {
        if (name == null) {
            throw new InvalidRequestException("Brand name cannot be null");
        }
        /*
         * Usuwamy spacje z początku i końca oraz zamieniamy wiele
         * kolejnych spacji na jedną.
         *
         * "  Samsung   Electronics  "
         * zamieni się na:
         * "Samsung Electronics"
         */
        return name
                .trim()
                .replaceAll("\\s+", " ");
    }

    private DictionaryBrandResponse map(DictionaryBrand brand) {
        return new DictionaryBrandResponse(brand.getId(), brand.getName());
    }

    @Transactional
    public DictionaryBrandResponse updateBrand(Long brandId, CreateDictionaryBrandRequest request) {
        DictionaryBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Brand was not found: " + brandId));
        String oldName = brand.getName();
        String newName = DictionaryValues.normalizeName(request.getName(), "Brand");
        boolean duplicate = brandRepository.findAll().stream()
                .anyMatch(other -> !other.getId().equals(brandId)
                        && other.getName().equalsIgnoreCase(newName));
        if (duplicate) {
            throw new DictionaryEntryAlreadyExistsException("Brand already exists: " + newName);
        }
        List<BotConfiguration> affected = botUsage.usingBrand(oldName);
        botUsage.requireEditable(affected);
        brand.setName(newName);
        affected.forEach(configuration -> configuration.setBrand(newName));
        return new DictionaryBrandResponse(brand.getId(), brand.getName());
    }

    @Transactional
    public void deleteBrand(Long brandId) {
        DictionaryBrand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new DictionaryEntryNotFoundException("Brand was not found: " + brandId));
        if (!modelRepository.findAllByBrand_IdOrderByNameAsc(brandId).isEmpty()) {
            throw new IllegalStateException("Brand cannot be deleted while it still has models. Delete or move the models first.");
        }
        boolean usedByBot = !botUsage.usingBrand(brand.getName()).isEmpty();
        if (usedByBot) {
            throw new IllegalStateException("Brand cannot be deleted because it is used by at least one bot configuration.");
        }
        brandRepository.delete(brand);
    }
}
