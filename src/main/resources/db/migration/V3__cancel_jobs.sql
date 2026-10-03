-- V3__cancel_jobs.sql
CREATE INDEX seats_by_show_status ON seats (show_id, status);
CREATE INDEX seats_by_owner       ON seats (show_id, owner_id) WHERE owner_id IS NOT NULL;

CREATE OR REPLACE FUNCTION cancel_reservation(p_reservation_id uuid, p_user_id uuid)
RETURNS TABLE (out_status integer, out_body jsonb) AS $$
DECLARE
    v_res record;
    v_released integer := 0;
BEGIN
    SET LOCAL lock_timeout = '2s';

    SELECT * INTO v_res FROM reservations WHERE id = p_reservation_id;
    IF NOT FOUND THEN
        out_status := 404;
        out_body := jsonb_build_object('code', 'not_found');
        RETURN NEXT;
        RETURN;
    END IF;

    IF v_res.user_id <> p_user_id THEN
        out_status := 403;
        out_body := jsonb_build_object('code', 'forbidden');
        RETURN NEXT;
        RETURN;
    END IF;

    IF v_res.status = 'cancelled' THEN
        out_status := 200;
        out_body := jsonb_build_object('seats_released', 0, 'replayed', true);
        RETURN NEXT;
        RETURN;
    END IF;

    IF v_res.status NOT IN ('held', 'confirmed') THEN
        out_status := 409;
        out_body := jsonb_build_object('code', 'reservation_not_cancellable');
        RETURN NEXT;
        RETURN;
    END IF;

    -- REQUIRED lock order fix (as per traps)
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
        out_status := 200;
        out_body := jsonb_build_object('seats_released', 0, 'replayed', true);
        RETURN NEXT;
        RETURN;
    END IF;

    out_status := 200;
    out_body := jsonb_build_object('seats_released', v_released);
    RETURN NEXT;
    RETURN;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION expire_holds(p_batch integer)
RETURNS integer AS $$
DECLARE
    v_released integer;
BEGIN
    WITH expired AS (
        SELECT show_id, seat_no, owner_id, reservation_id FROM seats
         WHERE status = 'held' AND hold_expires_at <= now()
         ORDER BY show_id, seat_no LIMIT p_batch FOR UPDATE SKIP LOCKED
    ), released AS (
        UPDATE seats s SET status='available', reservation_id=NULL, owner_id=NULL,
               hold_expires_at=NULL, updated_at=now()
          FROM expired e WHERE s.show_id=e.show_id AND s.seat_no=e.seat_no
        RETURNING e.show_id, e.owner_id, e.reservation_id
    ), refunded AS (
        UPDATE user_show_quota q SET active_seats = GREATEST(q.active_seats - d.cnt, 0)
          FROM (SELECT show_id, owner_id, count(*)::integer AS cnt FROM released
                 GROUP BY show_id, owner_id) d
         WHERE q.show_id = d.show_id AND q.user_id = d.owner_id
        RETURNING 1
    ), closed AS (
        UPDATE reservations r SET status='expired', updated_at=now()
         WHERE r.id IN (SELECT reservation_id FROM released) AND r.status='held'
        RETURNING 1
    )
    SELECT count(*)::integer INTO v_released FROM released;

    UPDATE reservations r SET status='expired', updated_at=now()
     WHERE r.status='held' AND r.expires_at <= now()
       AND NOT EXISTS (SELECT 1 FROM seats s WHERE s.reservation_id = r.id);

    RETURN v_released;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION reconcile_quota(p_batch integer)
RETURNS integer AS $$
DECLARE
    v_fixed integer;
BEGIN
    WITH target AS (
        SELECT show_id, user_id FROM user_show_quota
         ORDER BY show_id, user_id LIMIT p_batch FOR UPDATE SKIP LOCKED
    ), fixed AS (
        UPDATE user_show_quota q SET active_seats = live.cnt
          FROM target t,
               LATERAL (SELECT count(*)::integer AS cnt FROM seats s
                         WHERE s.show_id = t.show_id AND s.owner_id = t.user_id
                           AND (s.status='confirmed'
                             OR (s.status='held' AND s.hold_expires_at > now()))) AS live
         WHERE q.show_id = t.show_id AND q.user_id = t.user_id
           AND q.active_seats <> live.cnt
        RETURNING 1
    )
    SELECT count(*)::integer INTO v_fixed FROM fixed;
    RETURN v_fixed;
END;
$$ LANGUAGE plpgsql;
