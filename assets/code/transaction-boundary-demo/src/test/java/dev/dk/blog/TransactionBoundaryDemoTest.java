package dev.dk.blog;

import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.UnexpectedRollbackException;

import static org.junit.jupiter.api.Assertions.*;

class TransactionBoundaryDemoTest {
    private static int rows(TransactionBoundaryDemo demo, String id) {
        return demo.db().queryForObject("SELECT COUNT(*) FROM orders WHERE id=?", Integer.class, id);
    }

    private static String status(TransactionBoundaryDemo demo, String id) {
        return demo.db().queryForObject("SELECT status FROM orders WHERE id=?", String.class, id);
    }

    @Test void httpSuccessIsNotUndoneByApplicationRollback() throws Exception {
        try (var demo = new TransactionBoundaryDemo()) {
            assertThrows(IllegalStateException.class, () -> demo.bad().remoteSuccessThenRollback("order-1"));
            assertEquals(0, rows(demo, "order-1"));
            assertEquals(1, demo.stub().effects("order-1"));
        }
    }

    @Test void beforeCommitCallbackFailureDoesNotUndoHttp() throws Exception {
        try (var demo = new TransactionBoundaryDemo()) {
            assertThrows(IllegalStateException.class,
                    () -> demo.bad().remoteSuccessThenBeforeCommitFailure("order-2"));
            assertEquals(0, rows(demo, "order-2"));
            assertEquals(1, demo.stub().effects("order-2"));
        }
    }

    @Test void timeoutCanLeaveRemoteEffectWithUnknownClientOutcome() throws Exception {
        try (var demo = new TransactionBoundaryDemo()) {
            assertThrows(HttpTimeoutException.class,
                    () -> demo.bad().remoteSuccessThenHttpTimeout("order-3"));
            assertTrue(demo.stub().awaitSlowEffect());
            assertEquals(0, rows(demo, "order-3"));
            assertEquals(1, demo.stub().effects("order-3"));
        }
    }

    @Test void shortTransactionsAndSameKeyPermitRecoveryAfterFinalizeFailure() throws Exception {
        try (var demo = new TransactionBoundaryDemo()) {
            assertThrows(IllegalStateException.class, () -> demo.safe().place("order-4", true));
            assertEquals("PENDING", status(demo, "order-4"));
            assertEquals(1, demo.stub().effects("order-4"));
            demo.safe().resume("order-4");
            assertEquals("CONFIRMED", status(demo, "order-4"));
            assertEquals(1, demo.stub().effects("order-4"));
        }
    }

    @Test void propagationChangesDatabaseScopesNotHttpAtomicity() throws Exception {
        try (var demo = new TransactionBoundaryDemo()) {
            assertThrows(UnexpectedRollbackException.class,
                    () -> demo.propagation().required("outer-required", "child-required"));
            assertEquals(0, rows(demo, "outer-required"));
            assertEquals(0, rows(demo, "child-required"));

            assertThrows(IllegalStateException.class,
                    () -> demo.propagation().requiresNew("outer-new", "child-new"));
            assertEquals(0, rows(demo, "outer-new"));
            assertEquals(1, rows(demo, "child-new"));

            demo.propagation().nested("outer-nested", "child-nested");
            assertEquals(1, rows(demo, "outer-nested"));
            assertEquals(0, rows(demo, "child-nested"));
        }
    }
}
