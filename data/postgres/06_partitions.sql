BEGIN;

CREATE OR REPLACE FUNCTION orders.ensure_monthly_partitions(p_from date, p_to date)
RETURNS integer
LANGUAGE plpgsql
AS $$
DECLARE
    m       date;
    created integer := 0;
BEGIN
    FOR m IN
        SELECT generate_series(date_trunc('month', p_from), date_trunc('month', p_to), interval '1 month')::date
    LOOP
        IF to_regclass(format('orders.orders_%s', to_char(m, 'YYYY_MM'))) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE orders.orders_%s PARTITION OF orders.orders FOR VALUES FROM (%L) TO (%L)',
                to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
            EXECUTE format(
                'CREATE TABLE orders.order_items_%s PARTITION OF orders.order_items FOR VALUES FROM (%L) TO (%L)',
                to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;

-- 12 mois d'historique + 12 mois à venir à partir de la date d'initialisation.
SELECT orders.ensure_monthly_partitions((now() - interval '12 months')::date,
                                        (now() + interval '12 months')::date);

-- Filet de sécurité pour une date hors plage.
CREATE TABLE orders.orders_default      PARTITION OF orders.orders      DEFAULT;
CREATE TABLE orders.order_items_default PARTITION OF orders.order_items DEFAULT;

COMMIT;
