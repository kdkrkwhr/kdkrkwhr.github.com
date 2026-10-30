import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/** Educational example. CSV fields contain no commas or quoted values. */
public class TextToSqlSalesDemo {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Seoul");

    enum Metric { PAID_TOTAL_WON }
    record QueryIntent(Metric metric, LocalDate startInclusive, LocalDate endExclusive) {}
    record AuthContext(String tenantId) {} // Created by the authenticated server, not the LLM.

    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    static Instant boundary(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    static void validate(QueryIntent intent) {
        if (intent == null || intent.metric() != Metric.PAID_TOTAL_WON
                || intent.startInclusive() == null || intent.endExclusive() == null
                || !intent.startInclusive().isBefore(intent.endExclusive())
                || intent.endExclusive().isAfter(intent.startInclusive().plusDays(31))) {
            throw new IllegalArgumentException("unsupported metric or date range");
        }
    }

    static BigDecimal paidTotal(Connection db, AuthContext auth, QueryIntent intent) throws Exception {
        validate(intent);
        if (auth == null || auth.tenantId() == null || auth.tenantId().isBlank()) {
            throw new IllegalArgumentException("authenticated tenant is required");
        }
        // SQL structure and identifiers are fixed by code; only values are bound.
        String sql = "SELECT COALESCE(SUM(total_won), 0) FROM orders "
                + "WHERE tenant_id = ? AND status = 'PAID' AND placed_at >= ? AND placed_at < ?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            ps.setString(1, auth.tenantId());
            ps.setObject(2, OffsetDateTime.ofInstant(boundary(intent.startInclusive(), REPORT_ZONE), ZoneOffset.UTC));
            ps.setObject(3, OffsetDateTime.ofInstant(boundary(intent.endExclusive(), REPORT_ZONE), ZoneOffset.UTC));
            try (ResultSet rows = ps.executeQuery()) {
                require(rows.next(), "aggregate row missing");
                return rows.getBigDecimal(1);
            }
        }
    }

    static BigDecimal flawedTotal(Connection db, String sql, Instant from, Instant to) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, "shop-a");
            ps.setObject(2, OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
            ps.setObject(3, OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
            try (ResultSet rows = ps.executeQuery()) {
                require(rows.next(), "aggregate row missing");
                return rows.getBigDecimal(1);
            }
        }
    }

    static void load(Connection db, Path dir) throws Exception {
        try (Statement ddl = db.createStatement()) {
            ddl.execute("CREATE TABLE orders (order_id BIGINT PRIMARY KEY, tenant_id VARCHAR(30), "
                    + "placed_at TIMESTAMP WITH TIME ZONE, status VARCHAR(20), total_won DECIMAL(12,0))");
            ddl.execute("CREATE TABLE order_items (item_id BIGINT PRIMARY KEY, order_id BIGINT)");
        }
        List<String> orders = Files.readAllLines(dir.resolve("text-to-sql-orders.csv"));
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO orders VALUES (?, ?, ?, ?, ?)")) {
            for (String line : orders.subList(1, orders.size())) {
                String[] f = line.split(",");
                ps.setLong(1, Long.parseLong(f[0]));
                ps.setString(2, f[1]);
                ps.setObject(3, OffsetDateTime.ofInstant(Instant.parse(f[2]), ZoneOffset.UTC));
                ps.setString(4, f[3]);
                ps.setBigDecimal(5, new BigDecimal(f[4]));
                ps.executeUpdate();
            }
        }
        List<String> items = Files.readAllLines(dir.resolve("text-to-sql-items.csv"));
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO order_items VALUES (?, ?)")) {
            for (String line : items.subList(1, items.size())) {
                String[] f = line.split(",");
                ps.setLong(1, Long.parseLong(f[0]));
                ps.setLong(2, Long.parseLong(f[1]));
                ps.executeUpdate();
            }
        }
    }

    static void expect(BigDecimal actual, String expected, String label) {
        require(actual.compareTo(new BigDecimal(expected)) == 0,
                label + ": expected " + expected + ", got " + actual);
        System.out.println(label + ": " + actual + " won (PASS)");
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length == 0 ? "." : args[0]);
        String[] fixture = Files.readAllLines(dir.resolve("text-to-sql-expected.csv")).get(1).split(",");
        AuthContext auth = new AuthContext(fixture[0]);
        QueryIntent intent = new QueryIntent(Metric.PAID_TOTAL_WON,
                LocalDate.parse(fixture[1]), LocalDate.parse(fixture[2]));
        Instant from = boundary(intent.startInclusive(), REPORT_ZONE);
        Instant to = boundary(intent.endExclusive(), REPORT_ZONE);

        try (Connection db = DriverManager.getConnection("jdbc:h2:mem:sales")) {
            load(db, dir);
            String paidWhere = " WHERE o.tenant_id = ? AND o.status = 'PAID' "
                    + "AND o.placed_at >= ? AND o.placed_at < ?";
            expect(flawedTotal(db, "SELECT COALESCE(SUM(o.total_won), 0) FROM orders o "
                    + "JOIN order_items i ON i.order_id = o.order_id" + paidWhere, from, to),
                    "50000", "duplicated by item JOIN");
            expect(flawedTotal(db, "SELECT COALESCE(SUM(o.total_won), 0) FROM orders o "
                    + "WHERE o.tenant_id = ? AND o.placed_at >= ? AND o.placed_at < ?", from, to),
                    "60000", "canceled order included");
            expect(flawedTotal(db, "SELECT COALESCE(SUM(o.total_won), 0) FROM orders o" + paidWhere,
                    boundary(intent.startInclusive(), ZoneOffset.UTC),
                    boundary(intent.endExclusive(), ZoneOffset.UTC)),
                    "60000", "UTC dates used as Seoul dates");
            expect(paidTotal(db, auth, intent), fixture[3], "validated paid total");
            expect(paidTotal(db, new AuthContext("shop-b"), intent), "50000", "server tenant scope");
            try {
                paidTotal(db, auth, new QueryIntent(Metric.PAID_TOTAL_WON,
                        LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-01")));
                throw new AssertionError("reversed dates were accepted");
            } catch (IllegalArgumentException expected) {
                System.out.println("reversed dates rejected: PASS");
            }
        }
    }
}
