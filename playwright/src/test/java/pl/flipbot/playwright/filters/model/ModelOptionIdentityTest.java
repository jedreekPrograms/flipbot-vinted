package pl.flipbot.playwright.filters.model;

import org.junit.Test;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ModelOptionIdentityTest {

    @Test
    public void exactLabelAcceptsOnlyTheRequestedModel() {
        assertTrue(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy S25", "Galaxy S25"));
        assertTrue(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy S25", "  Galaxy S25  "));
        for (String other : java.util.List.of("Galaxy S25 Edge", "Galaxy S25 Ultra", "Galaxy S25 FE",
                "Galaxy S25+", "Galaxy S24")) {
            assertFalse(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy S25", other));
        }
    }

    @Test
    public void exactS26IdentityNeverAcceptsUltraOrPlus() {
        assertTrue(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy S26", "Galaxy S26\n42 przedmioty"));
        for (String other : java.util.List.of("Galaxy S26 Ultra", "Galaxy S26+", "Galaxy S26 Plus",
                "Galaxy S26 Ultra\n42 przedmioty")) {
            assertFalse(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy S26", other));
        }
    }

    @Test
    public void exactLabelIsCaseInsensitive() {
        assertTrue(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy Tab S11 Ultra", "GALAXY TAB S11 ULTRA"));
        assertFalse(ModelOptionIdentity.exactVisibleModelLabelMatches("Galaxy Tab S11 Ultra", "Galaxy Tab S11"));
    }

    @Test
    public void collectionSelectorsRejectMissingOrNonNumericIdentity() {
        for (String invalid : new String[]{null, "", "9977--suffix", "9977']"}) {
            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> ModelOptionIdentity.canonicalModelRowTestId(invalid));
            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> ModelOptionIdentity.exactModelCheckboxSelector(invalid));
            org.junit.Assert.assertThrows(IllegalArgumentException.class,
                    () -> ModelOptionIdentity.exactModelSuffixTestId(invalid));
        }
    }

    @Test
    public void modelCollectionIdIsDerivedOnlyFromExpectedVintedTestId() {
        assertEquals(
                "9976",
                ModelOptionIdentity.modelCollectionIdFromTestId(
                        "selectable-item-brand_collection-9976"
                )
        );
        assertNull(ModelOptionIdentity.modelCollectionIdFromTestId("9976"));
        assertNull(
                ModelOptionIdentity.modelCollectionIdFromTestId(
                        "selectable-item-brand_collection-Galaxy-S25"
                )
        );
    }

    @Test
    public void exactModelCheckboxSelectorTargetsTheProvenCollectionOnly() {
        assertEquals(
                "input[type='checkbox'][name='brand_collection_ids[]'][value='9977']",
                ModelOptionIdentity.exactModelCheckboxSelector("9977")
        );
    }

    @Test
    public void exactModelSuffixTestIdTargetsTheClickableSuffixForProvenCollection() {
        assertEquals(
                "selectable-item-brand_collection-9977--suffix",
                ModelOptionIdentity.exactModelSuffixTestId("9977")
        );
    }

    @Test
    public void titleChildEvidenceAlwaysResolvesBackToCanonicalSelectableRow() {
        String collectionId = ModelOptionIdentity.modelCollectionIdFromTestId(
                "selectable-item-brand_collection-10632--title"
        );

        assertEquals("10632", collectionId);
        assertEquals(
                "selectable-item-brand_collection-10632",
                ModelOptionIdentity.canonicalModelRowTestId(collectionId)
        );
    }

    @Test
    public void modelCollectionIdAcceptsOnlyTheKnownVintedTitleChildSuffix() {
        assertEquals(
                "10632",
                ModelOptionIdentity.modelCollectionIdFromTestId(
                        "selectable-item-brand_collection-10632--title"
                )
        );

        assertNull(
                ModelOptionIdentity.modelCollectionIdFromTestId(
                        "selectable-item-brand_collection-10632--description"
                )
        );
        assertNull(
                ModelOptionIdentity.modelCollectionIdFromTestId(
                        "selectable-item-brand_collection-10632--title--extra"
                )
        );
    }

    @Test
    public void visibleModelMatcherAcceptsExactLabelWithCountMetadata() {
        assertTrue(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25\n123 przedmioty"
                )
        );

        assertTrue(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "  GALAXY S25  \n  123 przedmiotów  "
                )
        );

        assertTrue(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25 123 przedmioty"
                )
        );
    }

    @Test
    public void visibleModelMatcherRejectsEveryS25Variant() {
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25 Edge\n50 przedmiotów"
                )
        );
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25 Ultra\n50 przedmiotów"
                )
        );
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25 FE\n50 przedmiotów"
                )
        );
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25+\n50 przedmiotów"
                )
        );
    }

    @Test
    public void visibleModelMatcherRejectsHighlightedBaseNameInsideVariantRow() {
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25\nEdge\n50 przedmiotów"
                )
        );
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25\nUltra\n50 przedmiotów"
                )
        );
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25\nFE\n50 przedmiotów"
                )
        );
    }

    @Test
    public void visibleModelMatcherDoesNotTreatAProductSuffixAsCountMetadata() {
        assertFalse(
                ModelOptionIdentity.exactVisibleModelLabelMatches(
                        "Galaxy S25",
                        "Galaxy S25 5G"
                )
        );
    }
}
