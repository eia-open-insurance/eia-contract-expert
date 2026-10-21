package com.eia.contract.expert.security;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractValidationException;
import com.eia.contract.expert.security.SecurityRules.SecurityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The security lifecycle matrix.
 *
 * <p>A live record may be released or expired; an expired record may still be
 * released; release is terminal. The public action accepts only those two target
 * statuses.</p>
 */
class ContractSecurityRulesTest {

    private static final List<SecurityStatus> LIVE =
            List.of(SecurityStatus.ACTIVE, SecurityStatus.OUTSTANDING,
                    SecurityStatus.PARTIAL, SecurityStatus.REQUIRED);

    private static void assertConflict(SecurityStatus from, SecurityStatus target) {
        assertThatThrownBy(() -> ContractSecurityRules.validateTransition(from, target))
                .as("%s -> %s", from, target)
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).code())
                        .isEqualTo("INVALID_STATUS_TRANSITION"));
    }

    @Test
    void liveStatusesCanBeReleasedOrExpired() {
        for (SecurityStatus from : LIVE) {
            assertThatCode(() -> ContractSecurityRules.validateTransition(
                    from, SecurityStatus.RELEASED))
                    .as("%s -> RELEASED", from)
                    .doesNotThrowAnyException();
            assertThatCode(() -> ContractSecurityRules.validateTransition(
                    from, SecurityStatus.EXPIRED))
                    .as("%s -> EXPIRED", from)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void expiredCanStillBeReleased() {
        assertThatCode(() -> ContractSecurityRules.validateTransition(
                SecurityStatus.EXPIRED, SecurityStatus.RELEASED))
                .doesNotThrowAnyException();
    }

    @Test
    void expiredCannotBeExpiredAgain() {
        assertConflict(SecurityStatus.EXPIRED, SecurityStatus.EXPIRED);
    }

    @Test
    void releasedIsTerminal() {
        for (SecurityStatus target : SecurityStatus.values()) {
            assertConflict(SecurityStatus.RELEASED, target);
        }
    }

    @Test
    void breachCannotBeReleasedOrExpired() {
        assertConflict(SecurityStatus.BREACH, SecurityStatus.RELEASED);
        assertConflict(SecurityStatus.BREACH, SecurityStatus.EXPIRED);
    }

    @Test
    void parsesOnlyReleasedAndExpiredAsTheTargetStatus() {
        assertThat(ContractSecurityRules.parseTargetStatus("released"))
                .isEqualTo(SecurityStatus.RELEASED);
        assertThat(ContractSecurityRules.parseTargetStatus(" Expired "))
                .isEqualTo(SecurityStatus.EXPIRED);

        for (SecurityStatus target : SecurityStatus.values()) {
            if (target == SecurityStatus.RELEASED || target == SecurityStatus.EXPIRED) {
                continue;
            }
            assertThatThrownBy(() -> ContractSecurityRules.parseTargetStatus(target.name()))
                    .as("target %s", target)
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                            .isEqualTo("INVALID_STATUS"));
        }
    }

    @Test
    void rejectsMissingOrUnknownTargetStatuses() {
        for (String raw : List.of("APPROVED", "RELEASED_NOW")) {
            assertThatThrownBy(() -> ContractSecurityRules.parseTargetStatus(raw))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                            .isEqualTo("INVALID_STATUS"));
        }
        for (String raw : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> ContractSecurityRules.parseTargetStatus(raw))
                    .isInstanceOf(ContractValidationException.class)
                    .satisfies(ex -> assertThat(((ContractValidationException) ex).code())
                            .isEqualTo("INVALID_STATUS"));
        }
    }
}
