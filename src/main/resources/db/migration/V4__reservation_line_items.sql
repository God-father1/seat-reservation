-- V4__reservation_line_items.sql
ALTER TABLE reservations ADD COLUMN seat_prices bigint[];

ALTER TABLE reservations ADD CONSTRAINT reservation_line_items_align CHECK (
    seat_prices IS NULL
    OR array_length(seat_prices, 1) = array_length(seat_labels, 1));

CREATE OR REPLACE FUNCTION reserve_seats(
    p_show_id uuid,
    p_user_id uuid,
    p_labels text[],
    p_idem_key text,
    p_req_hash bytea,
    p_mode text
)
RETURNS TABLE (out_status integer, out_body jsonb) AS $$
DECLARE
    v_show record;
    v_labels text[];
    v_n integer;
    v_target seat_status;
    v_res_target reservation_status;
    v_expires timestamptz;
    v_scope text;
    v_prev_status integer;
    v_prev_hash bytea;
    v_prev_body jsonb;
    v_found text[];
    v_blocked text[];
    v_prices bigint[];
    v_self_steal integer;
    v_amount bigint;
    v_rid uuid;
    v_rows integer;
BEGIN
    SET LOCAL lock_timeout = '2s';

    SELECT * INTO v_show FROM shows WHERE id = p_show_id;
    IF NOT FOUND THEN
        out_status := 404; out_body := jsonb_build_object('code', 'show_not_found'); RETURN NEXT; RETURN;
    END IF;

    SELECT array_agg(DISTINCT lbl ORDER BY lbl) INTO v_labels FROM unnest(p_labels) AS lbl;
    v_n := array_length(v_labels, 1);

    IF v_n IS NULL OR v_n = 0 THEN
        out_status := 422; out_body := jsonb_build_object('code', 'no_seats_requested'); RETURN NEXT; RETURN;
    END IF;
    
    IF v_n > v_show.per_user_limit THEN
        out_status := 422; out_body := jsonb_build_object('code', 'too_many_seats'); RETURN NEXT; RETURN;
    END IF;
    
    IF v_show.sales_open_at IS NOT NULL AND v_show.sales_open_at > now() THEN
        out_status := 409; out_body := jsonb_build_object('code', 'sales_not_open'); RETURN NEXT; RETURN;
    END IF;

    IF lower(p_mode) = 'hold' THEN
        v_target := 'held'::seat_status; v_res_target := 'held'::reservation_status; v_expires := now() + (v_show.hold_ttl_sec || ' seconds')::interval;
    ELSE
        v_target := 'confirmed'::seat_status; v_res_target := 'confirmed'::reservation_status; v_expires := NULL;
    END IF;

    v_scope := 'reserve:' || p_show_id::text;
    
    INSERT INTO idempotency_keys (user_id, key, scope, request_hash, status_code, response_body)
    VALUES (p_user_id, p_idem_key, v_scope, p_req_hash, 0, '{}'::jsonb)
    ON CONFLICT (user_id, key, scope) DO UPDATE SET status_code = idempotency_keys.status_code
    RETURNING status_code, request_hash, response_body INTO v_prev_status, v_prev_hash, v_prev_body;

    IF v_prev_status <> 0 THEN
        IF v_prev_hash <> p_req_hash THEN
            out_status := 409; out_body := jsonb_build_object('code', 'idempotency_key_conflict'); RETURN NEXT; RETURN;
        ELSE
            out_status := v_prev_status; out_body := v_prev_body || '{"replayed": true}'::jsonb; RETURN NEXT; RETURN;
        END IF;
    END IF;

    BEGIN
        WITH locked AS (
            SELECT s.seat_no, s.label, s.status, s.hold_expires_at, s.owner_id, s.price_paise
              FROM seats s WHERE s.show_id = p_show_id AND s.label = ANY (v_labels)
             ORDER BY s.seat_no FOR UPDATE
        )
        SELECT COALESCE(array_agg(label ORDER BY label), ARRAY[]::text[]),
               COALESCE(array_agg(label ORDER BY label) FILTER (WHERE status = 'confirmed' OR (status = 'held' AND hold_expires_at > now())), ARRAY[]::text[]),
               COALESCE(array_agg(COALESCE(price_paise, v_show.price_paise) ORDER BY label), ARRAY[]::bigint[]),
               COALESCE(count(*) FILTER (WHERE status = 'held' AND hold_expires_at <= now() AND owner_id = p_user_id), 0),
               COALESCE(sum(COALESCE(price_paise, v_show.price_paise)), 0)
          INTO v_found, v_blocked, v_prices, v_self_steal, v_amount
          FROM locked;

        IF array_length(v_found, 1) IS DISTINCT FROM v_n THEN RAISE EXCEPTION 'SR422 unknown_seat'; END IF;
        IF array_length(v_blocked, 1) > 0 THEN RAISE EXCEPTION 'SR409 seat_unavailable %', jsonb_build_object('seats', v_blocked); END IF;

        INSERT INTO user_show_quota (show_id, user_id, active_seats) VALUES (p_show_id, p_user_id, v_n - v_self_steal)
        ON CONFLICT (show_id, user_id) DO UPDATE SET active_seats = user_show_quota.active_seats + EXCLUDED.active_seats
          WHERE user_show_quota.active_seats + EXCLUDED.active_seats <= v_show.per_user_limit;
          
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        IF v_rows = 0 THEN RAISE EXCEPTION 'SR409 per_user_limit_exceeded'; END IF;

        v_rid := gen_random_uuid();

        UPDATE seats SET status = v_target, reservation_id = v_rid, owner_id = p_user_id, hold_expires_at = v_expires, updated_at = now()
         WHERE show_id = p_show_id AND label = ANY (v_labels);
         
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        IF v_rows <> v_n THEN RAISE EXCEPTION 'SR409 unreachable_assertion'; END IF;

        INSERT INTO reservations (id, show_id, user_id, seat_labels, seat_prices, amount_paise, status, expires_at)
        VALUES (v_rid, p_show_id, p_user_id, v_labels, v_prices, v_amount, v_res_target, v_expires);

        out_status := 201;
        out_body := jsonb_build_object(
            'reservation_id', v_rid, 'show_id', p_show_id, 'user_id', p_user_id, 'seats', v_labels,
            'amount_paise', v_amount, 'status', v_res_target, 'expires_at', v_expires,
            'line_items', (SELECT jsonb_agg(jsonb_build_object('seat', l, 'price_paise', p)) FROM unnest(v_labels, v_prices) AS t(l, p))
        );
    EXCEPTION
        WHEN SQLSTATE 'SR409' THEN
            out_status := 409;
            IF SQLERRM LIKE 'SR409 seat_unavailable %' THEN out_body := jsonb_build_object('code', 'seat_unavailable') || substr(SQLERRM, 26)::jsonb;
            ELSIF SQLERRM = 'SR409 per_user_limit_exceeded' THEN out_body := jsonb_build_object('code', 'per_user_limit_exceeded');
            ELSE out_body := jsonb_build_object('code', 'internal_error_unreachable'); END IF;
        WHEN SQLSTATE 'SR422' THEN out_status := 422; out_body := jsonb_build_object('code', 'unknown_seat');
    END;

    UPDATE idempotency_keys SET status_code = out_status, response_body = out_body
     WHERE user_id = p_user_id AND key = p_idem_key AND scope = v_scope;

    RETURN NEXT; RETURN;
END;
$$ LANGUAGE plpgsql;
