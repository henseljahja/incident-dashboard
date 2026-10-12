-- V11: the SPO priority matrix (Impact x Urgency -> Priority), as config.
--
-- Plan: Pras, 8 Oct. Impact columns Massive, Large, Limited, Localized; urgency rows Urgent, High,
-- Medium, Low. These are the real Redmine enum names. The matrix endpoint reads this table for
-- both the SPO priority of each cell and the axis order, so neither is hard-coded in Java.
--
-- Display/derivation only: SLA days and the Priority column still follow Redmine's recorded
-- priority (changing that is a business decision). Therefore NO generation bump: nothing the ETL
-- derives reads this table, and CFG_PRIORITY_MATRIX has no V1 bump trigger.
--
-- Additive; CREATE guarded for repair safety (Oracle DDL auto-commits). Seed is MERGE-free
-- INSERT ... WHERE NOT EXISTS, so a re-run writes nothing new.

DECLARE
    v_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM USER_TABLES WHERE TABLE_NAME = 'CFG_PRIORITY_MATRIX';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE TABLE CFG_PRIORITY_MATRIX ('
            || 'IMPACT        VARCHAR2(32 CHAR) NOT NULL, '
            || 'URGENCY       VARCHAR2(32 CHAR) NOT NULL, '
            || 'PRIORITY      VARCHAR2(16 CHAR) NOT NULL, '
            || 'IMPACT_SORT   NUMBER(3)         NOT NULL, '
            || 'URGENCY_SORT  NUMBER(3)         NOT NULL, '
            || 'CONSTRAINT CFG_PRIORITY_MATRIX_PK PRIMARY KEY (IMPACT, URGENCY), '
            || 'CONSTRAINT CFG_PM_PRIORITY_CK CHECK '
            || '(PRIORITY IN (''Critical'', ''High'', ''Medium'', ''Low'')))';
    END IF;
END;
/

-- Rows: impact (sort 1..4 = Massive, Large, Limited, Localized) x urgency (1..4 = Urgent, High,
-- Medium, Low).
INSERT INTO CFG_PRIORITY_MATRIX (IMPACT, URGENCY, PRIORITY, IMPACT_SORT, URGENCY_SORT)
SELECT v.IMPACT, v.URGENCY, v.PRIORITY, v.IMPACT_SORT, v.URGENCY_SORT FROM (
    SELECT 'Massive' IMPACT, 'Urgent' URGENCY, 'Critical' PRIORITY, 1 IMPACT_SORT, 1 URGENCY_SORT FROM DUAL UNION ALL
    SELECT 'Large',    'Urgent', 'Critical', 2, 1 FROM DUAL UNION ALL
    SELECT 'Limited',  'Urgent', 'High',     3, 1 FROM DUAL UNION ALL
    SELECT 'Localized','Urgent', 'High',     4, 1 FROM DUAL UNION ALL
    SELECT 'Massive',  'High',   'Critical', 1, 2 FROM DUAL UNION ALL
    SELECT 'Large',    'High',   'High',     2, 2 FROM DUAL UNION ALL
    SELECT 'Limited',  'High',   'High',     3, 2 FROM DUAL UNION ALL
    SELECT 'Localized','High',   'Medium',   4, 2 FROM DUAL UNION ALL
    SELECT 'Massive',  'Medium', 'High',     1, 3 FROM DUAL UNION ALL
    SELECT 'Large',    'Medium', 'Medium',   2, 3 FROM DUAL UNION ALL
    SELECT 'Limited',  'Medium', 'Medium',   3, 3 FROM DUAL UNION ALL
    SELECT 'Localized','Medium', 'Medium',   4, 3 FROM DUAL UNION ALL
    SELECT 'Massive',  'Low',    'Low',      1, 4 FROM DUAL UNION ALL
    SELECT 'Large',    'Low',    'Low',      2, 4 FROM DUAL UNION ALL
    SELECT 'Limited',  'Low',    'Low',      3, 4 FROM DUAL UNION ALL
    SELECT 'Localized','Low',    'Low',      4, 4 FROM DUAL) v
 WHERE NOT EXISTS (SELECT 1 FROM CFG_PRIORITY_MATRIX m
                    WHERE m.IMPACT = v.IMPACT AND m.URGENCY = v.URGENCY);
