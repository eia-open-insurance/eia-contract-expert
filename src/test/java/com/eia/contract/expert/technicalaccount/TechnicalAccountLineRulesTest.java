package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ApiError;
import com.eia.contract.expert.Fixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TechnicalAccountLineRulesTest {

    @Test
    void mapsEveryLineTypeToItsReconciliationBucket() {
        assertThat(TechnicalAccountLineType.PREMIUM.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.PREMIUM);
        assertThat(TechnicalAccountLineType.COMMISSION.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.COMMISSION);
        assertThat(TechnicalAccountLineType.BROKERAGE.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.COMMISSION);
        assertThat(TechnicalAccountLineType.PAID_LOSS.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.LOSS);
        assertThat(TechnicalAccountLineType.OUTSTANDING_LOSS.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.LOSS);
        assertThat(TechnicalAccountLineType.IBNR.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.LOSS);
        assertThat(TechnicalAccountLineType.ALAE.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.LOSS);
        assertThat(TechnicalAccountLineType.REINSTATEMENT.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.LOSS);
        assertThat(TechnicalAccountLineType.CASH_CALL.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.LOSS);
        assertThat(TechnicalAccountLineType.FUNDS_WITHHELD.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.OTHER);
        assertThat(TechnicalAccountLineType.TAX.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.OTHER);
        assertThat(TechnicalAccountLineType.ADJUSTMENT.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.OTHER);
        assertThat(TechnicalAccountLineType.OTHER.bucket())
                .isEqualTo(TechnicalAccountLineType.Bucket.OTHER);

        assertThat(TechnicalAccountLineType.Bucket.PREMIUM.types())
                .containsExactly(TechnicalAccountLineType.PREMIUM);
        assertThat(TechnicalAccountLineType.Bucket.LOSS.types())
                .containsExactly(TechnicalAccountLineType.PAID_LOSS,
                        TechnicalAccountLineType.OUTSTANDING_LOSS,
                        TechnicalAccountLineType.IBNR,
                        TechnicalAccountLineType.ALAE,
                        TechnicalAccountLineType.REINSTATEMENT,
                        TechnicalAccountLineType.CASH_CALL);
        assertThat(TechnicalAccountLineType.Bucket.COMMISSION.types())
                .containsExactly(TechnicalAccountLineType.COMMISSION,
                        TechnicalAccountLineType.BROKERAGE);
        assertThat(TechnicalAccountLineType.Bucket.OTHER.types())
                .containsExactly(TechnicalAccountLineType.FUNDS_WITHHELD,
                        TechnicalAccountLineType.TAX,
                        TechnicalAccountLineType.ADJUSTMENT,
                        TechnicalAccountLineType.OTHER);
    }

    @Test
    void noLongerAcceptsTheLegacyAggregateLossType() {
        assertThatThrownBy(() -> TechnicalAccountLineType.valueOf("LOSS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sumsDetailedTotalsAndRollsThemIntoBucketTotals() {
        Map<TechnicalAccountLineType, BigDecimal> totals = TechnicalAccountLineRules.sumLines(
                List.of(
                        Fixtures.technicalAccountLine("PREMIUM", "1000.0000"),
                        Fixtures.technicalAccountLine("COMMISSION", "80.0000"),
                        Fixtures.technicalAccountLine("BROKERAGE", "20.0000"),
                        Fixtures.technicalAccountLine("PAID_LOSS", "250.0000"),
                        Fixtures.technicalAccountLine("OUTSTANDING_LOSS", "100.0000"),
                        Fixtures.technicalAccountLine("IBNR", "30.0000"),
                        Fixtures.technicalAccountLine("ALAE", "10.0000"),
                        Fixtures.technicalAccountLine("REINSTATEMENT", "5.0000"),
                        Fixtures.technicalAccountLine("CASH_CALL", "5.0000"),
                        Fixtures.technicalAccountLine("FUNDS_WITHHELD", "20.0000"),
                        Fixtures.technicalAccountLine("TAX", "15.0000"),
                        Fixtures.technicalAccountLine("ADJUSTMENT", "10.0000"),
                        Fixtures.technicalAccountLine("OTHER", "5.0000")));

        assertThat(totals).hasSize(13);
        assertThat(totals.get(TechnicalAccountLineType.PREMIUM))
                .isEqualByComparingTo("1000.0000");
        assertThat(totals.get(TechnicalAccountLineType.BROKERAGE))
                .isEqualByComparingTo("20.0000");
        assertThat(totals.get(TechnicalAccountLineType.REINSTATEMENT))
                .isEqualByComparingTo("5.0000");

        Map<TechnicalAccountLineType.Bucket, BigDecimal> buckets =
                TechnicalAccountLineRules.bucketTotals(totals);
        assertThat(buckets.get(TechnicalAccountLineType.Bucket.PREMIUM))
                .isEqualByComparingTo("1000.0000");
        assertThat(buckets.get(TechnicalAccountLineType.Bucket.LOSS))
                .isEqualByComparingTo("400.0000");
        assertThat(buckets.get(TechnicalAccountLineType.Bucket.COMMISSION))
                .isEqualByComparingTo("100.0000");
        assertThat(buckets.get(TechnicalAccountLineType.Bucket.OTHER))
                .isEqualByComparingTo("50.0000");

        assertThat(TechnicalAccountLineRules.formattedTotals(totals))
                .containsEntry("PREMIUM", "1000.0000")
                .containsEntry("PAID_LOSS", "250.0000")
                .containsEntry("REINSTATEMENT", "5.0000")
                .containsEntry("CASH_CALL", "5.0000")
                .hasSize(13);
        assertThat(TechnicalAccountLineRules.formattedBucketTotals(buckets))
                .containsExactly(
                        Map.entry("premium", "1000.0000"),
                        Map.entry("loss", "400.0000"),
                        Map.entry("commission", "100.0000"),
                        Map.entry("other", "50.0000"));
    }

    @Test
    void reconcilesBucketsRatherThanIndividualTypes() {
        ContractTechnicalAccount account = Fixtures.technicalAccount("DRAFT");

        assertThatCode(() -> TechnicalAccountLineRules.reconcile(account, List.of(
                new TechnicalAccountLineRepository.LineTotal(
                        "PREMIUM", new BigDecimal("1000.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "PAID_LOSS", new BigDecimal("250.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "OUTSTANDING_LOSS", new BigDecimal("100.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "IBNR", new BigDecimal("50.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "COMMISSION", new BigDecimal("60.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "BROKERAGE", new BigDecimal("40.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "FUNDS_WITHHELD", new BigDecimal("30.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "TAX", new BigDecimal("20.0000")))))
                .doesNotThrowAnyException();
    }

    @Test
    void skipsReconciliationForAHeaderOnlyAccount() {
        assertThatCode(() -> TechnicalAccountLineRules.reconcile(
                Fixtures.technicalAccount("DRAFT"), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsOnlyTheOffendingBucketAndNamesItsContributingTypes() {
        ContractTechnicalAccount account = Fixtures.technicalAccount("DRAFT");
        List<TechnicalAccountLineRepository.LineTotal> rows = List.of(
                new TechnicalAccountLineRepository.LineTotal(
                        "PREMIUM", new BigDecimal("1000.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "PAID_LOSS", new BigDecimal("250.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "IBNR", new BigDecimal("100.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "COMMISSION", new BigDecimal("100.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "OTHER", new BigDecimal("50.0000")));

        assertThatThrownBy(() -> TechnicalAccountLineRules.reconcile(account, rows))
                .isInstanceOf(TechnicalAccountLinesDoNotReconcileException.class)
                .satisfies(ex -> {
                    TechnicalAccountLinesDoNotReconcileException mismatch =
                            (TechnicalAccountLinesDoNotReconcileException) ex;
                    assertThat(mismatch.code()).isEqualTo("ACCOUNT_LINES_DO_NOT_RECONCILE");
                    assertThat(mismatch.mismatches()).extracting(ApiError.FieldViolation::field)
                            .containsExactly("LOSS");
                    assertThat(mismatch.mismatches().get(0).message())
                            .isEqualTo("PAID_LOSS, OUTSTANDING_LOSS, IBNR, ALAE, "
                                    + "REINSTATEMENT, CASH_CALL lines total 350.0000 "
                                    + "but cededLosses is 400.0000");
                });
    }
}
