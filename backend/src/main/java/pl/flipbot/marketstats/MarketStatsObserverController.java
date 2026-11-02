package pl.flipbot.marketstats;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.flipbot.marketstats.dto.MarketStatsObserverPlaywrightResponse;

@RestController
@RequestMapping("/api/market-stats/observer")
@RequiredArgsConstructor
public class MarketStatsObserverController {

    private final MarketStatsObserverService observerService;

    @GetMapping("/playwright")
    public ResponseEntity<MarketStatsObserverPlaywrightResponse> getObserverForPlaywright() {
        return observerService.getObserverForPlaywright()
                .map(ResponseEntity::ok)
                .orElseGet(
                        () -> ResponseEntity.noContent().build()
                );
    }
}
