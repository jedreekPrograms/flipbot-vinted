package pl.flipbot.dictionary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import pl.flipbot.bot.Bot;
import pl.flipbot.bot.BotStatus;
import pl.flipbot.bot.configuration.BotConfiguration;
import pl.flipbot.bot.configuration.BotConfigurationRepository;
import pl.flipbot.bot.configuration.TargetMode;
import pl.flipbot.dictionary.dto.CreateDictionaryBrandRequest;
import pl.flipbot.dictionary.dto.CreateDictionaryCategoryRequest;
import pl.flipbot.dictionary.dto.CreateDictionaryModelRequest;
import pl.flipbot.dictionary.dto.UpdateDictionaryModelCategoryRequest;
import pl.flipbot.dictionary.dto.UpdateDictionaryModelPricingRequest;
import pl.flipbot.listing.Listing;
import pl.flipbot.listing.ListingRepository;
import pl.flipbot.listing.ListingStatus;
import pl.flipbot.marketstats.MarketStatsService;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DictionaryEditingRegressionTest {
    private final DictionaryBrandRepository brands = mock(DictionaryBrandRepository.class);
    private final DictionaryModelRepository models = mock(DictionaryModelRepository.class);
    private final DictionaryCategoryRepository categories = mock(DictionaryCategoryRepository.class);
    private final BotConfigurationRepository configurations = mock(BotConfigurationRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final MarketStatsService market = mock(MarketStatsService.class);
    private final DictionaryBotUsage usage = new DictionaryBotUsage(configurations, listings);
    private final DictionaryBrandService brandService = new DictionaryBrandService(models, usage, brands);
    private final DictionaryModelService modelService = new DictionaryModelService(usage, market, models, brands, categories);
    private final DictionaryCategoryService categoryService = new DictionaryCategoryService(models, usage, market, categories);
    private final DictionaryBrand brand = DictionaryBrand.builder().id(1L).name("Apple").build();
    private final DictionaryModel model = DictionaryModel.builder().id(2L).name("iPhone").brand(brand).build();

    @Test
    void brandEditKeepsExistingCaseInsensitiveMainProductLinks() {
        BotConfiguration matching = configuration(" apple ", "iPhone", TargetMode.VINTED_MODEL);
        BotConfiguration unrelated = configuration("Samsung", "Galaxy", TargetMode.VINTED_MODEL);
        unrelated.getBot().setStatus(BotStatus.RUNNING);
        when(brands.findById(1L)).thenReturn(Optional.of(brand));
        when(brands.findAll()).thenReturn(List.of(brand));
        when(configurations.findAll()).thenReturn(List.of(matching, unrelated));
        CreateDictionaryBrandRequest request = new CreateDictionaryBrandRequest();
        request.setName("  Apple   Computer  ");

        assertEquals("Apple Computer", brandService.updateBrand(1L, request).name());
        assertEquals("Apple Computer", matching.getBrand());
        assertEquals("Samsung", unrelated.getBrand());
        verify(listings).findByBotIdAndStatusOrderByIdAsc(7L, ListingStatus.NEGOTIATING);
        verify(listings).findByBotIdAndStatusOrderByIdAsc(7L, ListingStatus.ACTION_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(value = ListingStatus.class, names = {"NEGOTIATING", "ACTION_REQUIRED"})
    void editsStillRejectActiveConversationsBeforeMutatingNames(ListingStatus activeStatus) {
        BotConfiguration configuration = configuration("Apple", "iPhone", TargetMode.VINTED_MODEL);
        when(brands.findById(1L)).thenReturn(Optional.of(brand));
        when(brands.findAll()).thenReturn(List.of(brand));
        when(configurations.findAll()).thenReturn(List.of(configuration));
        when(listings.findByBotIdAndStatusOrderByIdAsc(7L, activeStatus)).thenReturn(List.of(mock(Listing.class)));
        CreateDictionaryBrandRequest request = new CreateDictionaryBrandRequest();
        request.setName("Changed");
        assertThrows(IllegalStateException.class, () -> brandService.updateBrand(1L, request));
        assertEquals("Apple", brand.getName());
        assertEquals("Apple", configuration.getBrand());
        verify(listings).findByBotIdAndStatusOrderByIdAsc(7L, ListingStatus.NEGOTIATING);
        verify(listings).findByBotIdAndStatusOrderByIdAsc(7L, ListingStatus.ACTION_REQUIRED);
    }

    @Test
    void runningBotIsRejectedWithoutConversationReads() {
        BotConfiguration configuration = configuration("Apple", "iPhone", TargetMode.VINTED_MODEL);
        configuration.getBot().setStatus(BotStatus.RUNNING);
        assertThrows(IllegalStateException.class, () -> usage.requireEditable(List.of(configuration)));
        verifyNoInteractions(listings);
    }

    @ParameterizedTest
    @EnumSource(TargetMode.class)
    void modelDeletionKeepsBothTargetModesInItsUsageCheck(TargetMode mode) {
        when(models.findById(2L)).thenReturn(Optional.of(model));
        when(configurations.findAll()).thenReturn(List.of(configuration(" APPLE ", " iphone ", mode)));
        assertThrows(IllegalStateException.class, () -> modelService.deleteModel(1L, 2L));
        verify(models, never()).delete(any());
    }

    @Test
    void targetModeChangeKeepsMatchingRulesAndObserverReset() {
        BotConfiguration matching = configuration("Apple", "iPhone", TargetMode.VINTED_MODEL);
        BotConfiguration unrelated = configuration("Apple", "Other", TargetMode.VINTED_MODEL);
        when(brands.findById(1L)).thenReturn(Optional.of(brand));
        when(models.findById(2L)).thenReturn(Optional.of(model));
        when(models.findAllByBrand_IdOrderByNameAsc(1L)).thenReturn(List.of(model));
        when(configurations.findAll()).thenReturn(List.of(matching, unrelated));
        CreateDictionaryModelRequest request = new CreateDictionaryModelRequest();
        request.setName("  iPhone   Pro ");
        request.setTargetMode(TargetMode.SEARCH_QUERY);
        var response = modelService.updateModel(1L, 2L, request);
        assertEquals("iPhone Pro", response.name());
        assertNull(matching.getModel());
        assertEquals("iPhone Pro", matching.getSearchQuery());
        assertEquals(TargetMode.SEARCH_QUERY, matching.getTargetMode());
        assertEquals("Other", unrelated.getModel());
        verify(market).resetModelTracking(2L);
    }

    @Test
    void cosmeticModelRenameDoesNotResetOrBlockARunningBot() {
        BotConfiguration configuration = configuration("Apple", "iPhone", TargetMode.VINTED_MODEL);
        configuration.getBot().setStatus(BotStatus.RUNNING);
        when(brands.findById(1L)).thenReturn(Optional.of(brand));
        when(models.findById(2L)).thenReturn(Optional.of(model));
        when(models.findAllByBrand_IdOrderByNameAsc(1L)).thenReturn(List.of(model));
        when(configurations.findAll()).thenReturn(List.of(configuration));
        CreateDictionaryModelRequest request = new CreateDictionaryModelRequest();
        request.setName(" iphone ");
        modelService.updateModel(1L, 2L, request);
        assertEquals("iphone", model.getName());
        assertEquals("iPhone", configuration.getModel());
        verifyNoInteractions(listings, market);
    }

    @Test
    void pricingKeepsTheRowLockAndNumericRangeComparison() {
        model.setMarketMinPrice(new BigDecimal("100.0"));
        model.setMarketMaxPrice(new BigDecimal("200.0"));
        when(models.findByIdForUpdate(2L)).thenReturn(Optional.of(model));
        UpdateDictionaryModelPricingRequest request = new UpdateDictionaryModelPricingRequest();
        request.setProposedOfferPrice(new BigDecimal("110"));
        request.setMarketMinPrice(new BigDecimal("100.00"));
        request.setMarketMaxPrice(new BigDecimal("200.00"));
        assertEquals(new BigDecimal("110"), modelService.updateModelPricing(1L, 2L, request).proposedOfferPrice());
        verify(models, never()).findById(any());
        verifyNoInteractions(market);
        request.setMarketMinPrice(new BigDecimal("101"));
        modelService.updateModelPricing(1L, 2L, request);
        verify(market).resetModelTracking(2L);
    }

    @Test
    void categoryEditKeepsPathMatchingAndOnlyResetsModelsOfThatCategory() {
        DictionaryCategory category = DictionaryCategory.builder().id(3L).name("Phones").path("Electronics > Phones").build();
        model.setCategory(category);
        DictionaryModel other = DictionaryModel.builder().id(4L).brand(brand).name("Other").build();
        BotConfiguration matching = configuration("Apple", "iPhone", TargetMode.VINTED_MODEL);
        matching.setCategoryPath(List.of(" electronics ", "phones"));
        when(categories.findById(3L)).thenReturn(Optional.of(category));
        when(categories.findAll()).thenReturn(List.of(category));
        when(models.findAll()).thenReturn(List.of(other, model));
        when(configurations.findAll()).thenReturn(List.of(matching));
        CreateDictionaryCategoryRequest request = new CreateDictionaryCategoryRequest();
        request.setCategoryPath(List.of(" Electronics ", " Smart   phones "));
        var response = categoryService.updateCategory(3L, request);
        assertEquals("Electronics > Smart phones", response.path());
        assertEquals(List.of("Electronics", "Smart phones"), matching.getCategoryPath());
        verify(market).resetModelTracking(2L);
        verifyNoMoreInteractions(market);
    }

    @Test
    void modelCategoryEndpointKeepsItsExistingScope() {
        DictionaryCategory category = DictionaryCategory.builder().id(3L).name("Phones").path("Electronics > Phones").build();
        when(models.findById(2L)).thenReturn(Optional.of(model));
        when(categories.findById(3L)).thenReturn(Optional.of(category));
        UpdateDictionaryModelCategoryRequest request = new UpdateDictionaryModelCategoryRequest();
        request.setCategoryId(3L);
        assertEquals(List.of("Electronics", "Phones"), modelService.updateModelCategory(1L, 2L, request).categoryPathElements());
        verifyNoInteractions(configurations, listings, market);
    }

    @Test
    void deletingABrandWithModelsStopsBeforeReadingBotConfigurations() {
        when(brands.findById(1L)).thenReturn(Optional.of(brand));
        when(models.findAllByBrand_IdOrderByNameAsc(1L)).thenReturn(List.of(model));
        assertThrows(IllegalStateException.class, () -> brandService.deleteBrand(1L));
        verifyNoInteractions(configurations);
        verify(brands, never()).delete(any());
    }

    private BotConfiguration configuration(String brand, String name, TargetMode mode) {
        return BotConfiguration.builder().bot(Bot.builder().id(7L).status(BotStatus.STOPPED).build())
                .brand(brand).targetMode(mode).model(mode == TargetMode.VINTED_MODEL ? name : null)
                .searchQuery(mode == TargetMode.SEARCH_QUERY ? name : null).build();
    }
}
