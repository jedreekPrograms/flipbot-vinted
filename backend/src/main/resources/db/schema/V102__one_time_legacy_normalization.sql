-- Preserve the previous narrow recovery correction before history updates change row xmin.
DO $correction$
DECLARE recovery_at TIMESTAMP;
BEGIN
    SELECT applied_at INTO recovery_at FROM flipbot_data_repair
      WHERE repair_key = 'legacy-negotiation-recovery-2026-09-19-v1';
    IF recovery_at IS NULL OR EXISTS (SELECT 1 FROM flipbot_data_repair
      WHERE repair_key = 'legacy-negotiation-recovery-2026-09-19-v2-rollback') THEN
        RETURN;
    END IF;
    WITH repair_tx AS (
        SELECT xmin AS recovery_xmin, applied_at FROM flipbot_data_repair
        WHERE repair_key = 'legacy-negotiation-recovery-2026-09-19-v1'
    )
    UPDATE listing l SET status = 'EXPIRED', awaiting_seller_response = TRUE,
        decision_at = COALESCE(l.decision_at, (SELECT applied_at FROM repair_tx))
    WHERE l.status = 'NEGOTIATING'
      AND EXISTS (SELECT 1 FROM repair_tx WHERE l.xmin = repair_tx.recovery_xmin);
    UPDATE listing l SET status = 'EXPIRED', awaiting_seller_response = TRUE,
        decision_at = COALESCE(l.decision_at, recovery_at)
    WHERE l.status = 'NEGOTIATING' AND l.current_step_started_at IS NOT NULL
      AND l.current_step_started_at < recovery_at - INTERVAL '24 hours'
      AND l.formal_response_detected_at IS NOT NULL AND l.formal_response_detected_at >= recovery_at
      AND NOT EXISTS (SELECT 1 FROM real_action_audit a WHERE a.backend_listing_id = l.id
          AND a.created_at >= recovery_at AND a.outcome IN ('CONFIRMED', 'AMBIGUOUS'));
    INSERT INTO flipbot_data_repair(repair_key)
      VALUES ('legacy-negotiation-recovery-2026-09-19-v2-rollback')
      ON CONFLICT (repair_key) DO NOTHING;
END $correction$;

UPDATE listing
SET history_outcome = 'PURCHASED'
WHERE upper(trim(history_outcome)) IN (
    'PURCHASED_BY_ME',
    'BOUGHT_BY_ME',
    'BOUGHT'
);

UPDATE listing
SET history_outcome = 'REJECTED'
WHERE upper(trim(history_outcome)) IN (
    'REJECTED_BY_ME',
    'SKIPPED_BY_ME'
);

UPDATE listing
SET history_outcome = CASE
    WHEN status = 'PURCHASED' THEN 'PURCHASED'
    WHEN status = 'SKIPPED_BY_USER' THEN 'REJECTED'
    ELSE 'UNCLASSIFIED'
END
WHERE history_outcome IS NOT NULL
  AND upper(trim(history_outcome)) NOT IN (
      'UNCLASSIFIED',
      'PURCHASED',
      'REJECTED',
      'MISSED_OPPORTUNITY'
  );

UPDATE listing
SET offer_assessment = 'UNASSESSED'
WHERE offer_assessment IS NULL
   OR upper(trim(offer_assessment)) NOT IN (
       'UNASSESSED',
       'LEGIT',
       'SCAM'
   );

UPDATE listing
SET missed_opportunity_reason = NULL
WHERE missed_opportunity_reason IS NOT NULL
  AND upper(trim(missed_opportunity_reason)) NOT IN (
      'SOLD_BEFORE_PURCHASE',
      'NO_FUNDS',
      'TOO_SLOW',
      'PRICE_TOO_HIGH',
      'CHANGED_MIND',
      'OTHER'
  );

UPDATE listing
SET history_outcome = 'REJECTED'
WHERE history_outcome = 'MISSED_OPPORTUNITY';

UPDATE listing
SET buy_candidate_at = COALESCE(
        decision_at,
        current_step_started_at,
        last_fresh_discovery_at,
        CASE
            WHEN status = 'ACTION_REQUIRED' THEN CURRENT_TIMESTAMP
            ELSE TIMESTAMP '1970-01-01 00:00:00'
        END
    )
WHERE buy_candidate_at IS NULL
  AND status IN (
      'ACTION_REQUIRED',
      'PURCHASED',
      'SKIPPED_BY_USER'
  );

UPDATE listing
SET history_outcome = 'PURCHASED'
WHERE status = 'PURCHASED'
  AND history_outcome IS NULL;

UPDATE listing
SET history_outcome = 'REJECTED'
WHERE status = 'SKIPPED_BY_USER'
  AND history_outcome IS NULL;

UPDATE listing
SET decision_at = COALESCE(
        decision_at,
        current_step_started_at,
        last_fresh_discovery_at
    )
WHERE decision_at IS NULL
  AND status IN (
      'UNAVAILABLE',
      'CONTACT_UNAVAILABLE',
      'REJECTED',
      'EXPIRED'
  );

UPDATE listing l
SET product_target_label =
        trim(t.brand)
        || ' → '
        || trim(
            CASE
                WHEN t.target_mode = 'SEARCH_QUERY' THEN t.search_query
                ELSE t.model
            END
        )
FROM bot_additional_target t
WHERE l.product_target_label IS NULL
  AND l.additional_target_id = t.id
  AND t.brand IS NOT NULL
  AND CASE
          WHEN t.target_mode = 'SEARCH_QUERY' THEN t.search_query
          ELSE t.model
      END IS NOT NULL
  AND position(
          lower(
              trim(
                  CASE
                      WHEN t.target_mode = 'SEARCH_QUERY' THEN t.search_query
                      ELSE t.model
                  END
              )
          )
          in lower(l.title)
      ) > 0;

UPDATE listing l
SET product_target_label =
        trim(c.brand)
        || ' → '
        || trim(
            CASE
                WHEN c.target_mode = 'SEARCH_QUERY' THEN c.search_query
                ELSE c.model
            END
        )
FROM bot_configuration c
WHERE l.product_target_label IS NULL
  AND l.additional_target_id IS NULL
  AND l.bot_id = c.bot_id
  AND c.brand IS NOT NULL
  AND CASE
          WHEN c.target_mode = 'SEARCH_QUERY' THEN c.search_query
          ELSE c.model
      END IS NOT NULL
  AND position(
          lower(
              trim(
                  CASE
                      WHEN c.target_mode = 'SEARCH_QUERY' THEN c.search_query
                      ELSE c.model
                  END
              )
          )
          in lower(l.title)
      ) > 0;

UPDATE market_listing_observation
SET published_at = NULL
WHERE published_at = first_seen_at;
