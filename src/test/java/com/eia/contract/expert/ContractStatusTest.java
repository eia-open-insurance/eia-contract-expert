package com.eia.contract.expert;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the SPEC §11.3.6 lifecycle table. */
class ContractStatusTest {

    @Test
    void followsTheDocumentedLifecycle() {
        assertTrue(ContractStatus.DRAFT.canTransitionTo(ContractStatus.QUOTED));
        assertTrue(ContractStatus.QUOTED.canTransitionTo(ContractStatus.PLACED));
        assertTrue(ContractStatus.PLACED.canTransitionTo(ContractStatus.BOUND));
        assertTrue(ContractStatus.BOUND.canTransitionTo(ContractStatus.SIGNED));
        assertTrue(ContractStatus.SIGNED.canTransitionTo(ContractStatus.IN_FORCE));
        assertTrue(ContractStatus.IN_FORCE.canTransitionTo(ContractStatus.ENDORSED));
        assertTrue(ContractStatus.ENDORSED.canTransitionTo(ContractStatus.IN_FORCE));
        assertTrue(ContractStatus.IN_FORCE.canTransitionTo(ContractStatus.EXPIRED));
        assertTrue(ContractStatus.IN_FORCE.canTransitionTo(ContractStatus.CANCELLED));
        assertTrue(ContractStatus.IN_FORCE.canTransitionTo(ContractStatus.COMMUTED));
        assertTrue(ContractStatus.EXPIRED.canTransitionTo(ContractStatus.RUNOFF));
        assertTrue(ContractStatus.EXPIRED.canTransitionTo(ContractStatus.CLOSED));
        assertTrue(ContractStatus.RUNOFF.canTransitionTo(ContractStatus.COMMUTED));
        assertTrue(ContractStatus.RUNOFF.canTransitionTo(ContractStatus.CLOSED));
        assertTrue(ContractStatus.COMMUTED.canTransitionTo(ContractStatus.CLOSED));
        assertTrue(ContractStatus.CANCELLED.canTransitionTo(ContractStatus.CLOSED));
    }

    @Test
    void rejectsShortcutsAndTerminalMoves() {
        assertFalse(ContractStatus.DRAFT.canTransitionTo(ContractStatus.BOUND));
        assertFalse(ContractStatus.DRAFT.canTransitionTo(ContractStatus.IN_FORCE));
        assertFalse(ContractStatus.QUOTED.canTransitionTo(ContractStatus.IN_FORCE));
        assertFalse(ContractStatus.IN_FORCE.canTransitionTo(ContractStatus.CLOSED));
        assertFalse(ContractStatus.CLOSED.canTransitionTo(ContractStatus.DRAFT));
        assertFalse(ContractStatus.VOID.canTransitionTo(ContractStatus.DRAFT));
        assertFalse(ContractStatus.CANCELLED.canTransitionTo(ContractStatus.IN_FORCE));
    }

    @Test
    void exposesAllowedNextStates() {
        assertEquals(EnumSet.of(ContractStatus.QUOTED, ContractStatus.VOID), ContractStatus.DRAFT.allowedNext());
        assertEquals(EnumSet.of(ContractStatus.ENDORSED, ContractStatus.EXPIRED,
                ContractStatus.CANCELLED, ContractStatus.COMMUTED), ContractStatus.IN_FORCE.allowedNext());
        assertTrue(ContractStatus.CLOSED.isTerminal());
        assertTrue(ContractStatus.VOID.isTerminal());
        assertFalse(ContractStatus.IN_FORCE.isTerminal());
    }
}
