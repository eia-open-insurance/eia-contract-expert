package com.eia.contract.expert.layer;

import com.eia.contract.expert.ContractRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.LAYER_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LayerController.class)
class LayerControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private LayerRepository layers;

    private static String body(String attachment, String limit) {
        return """
                {
                  "layerNumber": 1,
                  "layerType": "EXCESS_OF_LOSS",
                  "attachmentAmount": "%s",
                  "limitAmount": "%s",
                  "limitBasis": "PER_OCCURRENCE"
                }
                """.formatted(attachment, limit);
    }

    @Test
    void listsLayersWithMoneyAsStrings() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));
        when(layers.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(com.eia.contract.expert.Fixtures.layer(1, "1000000", "2000000")));

        mvc.perform(get("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].layerNumber").value(1))
                .andExpect(jsonPath("$[0].layerType").value("EXCESS_OF_LOSS"))
                .andExpect(jsonPath("$[0].attachmentAmount").value("1000000"))
                .andExpect(jsonPath("$[0].limitAmount").value("2000000"))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    void createsLayer() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(layers.create(any(ContractLayer.class)))
                .thenReturn(com.eia.contract.expert.Fixtures.layer(1, "1000000", "1000000"));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "1000000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.layerNumber").value(1))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"))
                .andExpect(jsonPath("$.effectiveTo").value("2026-12-31"));
    }

    @Test
    void rejectsUnknownLayerType() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "1000000").replace("EXCESS_OF_LOSS", "MAGIC")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LAYER_TYPE"));

        verify(layers, never()).create(any());
    }

    @Test
    void rejectsMissingAttachment() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(null, "1000000").replace("\"null\"", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ATTACHMENT_AMOUNT"));

        verify(layers, never()).create(any());
    }

    @Test
    void rejectsLayerCurrencyMismatch() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "1000000").replace("\"limitBasis\"", "\"currency\": \"EUR\", \"limitBasis\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAYER_CURRENCY_MISMATCH"));
    }

    @Test
    void rejectsAggregateLimitBelowLayerLimit() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "5000000")
                                .replace("\"limitBasis\"", "\"aggregateLimitAmount\": \"4000000\", \"limitBasis\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGGREGATE_LIMIT_BELOW_LAYER_LIMIT"));
    }

    /** SPEC CTR-02: two layers may not cover the same band. */
    @Test
    void rejectsOverlappingLayer() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of(
                com.eia.contract.expert.Fixtures.layer(1, "0", "1000000")));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("500000", "1000000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAYER_OVERLAP"))
                .andExpect(jsonPath("$.details[0].field").value("attachmentAmount"));

        verify(layers, never()).create(any());
    }

    @Test
    void acceptsAdjacentLayer() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of(
                com.eia.contract.expert.Fixtures.layer(1, "0", "1000000")));
        when(layers.create(any(ContractLayer.class)))
                .thenReturn(com.eia.contract.expert.Fixtures.layer(2, "1000000", "1000000"));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "1000000").replace("\"layerNumber\": 1", "\"layerNumber\": 2")))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsLayerOutsideContractTerm() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("DRAFT", 0)));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "1000000")
                                .replace("\"limitBasis\"", "\"effectiveFrom\": \"2025-12-01\", \"limitBasis\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAYER_OUTSIDE_CONTRACT_TERM"));
    }

    @Test
    void rejectsLayerWhenContractIsLocked() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("IN_FORCE", 4)));

        mvc.perform(post("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("1000000", "1000000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_LOCKED"));
    }

    @Test
    void reportsTowerSummaryWithGaps() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(com.eia.contract.expert.Fixtures.contract("PLACED", 2)));
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of(
                com.eia.contract.expert.Fixtures.layer(LAYER_ID, 1, "0", "1000000"),
                com.eia.contract.expert.Fixtures.layer(2, "2000000", "500000")));
        when(layers.capacity(TENANT, CONTRACT_ID))
                .thenReturn(new java.math.BigDecimal("1500000"));

        mvc.perform(get("/internal/v1/contracts/{id}/layers/summary", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.layerCount").value(2))
                .andExpect(jsonPath("$.capacity").value("1500000"))
                .andExpect(jsonPath("$.gaps.length()").value(1));
    }

    @Test
    void returnsNotFoundForUnknownContract() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/contracts/{id}/layers", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
