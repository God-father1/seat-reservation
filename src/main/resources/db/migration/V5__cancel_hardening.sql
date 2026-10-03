-- V5__cancel_hardening.sql
-- (Cancel hardening was already included in V3 in this codebase, but we include this file for schema version alignment)
CREATE OR REPLACE FUNCTION cancel_reservation(p_reservation_id uuid, p_user_id uuid)
RETURNS TABLE (out_status integer, out_body jsonb) AS $$
DECLARE
    v_res record;
    v_released integer := 0;
BEGIN
    SET LOCAL lock_timeout = '2s';

    SELECT * INTO v_res FROM reservations WHERE id = p_reservation_id;
    IF NOT FOUND THEN
        out_status := 404; out_body := jsonb_build_object('code', 'not_found'); RETURN NEXT; RETURN;
    END IF;

    IF v_res.user_id <> p_user_id THEN
        out_status := 403; out_body := jsonb_build_object('code', 'forbidden'); RETURN NEXT; RETURN;
    END IF;

    IF v_res.status = 'cancelled' THEN
        out_status := 200; out_body := jsonb_build_object('seats_released', 0, 'replayed', true); RETURN NEXT; RETURN;
    END IF;

    IF v_res.status NOT IN ('held', 'confirmed') THEN
        out_status := 409; out_body := jsonb_build_object('code', 'reservation_not_cancellable'); RETURN NEXT; RETURN;
    END IF;

    -- REQUIRED lock order fix (as per traps 17.6)
    PERFORM 1 FROM seats WHERE reservation_id = p_reservation_id ORDER BY seat_no FOR UPDATE;

    WITH released AS (
        UPDATE seats
           SET status = 'available', reservation_id = NULL, owner_id = NULL, hold_expires_at = NULL, updated_at = now()
         WHERE reservation_id = p_reservation_id AND status IN ('held', 'confirmed')
         RETURNING 1
    )
    SELECT count(*)::integer INTO v_released FROM released;

    IF v_released > 0 THEN
        UPDATE user_show_quota q
           SET active_seats = GREATEST(q.active_seats - v_released, 0)
         WHERE q.show_id = v_res.show_id AND q.user_id = p_user_id;
    END IF;

    UPDATE reservations
       SET status = 'cancelled', updated_at = now()
     WHERE id = p_reservation_id AND status IN ('held', 'confirmed');

    IF NOT FOUND THEN
        out_status := 200; out_body := jsonb_build_object('seats_released', 0, 'replayed', true); RETURN NEXT; RETURN;
    END IF;

    out_status := 200; out_body := jsonb_build_object('seats_released', v_released); RETURN NEXT; RETURN;
END;
$$ LANGUAGE plpgsql;
