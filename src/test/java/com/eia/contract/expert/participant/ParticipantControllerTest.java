package com.eia.contract.expert.participant;

import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.contract.expert.ResourceNotFoundException;
import com.eia.contract.expert.layer.LayerRepository;
import org.junit.jupiter.api.Test;
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
import static com.eia.contract.expert.Fixtures.REINSURER;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ParticipantController.class)
class ParticipantControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private ParticipantRepository participants;

    @MockitoBean
    private LayerRepository layers;

    private static String body(String role, String share) {
        return """
                {
                  "partyId": "%s",
                  "participantRole": "%s",
                  "sharePct": "%s"
                }
                """.formatted(REINSURER, role, share);
    }

    private void contractInStatus(String status) {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract(status, 1)));
    }

    @Test
    void listsParticipantsWithShareAsString() throws Exception {
        contractInStatus("DRAFT");
        when(participants.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(
                Fixtures.participant("REINSURER", "60.000000", null)));

        mvc.perform(get("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].participantRole").value("REINSURER"))
                .andExpect(jsonPath("$[0].sharePct").value("60.000000"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void createsParticipant() throws Exception {
        contractInStatus("PLACED");
        when(participants.activeShareTotal(TENANT, CONTRACT_ID, null)).thenReturn(new BigDecimal("40"));
        when(participants.create(any(ContractParticipant.class)))
                .thenReturn(Fixtures.participant("REINSURER", "60.000000", null));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sharePct").value("60.000000"))
                .andExpect(jsonPath("$.signatureStatus").value("PENDING"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-01-01"));
    }

    /** SPEC CTR-01: shares may never be pushed above 100% for a layer. */
    @Test
    void rejectsShareThatExceedsOneHundredPercent() throws Exception {
        contractInStatus("PLACED");
        when(participants.activeShareTotal(TENANT, CONTRACT_ID, null)).thenReturn(new BigDecimal("70"));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "40")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHARES_EXCEED_100"));

        verify(participants, never()).create(any());
    }

    @Test
    void acceptsSharesThatCompleteOneHundredPercent() throws Exception {
        contractInStatus("PLACED");
        when(participants.activeShareTotal(TENANT, CONTRACT_ID, null)).thenReturn(new BigDecimal("40"));
        when(participants.create(any(ContractParticipant.class)))
                .thenReturn(Fixtures.participant("REINSURER", "60.000000", null));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "60")))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsShareAboveOneHundred() throws Exception {
        contractInStatus("PLACED");

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("BROKER", "200")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SHARE_PCT"));
    }

    @Test
    void rejectsLeadWithoutLeadFlag() throws Exception {
        contractInStatus("PLACED");

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LEAD", "10")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEAD_FLAG_REQUIRED"));
    }

    @Test
    void rejectsSignedLineAboveShare() throws Exception {
        contractInStatus("PLACED");

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "10")
                                .replace("\"sharePct\"", "\"signedLinePct\": \"20\", \"sharePct\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIGNED_LINE_EXCEEDS_SHARE"));
    }

    @Test
    void rejectsParticipationOutsideContractTerm() throws Exception {
        contractInStatus("PLACED");

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "10")
                                .replace("\"sharePct\"", "\"effectiveFrom\": \"2025-06-01\", \"sharePct\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_OUTSIDE_CONTRACT_TERM"));
    }

    @Test
    void rejectsUnknownLayer() throws Exception {
        contractInStatus("PLACED");
        when(layers.get(eq(TENANT), eq(CONTRACT_ID), any(UUID.class)))
                .thenThrow(new ResourceNotFoundException("Layer", LAYER_ID));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "10")
                                .replace("\"sharePct\"", "\"layerId\": \"" + LAYER_ID + "\", \"sharePct\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsParticipationWhenContractIsLocked() throws Exception {
        contractInStatus("SIGNED");

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "10")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_LOCKED"));
    }

    @Test
    void reportsDuplicateParticipation() throws Exception {
        contractInStatus("PLACED");
        when(participants.activeShareTotal(TENANT, CONTRACT_ID, null)).thenReturn(BigDecimal.ZERO);
        when(participants.create(any(ContractParticipant.class)))
                .thenThrow(new ContractConflictException("DUPLICATE_PARTICIPANT", "already exists"));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "10")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PARTICIPANT"));
    }

    @Test
    void sumsContractLevelSharesForNullLayer() throws Exception {
        contractInStatus("PLACED");
        when(participants.activeShareTotal(TENANT, CONTRACT_ID, null)).thenReturn(BigDecimal.ZERO);
        when(participants.create(any(ContractParticipant.class)))
                .thenReturn(Fixtures.participant("REINSURER", "10.000000", null));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("REINSURER", "10")))
                .andExpect(status().isCreated());

        verify(participants).activeShareTotal(TENANT, CONTRACT_ID, null);
        verify(participants, never()).activeShareTotal(eq(TENANT), eq(CONTRACT_ID), any(UUID.class));
        verify(participants).create(any(ContractParticipant.class));
    }

    @Test
    void ignoresNonRiskBearingRoleForTheCap() throws Exception {
        contractInStatus("PLACED");
        when(participants.create(any(ContractParticipant.class)))
                .thenReturn(Fixtures.participant("CEDENT", "0.000000", null));

        mvc.perform(post("/internal/v1/contracts/{id}/participants", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("CEDENT", "0")))
                .andExpect(status().isCreated());

        verify(participants, never()).activeShareTotal(eq(TENANT), eq(CONTRACT_ID), isNull());
    }
}
