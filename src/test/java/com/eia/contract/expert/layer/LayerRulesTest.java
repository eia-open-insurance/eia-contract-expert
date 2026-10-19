package com.eia.contract.expert.layer;

import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.Fixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LayerRulesTest {

    private final Contract contract = Fixtures.contract("DRAFT", 0);

    @Test
    void rejectsOverlappingLayers() {
        ContractLayer below = Fixtures.layer(1, "0", "1000000");
        ContractLayer above = Fixtures.layer(2, "500000", "1000000");

        assertThatThrownBy(() -> LayerRules.checkTower(List.of(below), above))
                .isInstanceOf(ContractConflictException.class)
                .hasMessageContaining("Layer 2 attaches at 500000")
                .extracting(ex -> ((ContractConflictException) ex).code())
                .isEqualTo("LAYER_OVERLAP");
    }

    @Test
    void acceptsAdjacentLayers() {
        ContractLayer below = Fixtures.layer(1, "0", "1000000");
        ContractLayer above = Fixtures.layer(2, "1000000", "1000000");

        assertThatCode(() -> LayerRules.checkTower(List.of(below), above))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsGapsWithoutFailingTheTower() {
        ContractLayer lower = Fixtures.layer(1, "0", "1000000");
        ContractLayer upper = Fixtures.layer(2, "2000000", "500000");

        assertThatCode(() -> LayerRules.checkTower(List.of(lower, upper)))
                .doesNotThrowAnyException();
        assertThat(LayerRules.gaps(List.of(lower, upper)))
                .singleElement()
                .asString()
                .contains("Gap between layer 1 top 1000000");
    }

    @Test
    void validatesAggregateLimitAgainstLayerLimit() {
        LayerController.CreateLayerRequest request = new LayerController.CreateLayerRequest(
                1, "AGGREGATE_EXCESS", new BigDecimal("0"), new BigDecimal("5000000"),
                "USD", "ANNUAL_AGGREGATE", new BigDecimal("4000000"), null, null, null,
                Fixtures.FROM, Fixtures.TO, null);

        assertThatThrownBy(() -> LayerRules.validate(contract, request))
                .isInstanceOf(ContractConflictException.class)
                .extracting(ex -> ((ContractConflictException) ex).code())
                .isEqualTo("AGGREGATE_LIMIT_BELOW_LAYER_LIMIT");
    }

    @Test
    void defaultsCurrencyAndDatesFromTheContract() {
        LayerController.CreateLayerRequest request = new LayerController.CreateLayerRequest(
                1, "EXCESS_OF_LOSS", new BigDecimal("1000000"), new BigDecimal("500000"),
                null, "PER_OCCURRENCE", null, null, null, "Gap above the working layer",
                null, null, null);

        ContractLayer layer = LayerRules.validate(contract, request);

        assertThat(layer.currency()).isEqualTo("USD");
        assertThat(layer.effectiveFrom()).isEqualTo(Fixtures.FROM);
        assertThat(layer.effectiveTo()).isEqualTo(Fixtures.TO);
        assertThat(layer.status()).isEqualTo("QUOTED");
        assertThat(layer.attachmentAmount()).isEqualByComparingTo("1000000");
        assertThat(layer.top()).isEqualByComparingTo("1500000");
    }

    @Test
    void rejectsReinstatementCountWithoutType() {
        LayerController.CreateLayerRequest request = new LayerController.CreateLayerRequest(
                1, "EXCESS_OF_LOSS", new BigDecimal("0"), new BigDecimal("1000000"),
                "USD", "PER_OCCURRENCE", null, 2, "NONE", null, Fixtures.FROM, Fixtures.TO, null);

        assertThatThrownBy(() -> LayerRules.validate(contract, request))
                .isInstanceOf(ContractConflictException.class)
                .extracting(ex -> ((ContractConflictException) ex).code())
                .isEqualTo("REINSTATEMENT_TYPE_REQUIRED");
    }

    @Test
    void rejectsCurrencyThatDiffersFromTheContract() {
        LayerController.CreateLayerRequest request = new LayerController.CreateLayerRequest(
                1, "EXCESS_OF_LOSS", new BigDecimal("0"), new BigDecimal("1000000"),
                "EUR", "PER_OCCURRENCE", null, null, null, null, Fixtures.FROM, Fixtures.TO, null);

        assertThatThrownBy(() -> LayerRules.validate(contract, request))
                .isInstanceOf(ContractConflictException.class)
                .extracting(ex -> ((ContractConflictException) ex).code())
                .isEqualTo("LAYER_CURRENCY_MISMATCH");
    }
}
