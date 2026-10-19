package com.eia.contract.expert.term;

import com.eia.contract.expert.ContractType;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Terms that must be recorded before a contract can bind, by contract type.
 *
 * <p>The matrix is deliberately small and explicit: it encodes the SPEC facts that
 * make a treaty administrable (commission and settlement basis for proportional
 * business, reinstatement and ultimate-net-loss definitions for non-proportional
 * business, and the portfolio evidence SPEC CTR-13 requires for LPT/ADC). Every
 * type also requires its governing law, so a contract can never bind without a
 * stated legal basis.</p>
 */
public final class RequiredTerms {

    private static final Set<TermCode> BASE = EnumSet.of(TermCode.GOVERNING_LAW);

    private static final Set<TermCode> PROPORTIONAL = EnumSet.of(
            TermCode.CEDING_COMMISSION_PCT, TermCode.SETTLEMENT_BASIS);

    private static final Set<TermCode> NON_PROPORTIONAL = EnumSet.of(
            TermCode.REINSTATEMENT_PROVISION, TermCode.ULTIMATE_NET_LOSS);

    private static final Set<TermCode> LOSS_PORTFOLIO = EnumSet.of(
            TermCode.PORTFOLIO_SCHEDULE_REF, TermCode.INCEPTION_BALANCE_AMOUNT,
            TermCode.ACTUARIAL_ASSUMPTIONS_REF);

    private static final Set<TermCode> ADVERSE_DEVELOPMENT = EnumSet.of(
            TermCode.ACTUARIAL_ASSUMPTIONS_REF, TermCode.DEVELOPMENT_PATTERN_REF);

    private static final Map<ContractType, Set<TermCode>> REQUIRED = new EnumMap<>(ContractType.class);

    static {
        required(EnumSet.of(ContractType.QUOTA_SHARE, ContractType.SURPLUS, ContractType.FACULTATIVE,
                ContractType.FACULTATIVE_OBLIGATORY, ContractType.PROPORTIONAL_TREATY,
                ContractType.FRONTING, ContractType.FINITE), PROPORTIONAL);
        required(EnumSet.of(ContractType.EXCESS_OF_LOSS, ContractType.AGGREGATE_EXCESS_OF_LOSS,
                ContractType.STOP_LOSS, ContractType.CATASTROPHE_EXCESS_OF_LOSS, ContractType.CLASH,
                ContractType.NON_PROPORTIONAL_TREATY), NON_PROPORTIONAL);
        required(EnumSet.of(ContractType.LOSS_PORTFOLIO_TRANSFER), LOSS_PORTFOLIO);
        required(EnumSet.of(ContractType.ADVERSE_DEVELOPMENT_COVER), ADVERSE_DEVELOPMENT);
        required(EnumSet.of(ContractType.RETROCESSION), EnumSet.of(TermCode.SETTLEMENT_BASIS));
    }

    private RequiredTerms() {
    }

    private static void required(Set<ContractType> types, Set<TermCode> terms) {
        for (ContractType type : types) {
            REQUIRED.put(type, terms);
        }
    }

    public static Set<TermCode> forType(String contractType) {
        Set<TermCode> required = new TreeSet<>();
        required.addAll(BASE);
        if (contractType != null) {
            try {
                required.addAll(REQUIRED.getOrDefault(
                        ContractType.valueOf(contractType), EnumSet.noneOf(TermCode.class)));
            } catch (IllegalArgumentException ignored) {
                // An unknown stored type cannot add requirements; creation validates the type.
            }
        }
        return required;
    }

    public static Set<TermCode> missing(String contractType, Set<String> presentCodes) {
        Set<TermCode> missing = new TreeSet<>(forType(contractType));
        missing.removeIf(code -> presentCodes.contains(code.name()));
        return missing;
    }
}
