package com.eia.contract.expert.term;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredTermsTest {

    @Test
    void requiresGoverningLawForEveryContractType() {
        for (String type : List.of("QUOTA_SHARE", "EXCESS_OF_LOSS", "LOSS_PORTFOLIO_TRANSFER",
                "ADVERSE_DEVELOPMENT_COVER", "RETROCESSION", "FINITE")) {
            assertThat(RequiredTerms.forType(type))
                    .as(type)
                    .contains(TermCode.GOVERNING_LAW);
        }
    }

    @Test
    void requiresCommissionAndSettlementForProportionalBusiness() {
        assertThat(RequiredTerms.forType("QUOTA_SHARE"))
                .contains(TermCode.CEDING_COMMISSION_PCT, TermCode.SETTLEMENT_BASIS);
    }

    @Test
    void requiresReinstatementAndUltimateNetLossForNonProportionalBusiness() {
        assertThat(RequiredTerms.forType("EXCESS_OF_LOSS"))
                .contains(TermCode.REINSTATEMENT_PROVISION, TermCode.ULTIMATE_NET_LOSS);
    }

    @Test
    void requiresPortfolioEvidenceForLossPortfolioTransfer() {
        assertThat(RequiredTerms.forType("LOSS_PORTFOLIO_TRANSFER"))
                .contains(TermCode.PORTFOLIO_SCHEDULE_REF, TermCode.INCEPTION_BALANCE_AMOUNT,
                        TermCode.ACTUARIAL_ASSUMPTIONS_REF);
    }

    @Test
    void reportsOnlyTheMissingTerms() {
        Set<String> present = new LinkedHashSet<>(List.of("GOVERNING_LAW", "ULTIMATE_NET_LOSS"));

        assertThat(RequiredTerms.missing("EXCESS_OF_LOSS", present))
                .containsExactly(TermCode.REINSTATEMENT_PROVISION);
    }

    @Test
    void reportsEverythingMissingWhenNothingIsRecorded() {
        assertThat(RequiredTerms.missing("QUOTA_SHARE", Set.of()))
                .containsExactly(TermCode.CEDING_COMMISSION_PCT, TermCode.GOVERNING_LAW,
                        TermCode.SETTLEMENT_BASIS);
    }
}
