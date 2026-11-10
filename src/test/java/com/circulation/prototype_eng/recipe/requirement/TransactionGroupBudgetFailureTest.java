package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.transaction.ReversibleTransaction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionGroupBudgetFailureTest {
    @Test
    void theLastChildCallbackCreditThrowsTheOriginalFatalAndRetainsExactUncertainRecovery() {
        var fatal = new OutOfMemoryError("terminal callback");
        var later = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var child = new ReversibleTransaction() {
            private Resolution state = Resolution.REVERSIBLE;

            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
                callbacks.incrementAndGet();
                state = Resolution.ROLLED_BACK;
                throw fatal;
            }

            @Override
            public Resolution resolution() {
                return state;
            }
        };
        var group = TransactionGroup.frozen(List.of(TransactionGroup.callbacks(() -> {
        }, () -> {
        }, later::incrementAndGet), child));
        assertFalse(group.rollbackWithinBudget(new RecipeWorkBudget(1)), "Selecting the real composite child retains its original callback");
        assertEquals(0, callbacks.get());
        var lastCredit = new RecipeWorkBudget(1);
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> group.rollbackWithinBudget(lastCredit)));
        assertEquals(0, lastCredit.remaining());
        assertEquals(1, callbacks.get());
        assertEquals(0, later.get(), "Fatal propagation happens before the next composite child");
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> group.rollbackWithinBudget(new RecipeWorkBudget(20))));
        assertEquals(1, callbacks.get(), "The exact terminal proof prevents replay of the throwing callback");
        assertEquals(1, later.get());
        assertTrue(group.rollbackWithinBudget(new RecipeWorkBudget(0)));
        assertSame(ReversibleTransaction.Resolution.ROLLED_BACK, group.resolutionWithinBudget(new RecipeWorkBudget(0)));
    }

    @Test
    void aFatalExactResolutionQueryCannotTurnIntoANullBudgetYield() {
        var ordinary = new IllegalStateException("uncertain rollback");
        var fatal = new StackOverflowError("exact query");
        var queries = new AtomicInteger();
        var child = new ReversibleTransaction() {
            @Override
            public void commit() {
            }

            @Override
            public void rollback() {
                throw ordinary;
            }

            @Override
            public Resolution resolution() {
                queries.incrementAndGet();
                throw fatal;
            }
        };
        var group = TransactionGroup.frozen(List.of(child, TransactionGroup.callbacks(() -> {
        }, () -> {
        }, () -> {
        })));
        assertSame(ordinary, assertThrows(IllegalStateException.class, () -> group.rollbackWithinBudget(new RecipeWorkBudget(20))));
        assertNull(group.resolutionWithinBudget(new RecipeWorkBudget(1)));
        var lastCredit = new RecipeWorkBudget(1);
        assertSame(fatal, assertThrows(StackOverflowError.class, () -> group.resolutionWithinBudget(lastCredit)));
        assertEquals(0, lastCredit.remaining());
        assertEquals(1, queries.get());
    }
}
