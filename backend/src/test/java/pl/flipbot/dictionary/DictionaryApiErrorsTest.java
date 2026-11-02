package pl.flipbot.dictionary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import pl.flipbot.exception.GlobalExceptionHandler;
import pl.flipbot.marketstats.MarketStatsService;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DictionaryApiErrorsTest {
    private MockMvc mvc;
    private DictionaryModelRepository models;
    private DictionaryBrandRepository brands;

    @BeforeEach void setUp() {
        models = mock(DictionaryModelRepository.class);
        brands = mock(DictionaryBrandRepository.class);
        var service = new DictionaryModelService(mock(DictionaryBotUsage.class), mock(MarketStatsService.class),
                models, brands, mock(DictionaryCategoryRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(new DictionaryController(mock(DictionaryBrandService.class),
                service, mock(DictionaryCategoryService.class)), new ErrorProbe())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void missingModelCategoryAndReversedMarketRangeAreBadRequestsWithoutWrites() throws Exception {
        var brand = DictionaryBrand.builder().id(1L).name("Brand").build();
        when(brands.findById(1L)).thenReturn(Optional.of(brand));
        when(models.findByIdForUpdate(2L)).thenReturn(Optional.of(DictionaryModel.builder().id(2L).brand(brand).build()));
        mvc.perform(post("/api/dictionaries/brands/1/models").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Model\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Dictionary model category is required."));
        mvc.perform(patch("/api/dictionaries/brands/1/models/2/pricing").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"marketMinPrice\":100,\"marketMaxPrice\":90}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message")
                        .value("Market minimum price cannot be greater than market maximum price."));
        verify(models, never()).saveAndFlush(any());
    }

    @Test void malformedJsonInvalidPathAndFieldValidationStayClientErrors() throws Exception {
        mvc.perform(post("/api/dictionaries/brands/1/models").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Nieprawidłowe dane żądania."));
        mvc.perform(get("/api/dictionaries/brands/not-a-number/models"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/dictionaries/brands/1/models/2/pricing").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proposedOfferPrice\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.proposedOfferPrice").exists());
    }

    @Test void missingConflictAndUnexpectedErrorsRemainDistinctAndDoNotExposeInternalDetails() throws Exception {
        mvc.perform(get("/test/error/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/test/error/conflict")).andExpect(status().isConflict());
        for (String kind : new String[]{"internal", "bug"}) {
            mvc.perform(get("/test/error/" + kind)).andExpect(status().isInternalServerError())
                    .andExpect(content().json("{\"message\":\"Wystąpił nieoczekiwany błąd serwera.\"}"));
        }
    }

    @RestController static class ErrorProbe {
        @GetMapping("/test/error/{kind}") String error(@PathVariable("kind") String kind) {
            throw switch (kind) {
                case "missing" -> new NoSuchElementException("missing");
                case "conflict" -> new IllegalStateException("conflict");
                case "internal" -> new IllegalArgumentException("private internal precondition");
                default -> new NullPointerException("private internals");
            };
        }
    }
}
