-- V7: Benchmark results for architecture comparison
-- Not RLS-protected: global fixtures used by CompareController.
CREATE TABLE benchmark_results (
    id                UUID           NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    preset_id         TEXT           NOT NULL,
    approach          TEXT           NOT NULL,
    decision          TEXT           NOT NULL,
    amount            NUMERIC(14,2)  NOT NULL,
    correct           BOOLEAN        NOT NULL,
    latency_ms        INT            NOT NULL,
    estimated_cost_usd NUMERIC(10,6) NOT NULL DEFAULT 0,
    citation_present  BOOLEAN        NOT NULL DEFAULT false,
    unsafe_action     BOOLEAN        NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_benchmark_results_preset_id ON benchmark_results(preset_id);
