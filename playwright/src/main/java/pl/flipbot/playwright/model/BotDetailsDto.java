package pl.flipbot.playwright.model;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BotDetailsDto {

    private Long id;

    private String name;

    private String email;

    private String password;

    private BotConfigurationDto configuration;

    private List<BotAdditionalTargetDto> additionalTargets;

    /** Product configuration is local to this view; account identity is shared. */
    public BotDetailsDto withConfiguration(BotConfigurationDto productConfiguration) {
        BotDetailsDto view = new BotDetailsDto();
        view.id = id;
        view.name = name;
        view.email = email;
        view.password = password;
        view.configuration = productConfiguration;
        view.additionalTargets = additionalTargets;
        return view;
    }
}
