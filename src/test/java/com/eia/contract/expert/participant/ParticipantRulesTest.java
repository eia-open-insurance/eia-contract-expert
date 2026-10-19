package com.eia.contract.expert.participant;

import com.eia.contract.expert.ContractConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipantRulesTest {

    @Test
    void treatsOneHundredWithinToleranceAsBalanced() {
        assertThat(ParticipantRules.isBalanced(new BigDecimal("100.000000"))).isTrue();
        assertThat(ParticipantRules.isBalanced(new BigDecimal("100.0001"))).isTrue();
        assertThat(ParticipantRules.isBalanced(new BigDecimal("99.9999"))).isTrue();
    }

    @Test
    void treatsTotalsOutsideToleranceAsUnbalanced() {
        assertThat(ParticipantRules.isBalanced(new BigDecimal("99.99"))).isFalse();
        assertThat(ParticipantRules.isBalanced(new BigDecimal("100.01"))).isFalse();
        assertThat(ParticipantRules.isBalanced(BigDecimal.ZERO)).isFalse();
        assertThat(ParticipantRules.isBalanced(null)).isFalse();
    }

    @Test
    void rejectsSharesThatWouldExceedOneHundred() {
        assertThatThrownBy(() -> ParticipantRules.checkShareCapacity(
                "REINSURER", new BigDecimal("70"), new BigDecimal("35")))
                .isInstanceOf(ContractConflictException.class)
                .extracting(ex -> ((ContractConflictException) ex).code())
                .isEqualTo("SHARES_EXCEED_100");
    }

    @Test
    void allowsPartialPlacementBelowOneHundred() {
        assertThatCode(() -> ParticipantRules.checkShareCapacity(
                "REINSURER", new BigDecimal("0"), new BigDecimal("35")))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresCedentAndBrokerShares() {
        assertThat(ParticipantRules.isRiskBearing("BROKER")).isFalse();
        assertThat(ParticipantRules.isRiskBearing("CEDENT")).isFalse();
        assertThat(ParticipantRules.isRiskBearing("LEAD")).isTrue();
        assertThatCode(() -> ParticipantRules.checkShareCapacity(
                "BROKER", new BigDecimal("100"), new BigDecimal("100")))
                .doesNotThrowAnyException();
    }
}
