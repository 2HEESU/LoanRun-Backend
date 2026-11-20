-- Preserve immutable history except during a transaction scoped to the exact departing owner.
CREATE OR REPLACE FUNCTION reject_repayment_plan_version_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('loanrun.withdraw_user_id', true) = OLD.user_id::text THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'repayment plan versions are append-only';
END;
$$;

CREATE OR REPLACE FUNCTION reject_risk_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('loanrun.withdraw_user_id', true) = OLD.user_id::text THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'Risk history is append-only';
END;
$$;
