package id.co.yokke.incidentdashboard.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Creates, loads and resets the Redmine mirror tables inside the single Testcontainers
 * Oracle instance.
 *
 * <h2>The schema is a PREFIX, not a schema</h2>
 *
 * <p>Production reads the mirror from a genuinely separate Oracle schema, so
 * {@code incident.mirror.schema} is {@code "REDMINE_MIRROR."} there. Testcontainers hands
 * us exactly one schema, so the tests fake the separation with a table-name prefix:
 * {@link #MIRROR_PREFIX} is {@code "REDMINE_MIRROR_"}. In both cases <b>the property value
 * carries its own separator</b> — consumers concatenate {@code schema + "ISSUES"} and must
 * never add a dot of their own.
 *
 * <h2>NULL vs. the empty string</h2>
 *
 * <p>{@code custom_values.value} is the column the report's SOLUTION classifier and
 * AGING_NEW branch on, and they treat SQL NULL and {@code ''} differently. CSV cannot tell
 * the two apart, so the fixtures use the sentinel {@value #NULL_SENTINEL} for SQL NULL and
 * a genuinely empty field for the empty string; {@link #bind} reverses that.
 *
 * <p>Oracle then adds its own twist: a zero-length string assigned to a character column is
 * NULL. The only way to store a non-NULL, zero-length CLOB is an <em>empty LOB locator</em>
 * ({@code EMPTY_CLOB()} in SQL, a zero-length temporary {@link Clob} over JDBC), which is
 * what {@link #bind} does for CLOB columns. Such a value is {@code IS NOT NULL} but can only
 * be recognised with {@code DBMS_LOB.GETLENGTH(col) = 0} — {@code col = ''} is
 * {@code col = NULL} in Oracle and is never true. See {@code fixtures/README.md}.
 */
public final class MirrorFixtureLoader {

    /**
     * Table-name prefix standing in for the production {@code REDMINE_MIRROR.} schema.
     * The trailing underscore is part of the value; do not append a separator.
     */
    public static final String MIRROR_PREFIX = "REDMINE_MIRROR_";

    /** Sentinel written by {@code scripts/extract-fixtures.py} for SQL NULL. */
    public static final String NULL_SENTINEL = "\\N";

    private static final String FIXTURE_ROOT = "fixtures/";

    /**
     * Mart objects owned by {@code V3__mart.sql} (Phase 2). They do not exist during the
     * Phase 0 and Phase 1 tier-2 gates, so {@link #resetAll} must skip whatever is absent.
     */
    private static final List<String> MART_TABLES = List.of(
            "INCIDENT_FACT", "INCIDENT_FACT_REJECT", "ETL_RECONCILE_LOG", "ETL_WATERMARK");

    enum ColType {
        NUMBER,
        FLAG,
        DOUBLE,
        VARCHAR,
        CLOB,
        DATE,
        TIMESTAMP
    }

    /** Ordered column -> Oracle type, transcribed from {@code docs/reference/ddl.sql}. */
    private static final Map<String, Map<String, ColType>> SCHEMA = new LinkedHashMap<>();

    /** Oracle column-type text keyed the same way, so DDL and binding cannot drift. */
    private static final Map<String, Map<String, String>> DDL = new LinkedHashMap<>();

    static {
        table("issues", new String[][] {
                {"id", "NUMBER(10)"},
                {"tracker_id", "NUMBER(10)"},
                {"project_id", "NUMBER(10)"},
                {"subject", "VARCHAR2(255 CHAR)"},
                {"description", "CLOB"},
                {"due_date", "DATE"},
                {"category_id", "NUMBER(10)"},
                {"status_id", "NUMBER(10)"},
                {"assigned_to_id", "NUMBER(10)"},
                {"priority_id", "NUMBER(10)"},
                {"fixed_version_id", "NUMBER(10)"},
                {"author_id", "NUMBER(10)"},
                {"lock_version", "NUMBER(10)"},
                {"created_on", "TIMESTAMP(0)"},
                {"updated_on", "TIMESTAMP(0)"},
                {"start_date", "DATE"},
                {"done_ratio", "NUMBER(10)"},
                {"estimated_hours", "BINARY_DOUBLE"},
                {"parent_id", "NUMBER(10)"},
                {"root_id", "NUMBER(10)"},
                {"lft", "NUMBER(10)"},
                {"rgt", "NUMBER(10)"},
                {"is_private", "NUMBER(1)"},
                {"closed_on", "TIMESTAMP(0)"},
        });
        table("issue_statuses", new String[][] {
                {"id", "NUMBER(10)"},
                {"name", "VARCHAR2(30 CHAR)"},
                {"is_closed", "NUMBER(1)"},
                {"position", "NUMBER(10)"},
                {"default_done_ratio", "NUMBER(10)"},
                {"description", "CLOB"},
        });
        table("enumerations", new String[][] {
                {"id", "NUMBER(10)"},
                {"name", "VARCHAR2(30 CHAR)"},
                {"position", "NUMBER(10)"},
                {"is_default", "NUMBER(1)"},
                {"type", "VARCHAR2(255 CHAR)"},
                {"active", "NUMBER(1)"},
                {"project_id", "NUMBER(10)"},
                {"parent_id", "NUMBER(10)"},
                {"position_name", "VARCHAR2(30 CHAR)"},
        });
        table("trackers", new String[][] {
                {"id", "NUMBER(10)"},
                {"name", "VARCHAR2(30 CHAR)"},
                {"description", "VARCHAR2(255 CHAR)"},
                {"is_in_chlog", "NUMBER(1)"},
                {"position", "NUMBER(10)"},
                {"is_in_roadmap", "NUMBER(1)"},
                {"fields_bits", "NUMBER(10)"},
                {"default_status_id", "NUMBER(10)"},
        });
        table("custom_values", new String[][] {
                {"id", "NUMBER(10)"},
                {"customized_type", "VARCHAR2(30 CHAR)"},
                {"customized_id", "NUMBER(10)"},
                {"custom_field_id", "NUMBER(10)"},
                {"value", "CLOB"},
                {"custom_entity_id", "NUMBER(10)"},
        });
        table("journals", new String[][] {
                {"id", "NUMBER(10)"},
                {"journalized_id", "NUMBER(10)"},
                {"journalized_type", "VARCHAR2(30 CHAR)"},
                {"user_id", "NUMBER(10)"},
                {"notes", "CLOB"},
                {"created_on", "TIMESTAMP(0)"},
                {"private_notes", "NUMBER(1)"},
                {"updated_on", "TIMESTAMP(0)"},
                {"updated_by_id", "NUMBER(10)"},
        });
        table("journal_details", new String[][] {
                {"id", "NUMBER(10)"},
                {"journal_id", "NUMBER(10)"},
                {"property", "VARCHAR2(30 CHAR)"},
                {"prop_key", "VARCHAR2(30 CHAR)"},
                {"old_value", "CLOB"},
                {"value", "CLOB"},
        });
    }

    /**
     * Reference tables owned by {@code V1__config_tables.sql}, keyed by fixture CSV name.
     *
     * <p>Deliberately NOT in {@link #SCHEMA}: they are <em>not</em> mirror tables. Nothing
     * creates them here (Flyway does), they carry no {@code REDMINE_MIRROR_} prefix, and the
     * mirror's single-column {@code ID} primary key does not apply — {@code REF_CUSTOM_FIELD_ENUM}
     * is keyed on a pair. They ride along in {@link #loadAll} and {@link #resetAll} only
     * because every test that needs mirror rows also needs the labels those rows resolve to.
     *
     * <p>Both CSVs are SYNTHETIC and tracked in git, on the same argument as
     * {@code custom_values.csv}: {@code custom_field_enumerations}, {@code users} and
     * {@code email_addresses} were never delivered, so there is no production export to
     * regenerate them from. The production seeds come from {@code scripts/extract-ref-tables.py}
     * and are git-ignored because they carry real names.
     */
    private static final Map<String, Map<String, ColType>> REF_SCHEMA = new LinkedHashMap<>();

    static {
        refTable("ref_custom_field_enum", new String[][] {
                {"custom_field_id", "NUMBER(10)"},
                {"enum_value_id", "NUMBER(10)"},
                {"label", "VARCHAR2(255 CHAR)"},
                {"position", "NUMBER(10)"},
        });
        refTable("ref_user", new String[][] {
                {"user_id", "NUMBER(10)"},
                {"address", "VARCHAR2(255 CHAR)"},
                {"firstname", "VARCHAR2(255 CHAR)"},
                {"lastname", "VARCHAR2(255 CHAR)"},
                {"display_name", "VARCHAR2(255 CHAR)"},
        });
    }

    /** Fixture CSV base name -> physical table name for the non-mirror reference tables. */
    private static final Map<String, String> REF_TABLE_NAMES =
            Map.of("ref_custom_field_enum", "REF_CUSTOM_FIELD_ENUM", "ref_user", "REF_USER");

    private static void refTable(String fixture, String[][] cols) {
        Map<String, ColType> types = new LinkedHashMap<>();
        for (String[] col : cols) {
            types.put(col[0], classify(col[1]));
        }
        REF_SCHEMA.put(fixture, types);
    }

    private static void table(String name, String[][] cols) {
        Map<String, ColType> types = new LinkedHashMap<>();
        Map<String, String> ddl = new LinkedHashMap<>();
        for (String[] col : cols) {
            ddl.put(col[0], col[1]);
            types.put(col[0], classify(col[1]));
        }
        SCHEMA.put(name, types);
        DDL.put(name, ddl);
    }

    private static ColType classify(String oracleType) {
        if (oracleType.equals("NUMBER(1)")) {
            return ColType.FLAG;
        }
        if (oracleType.startsWith("NUMBER")) {
            return ColType.NUMBER;
        }
        if (oracleType.equals("BINARY_DOUBLE")) {
            return ColType.DOUBLE;
        }
        if (oracleType.equals("CLOB")) {
            return ColType.CLOB;
        }
        if (oracleType.equals("DATE")) {
            return ColType.DATE;
        }
        if (oracleType.startsWith("TIMESTAMP")) {
            return ColType.TIMESTAMP;
        }
        return ColType.VARCHAR;
    }

    private MirrorFixtureLoader() {
    }

    /** The 7 mirror tables, unprefixed, in load order. */
    public static List<String> mirrorTables() {
        return List.copyOf(SCHEMA.keySet());
    }

    /** Fully-qualified (here: prefixed) mirror table name, e.g. {@code REDMINE_MIRROR_ISSUES}. */
    public static String mirrorTable(String logicalName) {
        return MIRROR_PREFIX + logicalName.toUpperCase(Locale.ROOT);
    }

    /**
     * Physical table for a fixture name: prefixed for the mirror, unprefixed for the
     * Flyway-owned {@code REF_} tables.
     */
    private static String physicalTable(String logical) {
        String ref = REF_TABLE_NAMES.get(logical);
        return ref != null ? ref : mirrorTable(logical);
    }

    private static Map<String, ColType> columnTypes(String logical) {
        Map<String, ColType> types = SCHEMA.get(logical);
        return types != null ? types : REF_SCHEMA.get(logical);
    }

    // ---------------------------------------------------------------- schema

    /**
     * Drops and recreates the 7 mirror tables. Idempotent: safe to call against a fresh
     * container or one a previous class already bootstrapped.
     *
     * <p><b>Must run before Flyway.</b> {@code V3__mart.sql} creates {@code V_INCIDENT} as a
     * view over these tables; migrating first fails with ORA-00942. See
     * {@link AbstractOracleIT} for the latch that keeps the pair ordered.
     */
    public static void createMirrorSchema(DataSource dataSource) {
        // Both the Connection and the Statement are in the try-with-resources. Closing only
        // the Statement leaks a pooled Connection per call, which — with resetAll() running
        // per test — exhausts the pool and HANGS tier 2 instead of failing it.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String logical : SCHEMA.keySet()) {
                String table = mirrorTable(logical);
                dropIfExists(statement, table);
                statement.execute(createTableSql(logical));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create mirror schema", e);
        }
    }

    private static String createTableSql(String logical) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ")
                .append(mirrorTable(logical))
                .append(" (");
        for (Map.Entry<String, String> col : DDL.get(logical).entrySet()) {
            sql.append('"').append(col.getKey().toUpperCase(Locale.ROOT)).append('"')
                    .append(' ').append(col.getValue()).append(", ");
        }
        sql.append("CONSTRAINT ").append(mirrorTable(logical)).append("_PK PRIMARY KEY (\"ID\"))");
        return sql.toString();
    }

    private static void dropIfExists(Statement statement, String table) throws SQLException {
        try {
            statement.execute("DROP TABLE " + table + " CASCADE CONSTRAINTS PURGE");
        } catch (SQLException e) {
            if (e.getErrorCode() != 942) { // ORA-00942: table or view does not exist
                throw e;
            }
        }
    }

    // ----------------------------------------------------------------- reset

    /**
     * Empties every mirror table plus whichever mart tables currently exist.
     *
     * <p>Called from {@code @BeforeEach}: without it the second test method in a class hits
     * ORA-00001 on the ISSUES primary key, because the container is shared.
     *
     * <p>The truncate list is <b>driven from {@code USER_TABLES}</b>, not from a hard-coded
     * list. {@code INCIDENT_FACT} and friends only exist from Phase 2 onwards, and Phases 0
     * and 1 are themselves tier-2 gates — truncating unconditionally would fail those gates
     * with ORA-00942.
     */
    public static void resetAll(DataSource dataSource) {
        List<String> wanted = new ArrayList<>();
        for (String logical : SCHEMA.keySet()) {
            wanted.add(mirrorTable(logical));
        }
        wanted.addAll(MART_TABLES);
        // REF_ tables are truncated too: they are fixture-loaded per test like the mirror is,
        // so leaving them populated makes the second test method in a class fail on their
        // primary key. The CFG_ tables are NOT in this list -- they are Flyway seed data and
        // truncating them would erase the parity contract for every later test in the JVM.
        wanted.addAll(REF_TABLE_NAMES.values());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            Set<String> present = existingTables(statement);
            for (String table : wanted) {
                if (present.contains(table)) {
                    // TRUNCATE also rewinds ETL_WATERMARK: the ETL treats an absent row as
                    // "never run", which is exactly the state each test wants to start from.
                    statement.execute("TRUNCATE TABLE " + table);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to reset fixtures", e);
        }
    }

    private static Set<String> existingTables(Statement statement) throws SQLException {
        Set<String> present = new HashSet<>();
        try (ResultSet rs = statement.executeQuery("SELECT table_name FROM user_tables")) {
            while (rs.next()) {
                present.add(rs.getString(1).toUpperCase(Locale.ROOT));
            }
        }
        return present;
    }

    // ------------------------------------------------------------------ load

    /** Loads every fixture CSV present on the test classpath, in dependency order. */
    public static void loadAll(DataSource dataSource) {
        for (String logical : SCHEMA.keySet()) {
            load(dataSource, logical);
        }
        // The REF_ tables load last: they resolve ids the mirror rows carry, so nothing reads
        // them until the mirror is populated, and loading them first would only obscure a
        // failure in the mirror load.
        for (String logical : REF_SCHEMA.keySet()) {
            load(dataSource, logical);
        }
    }

    /**
     * Loads {@code fixtures/<table>.csv} into the matching mirror table. Silently skips a
     * table whose CSV is absent — the six derived CSVs are git-ignored (they carry
     * production incident text) and are regenerated by {@code scripts/extract-fixtures.py}.
     */
    public static void load(DataSource dataSource, String logical) {
        List<List<String>> rows = readCsv(FIXTURE_ROOT + logical + ".csv");
        if (rows.isEmpty()) {
            return;
        }
        List<String> header = rows.get(0);
        Map<String, ColType> types = columnTypes(logical);
        if (types == null) {
            throw new IllegalArgumentException("unknown fixture table: " + logical);
        }

        StringBuilder sql = new StringBuilder("INSERT INTO ").append(physicalTable(logical)).append(" (");
        for (int i = 0; i < header.size(); i++) {
            sql.append(i == 0 ? "" : ", ").append('"')
                    .append(header.get(i).toUpperCase(Locale.ROOT)).append('"');
        }
        sql.append(") VALUES (");
        for (int i = 0; i < header.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(')');

        List<Clob> temporaries = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int r = 1; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                for (int c = 0; c < header.size(); c++) {
                    ColType type = types.get(header.get(c));
                    if (type == null) {
                        throw new IllegalStateException(
                                logical + ".csv has unknown column " + header.get(c));
                    }
                    bind(connection, ps, c + 1, row.get(c), type, temporaries);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to load fixture " + logical, e);
        } finally {
            for (Clob clob : temporaries) {
                try {
                    clob.free();
                } catch (SQLException ignored) {
                    // best effort; the container dies with the JVM anyway
                }
            }
        }
    }

    /**
     * Maps one CSV field onto one JDBC parameter.
     *
     * <p>Three cases, and getting them wrong silently destroys the fixture's meaning:
     *
     * <ul>
     *   <li>the sentinel {@value #NULL_SENTINEL} is SQL NULL, whatever the column type;
     *   <li>an empty field on a <b>numeric / date</b> column is SQL NULL — MySQL never had a
     *       zero-length number, so an empty field there is just an unset value;
     *   <li>an empty field on a <b>CLOB</b> column is a real, non-NULL empty value and is
     *       bound as a zero-length temporary LOB. Binding {@code setString(i, "")} instead
     *       would collapse it to NULL and lose the very distinction the fixture exists to
     *       carry.
     * </ul>
     */
    static void bind(Connection connection, PreparedStatement ps, int index, String raw,
            ColType type, List<Clob> temporaries) throws SQLException {
        if (raw == null || NULL_SENTINEL.equals(raw)) {
            ps.setNull(index, jdbcType(type));
            return;
        }
        if (raw.isEmpty()) {
            if (type == ColType.CLOB) {
                Clob empty = connection.createClob();
                temporaries.add(empty);
                ps.setClob(index, empty);
            } else if (type == ColType.VARCHAR) {
                // Oracle stores '' as NULL in a VARCHAR2 anyway; be explicit about it.
                ps.setNull(index, Types.VARCHAR);
            } else {
                ps.setNull(index, jdbcType(type));
            }
            return;
        }
        switch (type) {
            case NUMBER, FLAG -> ps.setLong(index, Long.parseLong(raw.trim()));
            case DOUBLE -> ps.setDouble(index, Double.parseDouble(raw.trim()));
            case DATE -> {
                java.sql.Date date = parseDate(raw.trim());
                if (date == null) {
                    ps.setNull(index, Types.DATE);
                } else {
                    ps.setDate(index, date);
                }
            }
            case TIMESTAMP -> {
                java.sql.Timestamp ts = parseTimestamp(raw.trim());
                if (ts == null) {
                    ps.setNull(index, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(index, ts);
                }
            }
            case CLOB -> ps.setString(index, raw);
            case VARCHAR -> ps.setString(index, raw);
        }
    }

    private static int jdbcType(ColType type) {
        return switch (type) {
            case NUMBER, FLAG -> Types.NUMERIC;
            case DOUBLE -> Types.DOUBLE;
            case CLOB -> Types.CLOB;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
            case VARCHAR -> Types.VARCHAR;
        };
    }

    private static java.sql.Date parseDate(String raw) {
        if (raw.startsWith("0000-00-00")) { // MySQL's zero date has no Oracle equivalent
            return null;
        }
        return java.sql.Date.valueOf(raw.length() > 10 ? raw.substring(0, 10) : raw);
    }

    private static java.sql.Timestamp parseTimestamp(String raw) {
        if (raw.startsWith("0000-00-00")) {
            return null;
        }
        return java.sql.Timestamp.valueOf(raw.length() == 10 ? raw + " 00:00:00" : raw);
    }

    // ------------------------------------------------------------------- CSV

    /** Minimal RFC4180 reader: quoted fields, doubled quotes, embedded newlines. */
    static List<List<String>> readCsv(String classpathResource) {
        try (InputStream in = MirrorFixtureLoader.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in == null) {
                return List.of();
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder all = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    all.append(buf, 0, n);
                }
                return parseCsv(all.toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> {
                    quoted = true;
                    fieldStarted = true;
                }
                case ',' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                }
                case '\r' -> { /* ignore; \n terminates */ }
                case '\n' -> {
                    row.add(field.toString());
                    field.setLength(0);
                    fieldStarted = false;
                    rows.add(row);
                    row = new ArrayList<>();
                }
                default -> {
                    field.append(ch);
                    fieldStarted = true;
                }
            }
        }
        if (fieldStarted || !field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
