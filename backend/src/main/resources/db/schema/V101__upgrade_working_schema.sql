-- Additive upgrade of the backed-up local schema; executes once through Flyway.

-- Global marketplace negotiation ownership.
--
-- Listing discovery intentionally remains scoped per bot, so different bots may
-- discover/evaluate the same marketplace listing. Real negotiation ownership is
-- global: only one bot may negotiate a given (marketplace, listing id).
CREATE TABLE IF NOT EXISTS marketplace_negotiation_claim
(
    id BIGSERIAL PRIMARY KEY,
    marketplace VARCHAR(64) NOT NULL,
    marketplace_listing_id VARCHAR(255) NOT NULL,
    owner_bot_id BIGINT NOT NULL,
    owner_listing_id BIGINT NOT NULL,
    request_id UUID,
    claimed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP WITHOUT TIME ZONE,

    CONSTRAINT uk_marketplace_negotiation_claim
        UNIQUE (marketplace, marketplace_listing_id)
);

CREATE INDEX IF NOT EXISTS idx_marketplace_negotiation_claim_owner
    ON marketplace_negotiation_claim (owner_bot_id, owner_listing_id);

CREATE INDEX IF NOT EXISTS idx_marketplace_negotiation_claim_unconfirmed_owner
    ON marketplace_negotiation_claim (owner_bot_id)
    WHERE confirmed_at IS NULL;



CREATE TABLE IF NOT EXISTS bot_additional_target (
    id BIGSERIAL PRIMARY KEY,
    configuration_id BIGINT NOT NULL,
    brand VARCHAR(255) NOT NULL,
    target_mode VARCHAR(40) NOT NULL,
    model VARCHAR(255),
    search_query VARCHAR(255),
    min_price NUMERIC(19, 2) NOT NULL,
    max_price NUMERIC(19, 2) NOT NULL,
    auto_raise_offer_to_vinted_minimum BOOLEAN NOT NULL DEFAULT FALSE,
    max_automatic_offer NUMERIC(19, 2),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_bot_additional_target_configuration
        FOREIGN KEY (configuration_id)
        REFERENCES bot_configuration(id)
        ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS bot_additional_target_category_path (
    target_id BIGINT NOT NULL,
    path_index INTEGER NOT NULL,
    category VARCHAR(255) NOT NULL,
    PRIMARY KEY (target_id, path_index),
    CONSTRAINT fk_bot_additional_target_category_path
        FOREIGN KEY (target_id)
        REFERENCES bot_additional_target(id)
        ON DELETE CASCADE
);

ALTER TABLE negotiation_step
    ADD COLUMN IF NOT EXISTS additional_target_id BIGINT;

ALTER TABLE negotiation_step
    ALTER COLUMN configuration_id DROP NOT NULL;

ALTER TABLE listing
    ADD COLUMN IF NOT EXISTS additional_target_id BIGINT;

ALTER TABLE listing
    ADD COLUMN IF NOT EXISTS product_target_label VARCHAR(1000);

CREATE INDEX IF NOT EXISTS idx_bot_additional_target_configuration_active
    ON bot_additional_target (configuration_id, active, id);

CREATE INDEX IF NOT EXISTS idx_listing_bot_additional_target_status
    ON listing (bot_id, additional_target_id, status, id);

ALTER TABLE bot_configuration
    ADD COLUMN IF NOT EXISTS negotiation_strategy_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE bot_additional_target
    ADD COLUMN IF NOT EXISTS negotiation_strategy_version INTEGER NOT NULL DEFAULT 1;

ALTER TABLE listing
    ADD COLUMN IF NOT EXISTS negotiation_strategy_version INTEGER,
    ADD COLUMN IF NOT EXISTS negotiation_strategy_snapshot TEXT;

CREATE INDEX IF NOT EXISTS idx_listing_negotiation_strategy_version
    ON listing (negotiation_strategy_version)
    WHERE negotiation_strategy_version IS NOT NULL;

ALTER TABLE listing
    ADD COLUMN IF NOT EXISTS last_terminal_watch_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_listing_terminal_watch
    ON listing (status, last_terminal_watch_at, decision_at);

ALTER TABLE listing
    ADD COLUMN IF NOT EXISTS history_outcome VARCHAR(40),
    ADD COLUMN IF NOT EXISTS offer_assessment VARCHAR(40) NOT NULL DEFAULT 'UNASSESSED',
    ADD COLUMN IF NOT EXISTS missed_opportunity_reason VARCHAR(40),
    ADD COLUMN IF NOT EXISTS buy_candidate_at TIMESTAMP;

ALTER TABLE listing
    ALTER COLUMN offer_assessment SET DEFAULT 'UNASSESSED';

ALTER TABLE listing
    ALTER COLUMN offer_assessment SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_listing_history_classification
    ON listing (history_outcome, offer_assessment);

CREATE INDEX IF NOT EXISTS idx_listing_buy_candidate_history
    ON listing (buy_candidate_at, status)
    WHERE buy_candidate_at IS NOT NULL;

ALTER TABLE market_listing_observation
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;

ALTER TABLE market_model_scan_state
    ADD COLUMN IF NOT EXISTS publication_window_complete_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS tracking_generation INTEGER NOT NULL DEFAULT 1;

ALTER TABLE market_listing_observation
    ADD COLUMN IF NOT EXISTS tracking_generation INTEGER NOT NULL DEFAULT 1;

ALTER TABLE market_listing_observation
    DROP CONSTRAINT IF EXISTS uk_market_listing_observation_model_listing;

CREATE UNIQUE INDEX IF NOT EXISTS uk_market_listing_observation_model_generation_listing
    ON market_listing_observation (
        model_id,
        tracking_generation,
        marketplace_listing_id
    );

ALTER TABLE market_listing_observation
    ADD COLUMN IF NOT EXISTS first_seen_price NUMERIC(38, 2),
    ADD COLUMN IF NOT EXISTS latest_price NUMERIC(38, 2),
    ADD COLUMN IF NOT EXISTS lowest_seen_price NUMERIC(38, 2),
    ADD COLUMN IF NOT EXISTS highest_seen_price NUMERIC(38, 2);

DROP INDEX IF EXISTS idx_market_listing_observation_model_published_at;

CREATE INDEX idx_market_listing_observation_model_published_at
    ON market_listing_observation (model_id, tracking_generation, published_at)
    WHERE published_at IS NOT NULL;

DROP INDEX IF EXISTS idx_market_listing_observation_model_published_price;

CREATE INDEX idx_market_listing_observation_model_published_price
    ON market_listing_observation (model_id, tracking_generation, published_at)
    WHERE latest_price IS NOT NULL OR first_seen_price IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_negotiation_step_additional_target' AND confdeltype <> 'c') THEN
        ALTER TABLE negotiation_step DROP CONSTRAINT fk_negotiation_step_additional_target;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_negotiation_step_additional_target') THEN
        ALTER TABLE negotiation_step ADD CONSTRAINT fk_negotiation_step_additional_target FOREIGN KEY (additional_target_id)
            REFERENCES bot_additional_target(id) ON DELETE CASCADE;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_listing_additional_target' AND confdeltype <> 'c') THEN
        ALTER TABLE listing DROP CONSTRAINT fk_listing_additional_target;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_listing_additional_target') THEN
        ALTER TABLE listing ADD CONSTRAINT fk_listing_additional_target FOREIGN KEY (additional_target_id)
            REFERENCES bot_additional_target(id) ON DELETE CASCADE;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS flipbot_data_repair (
    repair_key VARCHAR(160) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_market_observation_retention ON market_listing_observation (last_seen_at);

CREATE INDEX IF NOT EXISTS idx_daily_offer_quota_reservation_bot_date_active ON daily_offer_quota_reservation (bot_id, usage_date, active);
