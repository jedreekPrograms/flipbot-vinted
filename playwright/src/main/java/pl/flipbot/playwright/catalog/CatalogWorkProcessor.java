package pl.flipbot.playwright.catalog;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import pl.flipbot.playwright.api.listing.ListingClient;
import pl.flipbot.playwright.api.listing.ListingStatusUpdater;
import pl.flipbot.playwright.api.listing.TargetBoundListingClient;
import pl.flipbot.playwright.api.quota.OfferQuotaClient;
import pl.flipbot.playwright.api.quota.RunScopedOfferQuotaClient;
import pl.flipbot.playwright.context.BotContext;
import pl.flipbot.playwright.filters.FilterService;
import pl.flipbot.playwright.marketplace.MarketplaceNavigator;
import pl.flipbot.playwright.model.BotConfigurationDto;
import pl.flipbot.playwright.model.BotProductExecutionPlan;
import pl.flipbot.playwright.negotiation.CatalogDetailInspectionBudget;
import pl.flipbot.playwright.negotiation.NewNegotiationProcessor;

@Slf4j
public class CatalogWorkProcessor {

    private static final int MAX_DETAIL_PAGE_REQUESTS_PER_CATALOG_RUN = 25;

    private static final ConcurrentMap<Long, Integer> NEXT_PRODUCT_OFFSET =
            new ConcurrentHashMap<>();

    private final BotContext context;
    private final ListingClient listingClient;
    private final ListingStatusUpdater listingStatusUpdater;
    private final boolean realOffersEnabled;
    private final int maxRealOffersPerRun;
    private final MarketplaceNavigator marketplaceNavigator;
    private final FilterService filterService;
    private final RunScopedOfferQuotaClient runQuota;

    public CatalogWorkProcessor(
            BotContext context,
            ListingClient listingClient,
            OfferQuotaClient offerQuotaClient,
            ListingStatusUpdater listingStatusUpdater,
            boolean realOffersEnabled,
            int maxRealOffersPerRun
    ) {
        this.context = context;
        this.listingClient = listingClient;
        this.listingStatusUpdater = listingStatusUpdater;
        this.realOffersEnabled = realOffersEnabled;
        this.maxRealOffersPerRun = maxRealOffersPerRun;
        this.marketplaceNavigator = new MarketplaceNavigator(context);
        this.filterService = new FilterService(context);
        this.runQuota = new RunScopedOfferQuotaClient(
                offerQuotaClient,
                maxRealOffersPerRun
        );
    }

    public void process() {
        Long botId = context.getBot().getId();
        BotConfigurationDto main = context.getBot().getConfiguration();
        if (main == null) {
            throw new IllegalStateException("Bot configuration is missing");
        }

        CatalogDetailInspectionBudget detailInspectionBudget =
                new CatalogDetailInspectionBudget(
                        MAX_DETAIL_PAGE_REQUESTS_PER_CATALOG_RUN
                );

        List<BotProductExecutionPlan.Target> targets =
                BotProductExecutionPlan.activeCatalogTargets(context.getBot());
        int offset = targets.size() <= 1
                ? 0
                : NEXT_PRODUCT_OFFSET.compute(
                        botId,
                        (ignored, previous) -> previous == null
                                ? 0
                                : (previous + 1) % targets.size()
                );

        log.info(
                "[MULTI PRODUCT] Bot {} scanning {} product(s), offset={}. Shared live item-detail budget={}/{}.",
                botId,
                targets.size(),
                offset,
                detailInspectionBudget.used(),
                detailInspectionBudget.limit()
        );

        try {
            for (int i = 0; i < targets.size(); i++) {
                BotProductExecutionPlan.Target target = targets.get(
                        (offset + i) % targets.size()
                );
                processTarget(target, detailInspectionBudget);
            }
        } finally {
            log.info(
                    "[CATALOG DETAIL BUDGET] Bot {} catalog run finished with {}/{} live item-detail request(s) consumed; remaining={}.",
                    botId,
                    detailInspectionBudget.used(),
                    detailInspectionBudget.limit(),
                    detailInspectionBudget.remaining()
            );
        }

    }

    private void processTarget(
            BotProductExecutionPlan.Target target,
            CatalogDetailInspectionBudget detailInspectionBudget
    ) {
        BotContext productContext = context.forProduct(target.configuration());
        Long botId = productContext.getBot().getId();
        String label = target.additionalTargetId() == null
                ? "MAIN"
                : "ADDITIONAL:" + target.additionalTargetId();

        log.info(
                "[MULTI PRODUCT] Bot {} scanning {} brand='{}', model='{}'. Shared live item-detail budget before product={}/{}.",
                botId,
                label,
                target.configuration().getBrand(),
                target.configuration().getModel(),
                detailInspectionBudget.used(),
                detailInspectionBudget.limit()
        );

        marketplaceNavigator.goToCatalog();
        filterService.applyFilters(productContext.getBot());

        TargetBoundListingClient targetClient = new TargetBoundListingClient(
                listingClient, target.additionalTargetId()
        );
        CatalogCandidateProcessor.CandidateBatch batch =
                new CatalogCandidateProcessor(
                        productContext,
                        targetClient,
                        listingStatusUpdater
                ).process();

        if (batch.candidates().isEmpty()) {
            return;
        }

        new NewNegotiationProcessor(
                productContext,
                targetClient,
                runQuota,
                listingStatusUpdater,
                realOffersEnabled,
                maxRealOffersPerRun,
                detailInspectionBudget
        ).process(
                batch.candidates(),
                batch.currentScanListingIds()
        );
    }

    public static void clearProcessState(Long botId) {
        if (botId != null) {
            NEXT_PRODUCT_OFFSET.remove(botId);
        }
    }

    static void setRotationStateForTests(Long botId, int offset) {
        NEXT_PRODUCT_OFFSET.put(botId, offset);
    }

    static boolean hasRotationStateForTests(Long botId) {
        return botId != null && NEXT_PRODUCT_OFFSET.containsKey(botId);
    }
}
