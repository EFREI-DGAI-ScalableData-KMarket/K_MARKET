-- Partitions mensuelles (octobre 2026 -> octobre 2027) + partition par défaut
DO $$
DECLARE
    m date;
BEGIN
    FOR m IN SELECT generate_series(date '2026-10-01', date '2027-10-01', interval '1 month')::date
    LOOP
        EXECUTE format('CREATE TABLE orders.orders_%s PARTITION OF orders.orders FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        EXECUTE format('CREATE TABLE orders.order_items_%s PARTITION OF orders.order_items FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        EXECUTE format('CREATE TABLE payments.payments_%s PARTITION OF payments.payments FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
    END LOOP;
END $$;