package dev.dk.blog;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Local, fictional order/reservation demo. No payment or third-party endpoint is called. */
public class TransactionBoundaryDemo implements AutoCloseable {
    private final ReservationStub stub;
    private final AnnotationConfigApplicationContext context;

    public TransactionBoundaryDemo() throws IOException {
        stub = new ReservationStub();
        stub.start();
        context = new AnnotationConfigApplicationContext();
        context.register(AppConfig.class);
        context.getBeanFactory().registerSingleton("gateway", new ReservationGateway(stub.uri()));
        context.refresh();
        context.getBean(JdbcTemplate.class).execute(
                "CREATE TABLE orders (id VARCHAR(80) PRIMARY KEY, status VARCHAR(20) NOT NULL)");
    }

    public JdbcTemplate db() { return context.getBean(JdbcTemplate.class); }
    public BadOrderService bad() { return context.getBean(BadOrderService.class); }
    public SafeOrderService safe() { return context.getBean(SafeOrderService.class); }
    public PropagationCases propagation() { return context.getBean(PropagationCases.class); }
    public ReservationStub stub() { return stub; }

    @Override public void close() {
        context.close();
        stub.close();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    public static class AppConfig {
        @Bean DataSource dataSource() {
            // Unique database per test process; contents disappear with the JVM.
            return new DriverManagerDataSource("jdbc:h2:mem:txdemo-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean BadOrderService badOrderService(JdbcTemplate db, ReservationGateway gateway) {
            return new BadOrderService(db, gateway);
        }
        @Bean OrderSteps orderSteps(JdbcTemplate db) { return new OrderSteps(db); }
        @Bean SafeOrderService safeOrderService(OrderSteps steps, ReservationGateway gateway) {
            return new SafeOrderService(steps, gateway);
        }
        @Bean PropagationInner propagationInner(JdbcTemplate db) { return new PropagationInner(db); }
        @Bean PropagationCases propagationCases(JdbcTemplate db, PropagationInner inner) {
            return new PropagationCases(db, inner);
        }
    }

    public static class BadOrderService {
        private final JdbcTemplate db;
        private final ReservationGateway gateway;
        BadOrderService(JdbcTemplate db, ReservationGateway gateway) { this.db = db; this.gateway = gateway; }

        @Transactional(timeout = 3, rollbackFor = {IOException.class, InterruptedException.class})
        public void remoteSuccessThenRollback(String id) throws IOException, InterruptedException {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", id);
            gateway.reserve(id, false);
            throw new IllegalStateException("synthetic failure after HTTP success");
        }

        @Transactional(timeout = 3, rollbackFor = {IOException.class, InterruptedException.class})
        public void remoteSuccessThenBeforeCommitFailure(String id) throws IOException, InterruptedException {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", id);
            gateway.reserve(id, false);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("synthetic beforeCommit failure");
                }
            });
        }

        @Transactional(timeout = 3, rollbackFor = {IOException.class, InterruptedException.class})
        public void remoteSuccessThenHttpTimeout(String id) throws IOException, InterruptedException {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", id);
            gateway.reserve(id, true);
        }
    }

    public static class OrderSteps {
        private final JdbcTemplate db;
        OrderSteps(JdbcTemplate db) { this.db = db; }

        @Transactional
        public void start(String id) { db.update("INSERT INTO orders VALUES (?, 'PENDING')", id); }

        @Transactional
        public void confirm(String id) { applyConfirmation(id); }

        @Transactional
        public void confirmThenFail(String id) {
            applyConfirmation(id);
            throw new IllegalStateException("synthetic finalization failure");
        }

        private void applyConfirmation(String id) {
            int changed = db.update("UPDATE orders SET status='CONFIRMED' WHERE id=? AND status IN ('PENDING','UNKNOWN')", id);
            if (changed == 0) {
                String status = db.queryForObject("SELECT status FROM orders WHERE id=?", String.class, id);
                if (!"CONFIRMED".equals(status)) throw new IllegalStateException("unexpected status: " + status);
            }
        }

        @Transactional
        public void markUnknown(String id) {
            db.update("UPDATE orders SET status='UNKNOWN' WHERE id=? AND status='PENDING'", id);
        }
    }

    public static class SafeOrderService {
        private final OrderSteps steps;
        private final ReservationGateway gateway;
        SafeOrderService(OrderSteps steps, ReservationGateway gateway) { this.steps = steps; this.gateway = gateway; }

        public void place(String id, boolean failFinalize) throws IOException, InterruptedException {
            steps.start(id); // transaction 1 has committed before HTTP starts.
            reserveAndFinish(id, failFinalize);
        }

        public void resume(String id) throws IOException, InterruptedException {
            reserveAndFinish(id, false); // same business key, same remote effect.
        }

        private void reserveAndFinish(String id, boolean failFinalize) throws IOException, InterruptedException {
            try {
                gateway.reserve(id, false);
                if (failFinalize) steps.confirmThenFail(id);
                else steps.confirm(id); // transaction 2 commits after HTTP succeeds.
            } catch (HttpTimeoutException e) {
                steps.markUnknown(id);
                throw e; // remote outcome must be checked before a new business attempt.
            }
        }
    }

    public static class PropagationInner {
        private final JdbcTemplate db;
        PropagationInner(JdbcTemplate db) { this.db = db; }

        @Transactional(propagation = Propagation.REQUIRED)
        public void requiredThenFail(String id) {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", id);
            throw new IllegalStateException("inner REQUIRED failure");
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void requiresNew(String id) { db.update("INSERT INTO orders VALUES (?, 'PENDING')", id); }

        @Transactional(propagation = Propagation.NESTED)
        public void nestedThenFail(String id) {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", id);
            throw new IllegalStateException("inner NESTED failure");
        }
    }

    public static class PropagationCases {
        private final JdbcTemplate db;
        private final PropagationInner inner;
        PropagationCases(JdbcTemplate db, PropagationInner inner) { this.db = db; this.inner = inner; }

        @Transactional
        public void required(String outer, String child) {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", outer);
            try { inner.requiredThenFail(child); } catch (IllegalStateException expected) { /* inspect commit result */ }
        }

        @Transactional
        public void requiresNew(String outer, String child) {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", outer);
            inner.requiresNew(child);
            throw new IllegalStateException("outer failure after independent inner commit");
        }

        @Transactional
        public void nested(String outer, String child) {
            db.update("INSERT INTO orders VALUES (?, 'PENDING')", outer);
            try { inner.nestedThenFail(child); } catch (IllegalStateException expected) { /* savepoint rollback */ }
        }
    }

    public static class ReservationGateway {
        private final URI endpoint;
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        ReservationGateway(URI endpoint) { this.endpoint = endpoint; }

        void reserve(String key, boolean slow) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(slow ? 150 : 2000))
                    .header("Idempotency-Key", key)
                    .header("X-Demo-Slow", Boolean.toString(slow))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if (response.statusCode() != 200) throw new IOException("reservation status " + response.statusCode());
        }
    }

    public static class ReservationStub implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentHashMap<String, Integer> effects = new ConcurrentHashMap<>();
        private final CountDownLatch slowEffectRecorded = new CountDownLatch(1);

        ReservationStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/reservations", exchange -> {
                try (exchange) {
                    String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                    if (key == null || key.isBlank()) { exchange.sendResponseHeaders(400, -1); return; }
                    effects.putIfAbsent(key, 1); // an already known key never creates a second effect.
                    if ("true".equals(exchange.getRequestHeaders().getFirst("X-Demo-Slow"))) {
                        slowEffectRecorded.countDown();
                        try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    exchange.sendResponseHeaders(200, -1);
                } catch (IOException ignored) { /* client may have timed out after effect was recorded */ }
            });
        }
        void start() { server.start(); }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/reservations"); }
        public int effects(String key) { return effects.getOrDefault(key, 0); }
        public boolean awaitSlowEffect() throws InterruptedException { return slowEffectRecorded.await(2, TimeUnit.SECONDS); }
        @Override public void close() { server.stop(0); }
    }
}
