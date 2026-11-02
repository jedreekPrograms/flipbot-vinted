package pl.flipbot.mobile;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MobilePanelControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MobilePanelController(), new ApiProbe()).build();

    @ParameterizedTest
    @ValueSource(strings = {
            "/runtime", "/statistics", "/bots", "/bots/create", "/bots/7/edit",
            "/bots/7/additional-products/new", "/bots/7/additional-products/12/edit",
            "/action-required", "/history", "/pricing", "/dictionaries", "/dictionaries/manage"
    })
    void directNavigationAndRefreshReachThePanel(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/bots", "/api/statistics"})
    void panelRoutesDoNotInterceptApiRequests(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().string("api"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/not-found", "/assets/not-found.js", "/bots/7/unknown"})
    void thereIsNoCatchAllForward(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @RestController
    static class ApiProbe {
        @GetMapping({"/api/bots", "/api/statistics"})
        String api() { return "api"; }
    }
}
