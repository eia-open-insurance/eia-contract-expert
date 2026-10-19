package com.eia.contract.expert.cession;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.contract.expert.bordereau.BordereauRepository;
import com.eia.contract.expert.layer.LayerRepository;
import com.eia.contract.expert.security.SecurityRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.LAYER_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CessionController.class)
class CessionControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private CessionRepository cessions;

    @MockitoBean
    private LayerRepository layers;

    @MockitoBean
    private SecurityRepository securities;

    @MockitoBean
    private BordereauRepository bordereaux;

    @MockitoBean
    private BusinessKeys keys;

    private void contractInStatus(String status) {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract(status, 2)));
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());
    }

    private static String body(String loss) {
        return """
                {
                  "sourceType": "CLAIM",
                  "claimRef": "CLM-2026-0007",
                  "policyRef": "POL-2026-0001",
                  "cessionType": "QUOTA_SHARE",
                  "cessionPct": "40",
                  "cededPremiumAmount": "400000",
                  "cededLossAmount": "%s"
                }
                """.formatted(loss);
    }

    @Test
    void listsCessions() throws Exception {
        contractInStatus("BOUND");
        when(cessions.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(Fixtures.cession("240000")));

        mvc.perform(get("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cessionNumber").value("CES-2026-00001"))
                .andExpect(jsonPath("$[0].cededLossAmount").value("240000"))
                .andExpect(jsonPath("$[0].sourceVertical").value("ASSUMED"))
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    void recordsCessionWithinCapacity() throws Exception {
        contractInStatus("BOUND");
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of(Fixtures.layer(LAYER_ID, 1, "0", "1000000")));
        when(cessions.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(cessions.activeShareForSource(any(), any(), anyString(), any(), any(), anyInt()))
                .thenReturn(BigDecimal.ZERO);
        when(cessions.create(any(ContractCession.class))).thenReturn(Fixtures.cession("200000"));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECORDED"))
                .andExpect(jsonPath("$.cededLossAmount").value("200000"));
    }

    @Test
    void generatesCessionNumberWhenOmitted() throws Exception {
        contractInStatus("BOUND");
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(cessions.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(keys.next(TENANT, "CES", 2026)).thenReturn("CES-2026-00042");
        when(cessions.activeShareForSource(any(), any(), anyString(), any(), any(), anyInt()))
                .thenReturn(BigDecimal.ZERO);
        when(cessions.create(any(ContractCession.class))).thenReturn(Fixtures.cession("200000"));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")))
                .andExpect(status().isCreated());

        ArgumentCaptor<ContractCession> captor = ArgumentCaptor.forClass(ContractCession.class);
        verify(cessions).create(captor.capture());
        assertThat(captor.getValue().cessionNumber()).isEqualTo("CES-2026-00042");
    }

    /** SPEC CTR-04: cessions cannot exceed the contract limit. */
    @Test
    void rejectsCessionBeyondContractLimit() throws Exception {
        contractInStatus("BOUND");
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of(Fixtures.layer(LAYER_ID, 1, "0", "1000000")));
        when(cessions.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(Fixtures.cession("900000")));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CESSION_EXCEEDS_CONTRACT_LIMIT"))
                .andExpect(jsonPath("$.details[0].field").value("cededLossAmount"));

        verify(cessions, never()).create(any());
    }

    @Test
    void rejectsCessionBeyondLayerLimit() throws Exception {
        contractInStatus("BOUND");
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of(
                Fixtures.layer(LAYER_ID, 1, "0", "1000000"),
                Fixtures.layer(2, "1000000", "4000000")));
        when(cessions.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(
                Fixtures.cession("900000", LAYER_ID)));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")
                                .replace("\"sourceType\"", "\"layerId\": \"" + LAYER_ID + "\", \"sourceType\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CESSION_EXCEEDS_CONTRACT_LIMIT"));
    }

    @Test
    void rejectsCurrencyMismatch() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000").replace("\"cededLossAmount\"", "\"currency\": \"EUR\", \"cededLossAmount\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CESSION_CURRENCY_MISMATCH"));
    }

    /** The ASSUMED book wall: a DIRECT source is rejected outright. */
    @Test
    void rejectsDirectBookSource() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000").replace("\"sourceType\"", "\"sourceVertical\": \"DIRECT\", \"sourceType\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOK_WALL_VIOLATION"));
    }

    @Test
    void rejectsCessionOnDraftContract() throws Exception {
        contractInStatus("DRAFT");

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_INCEPTED"));
    }

    @Test
    void rejectsCessionWhenSecurityIsInBreach() throws Exception {
        contractInStatus("BOUND");
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(
                Fixtures.security("5000000", "1000000", "BREACH")));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_BREACH"));
    }

    @Test
    void rejectsCessionOutsideContractTerm() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000").replace("\"sourceType\"",
                                "\"effectiveFrom\": \"2025-06-01\", \"sourceType\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CESSION_OUTSIDE_CONTRACT_TERM"));
    }

    @Test
    void rejectsClaimCessionWithoutClaimReference() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000").replace("\"claimRef\": \"CLM-2026-0007\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CES_CLAIM_REF_REQUIRED"));
    }

    @Test
    void rejectsCessionWithoutAnyAmount() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceType": "CLAIM", "claimRef": "CLM-2026-0007",
                                 "cessionType": "QUOTA_SHARE", "cessionPct": "40"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CES_AMOUNT_REQUIRED"));
    }

    /** SPEC CTR-05: active cessions for one source may not exceed 100%. */
    @Test
    void rejectsSourceOverCeded() throws Exception {
        contractInStatus("BOUND");
        when(layers.findLive(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(cessions.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of());
        when(cessions.activeShareForSource(any(), any(), anyString(), any(), any(), anyInt()))
                .thenReturn(new BigDecimal("70"));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CESSION_SOURCE_OVER_CEDED"));
    }

    @Test
    void returnsNotFoundWhenLayerIsNotOnTheContract() throws Exception {
        contractInStatus("BOUND");
        when(layers.get(eq(TENANT), eq(CONTRACT_ID), any(UUID.class)))
                .thenThrow(new com.eia.contract.expert.ResourceNotFoundException("Layer", LAYER_ID));

        mvc.perform(post("/internal/v1/contracts/{id}/cessions", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("200000")
                                .replace("\"sourceType\"", "\"layerId\": \"" + LAYER_ID + "\", \"sourceType\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
