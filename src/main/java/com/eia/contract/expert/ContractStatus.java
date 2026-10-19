package com.eia.contract.expert;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Contract status lifecycle from SPEC §11.3.6.
 *
 * <p>The state diagram is the authoritative transition table. {@code VOID} is part of
 * the ContractStatus enumeration but is not drawn as a transition in the diagram; it is
 * modelled here as a terminal escape hatch from any non-terminal state, which keeps a
 * draft or placed contract from being permanently stuck when it is abandoned before
 * inception.</p>
 */
public enum ContractStatus {
    DRAFT,
    QUOTED,
    PLACED,
    BOUND,
    SIGNED,
    IN_FORCE,
    ENDORSED,
    EXPIRED,
    CANCELLED,
    COMMUTED,
    RUNOFF,
    CLOSED,
    VOID;

    private static final Map<ContractStatus, Set<ContractStatus>> ALLOWED;

    static {
        Map<ContractStatus, Set<ContractStatus>> allowed = new EnumMap<>(ContractStatus.class);
        allowed.put(DRAFT, EnumSet.of(QUOTED, VOID));
        allowed.put(QUOTED, EnumSet.of(PLACED, VOID));
        allowed.put(PLACED, EnumSet.of(BOUND, VOID));
        allowed.put(BOUND, EnumSet.of(SIGNED, VOID));
        allowed.put(SIGNED, EnumSet.of(IN_FORCE, VOID));
        allowed.put(IN_FORCE, EnumSet.of(ENDORSED, EXPIRED, CANCELLED, COMMUTED));
        allowed.put(ENDORSED, EnumSet.of(IN_FORCE));
        allowed.put(EXPIRED, EnumSet.of(RUNOFF, CLOSED));
        allowed.put(RUNOFF, EnumSet.of(COMMUTED, CLOSED));
        allowed.put(COMMUTED, EnumSet.of(CLOSED));
        allowed.put(CANCELLED, EnumSet.of(CLOSED));
        allowed.put(CLOSED, EnumSet.noneOf(ContractStatus.class));
        allowed.put(VOID, EnumSet.noneOf(ContractStatus.class));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    public Set<ContractStatus> allowedNext() {
        return ALLOWED.get(this);
    }

    public boolean canTransitionTo(ContractStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
