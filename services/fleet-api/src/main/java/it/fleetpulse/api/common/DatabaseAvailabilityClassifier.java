package it.fleetpulse.api.common;

import org.hibernate.TransactionException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

@Component
public class DatabaseAvailabilityClassifier {
    /**
     * Riconosce una connection failure attraversando cause, suppressed e catena JDBC.
     */
    public boolean isConnectionFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean hibernateTransactionFailure = false;
        boolean sqlFailureWithoutState = false;
        pending.add(throwable);

        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof TransactionException) {
                hibernateTransactionFailure = true;
            }
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();

                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
                if (sqlState == null) {
                    sqlFailureWithoutState = true;
                }

                if (sqlException.getNextException() != null) {
                    pending.addLast(sqlException.getNextException());
                }
            }

            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }

        return hibernateTransactionFailure && sqlFailureWithoutState;
    }
}
