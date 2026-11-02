package pl.flipbot.bot.activity;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import pl.flipbot.bot.BotRepository;
import pl.flipbot.bot.activity.dto.BotDailyActivityResponse;
import pl.flipbot.bot.runtime.BotRuntimeStateService;
import pl.flipbot.bot.runtime.dto.BotRuntimeStateResponse;

/** One HTTP refresh for the bot list; a failed metric does not hide other bots. */
@RestController
@RequiredArgsConstructor
@Slf4j
public class BotOverviewController {
    private final BotRepository bots;
    private final BotDailyActivityService activity;
    private final BotRuntimeStateService runtime;

    public record Overview(Long botId, BotDailyActivityResponse activity, BotRuntimeStateResponse runtime) {}

    @GetMapping("/api/bots/overview")
    public List<Overview> getOverview() {
        return bots.findAll().stream().map(bot -> read(bot.getId())).toList();
    }

    /** Keeps already-open panels working across the switch to the overview endpoint. */
    @GetMapping("/api/bots/{botId}/activity/today")
    public BotDailyActivityResponse getToday(@PathVariable("botId") Long botId) {
        return activity.getToday(botId);
    }

    private Overview read(Long botId) {
        BotDailyActivityResponse activityState = null;
        BotRuntimeStateResponse runtimeState = null;
        try { activityState = activity.getToday(botId); }
        catch (RuntimeException failure) { log.warn("Could not read daily activity for bot {}", botId, failure); }
        try { runtimeState = runtime.getRuntimeState(botId); }
        catch (RuntimeException failure) { log.warn("Could not read runtime for bot {}", botId, failure); }
        return new Overview(botId, activityState, runtimeState);
    }
}
