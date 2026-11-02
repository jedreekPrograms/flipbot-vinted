-- Additive indexes only: existing migration checksums and business data are unchanged.
CREATE INDEX IF NOT EXISTS idx_real_action_audit_bot_created
    ON real_action_audit (bot_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_real_action_audit_listing_action_outcome
    ON real_action_audit (backend_listing_id, action_type, outcome);

CREATE INDEX IF NOT EXISTS idx_listing_visible_history_date
    ON listing ((COALESCE(decision_at, buy_candidate_at)))
    WHERE buy_candidate_at IS NOT NULL AND history_hidden = false;
CREATE INDEX IF NOT EXISTS idx_market_observation_published
    ON market_listing_observation (published_at)
    WHERE published_at IS NOT NULL;
