package com.eia.contract.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static com.eia.contract.expert.Fixtures.CEDENT;
import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.REINSURER;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractController.class)
class ContractControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository repository;

    private static String createBody(String effectiveDate, String expirationDate) {
        return """
                {
                  "tenantId": "%s",
                  "contractNumber": "XOL-2026-00091",
                  "contractType": "EXCESS_OF_LOSS",
                  "cedentPartyId": "%s",
                  "reinsurerPartyId": "%s",
                  "effectiveDate": "%s",
                  "expirationDate": "%s",
                  "currency": "USD"
                }
                """.formatted(TENANT, CEDENT, REINSURER, effectiveDate, expirationDate);
    }

    @Test
    void createsDraftContract() throws Exception {
        when(repository.create(any(CreateContractRequest.class), eq(ContractType.EXCESS_OF_LOSS), eq(ContractStatus.DRAFT)))
                .thenReturn(Fixtures.contract("DRAFT", 0));

        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2026-01-01", "2026-12-31")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONTRACT_ID.toString()))
                .andExpect(jsonPath("$.contractNumber").value("XOL-2026-00091"))
                .andExpect(jsonPath("$.contractType").value("EXCESS_OF_LOSS"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void rejectsExpirationBeforeEffectiveDate() throws Exception {
        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2026-12-31", "2026-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
                .andExpect(jsonPath("$.message").value("effectiveDate must be on or before expirationDate"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnknownContractType() throws Exception {
        String body = createBody("2026-01-01", "2026-12-31")
                .replace("EXCESS_OF_LOSS", "MYSTERY_TREATY");

        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONTRACT_TYPE"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBlankContractNumber() throws Exception {
        String body = createBody("2026-01-01", "2026-12-31").replace("XOL-2026-00091", "");

        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("contractNumber"));

        verifyNoInteractions(repository);
    }

    @Test
    void reportsDuplicateContractNumber() throws Exception {
        when(repository.create(any(CreateContractRequest.class), any(ContractType.class), any(ContractStatus.class)))
                .thenThrow(new DuplicateContractNumberException("XOL-2026-00091"));

        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2026-01-01", "2026-12-31")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CONTRACT_NUMBER"));
    }

    @Test
    void returnsNotFoundForUnknownContract() throws Exception {
        when(repository.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(get("/internal/v1/contracts/{id}", CONTRACT_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void listsContractsForTenant() throws Exception {
        when(repository.findAll(TENANT)).thenReturn(List.of(Fixtures.contract("DRAFT", 0)));

        mvc.perform(get("/internal/v1/contracts").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contractNumber").value("XOL-2026-00091"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void rejectsIllegalStatusTransition() throws Exception {
        when(repository.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("DRAFT", 0)));

        mvc.perform(patch("/internal/v1/contracts/{id}/status", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "QUOTED", "expectedVersion": 0}
                                """.replace("QUOTED", "SIGNED")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void appliesLegalStatusTransition() throws Exception {
        when(repository.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("DRAFT", 0)));
        when(repository.updateStatus(TENANT, CONTRACT_ID, ContractStatus.QUOTED, 0))
                .thenReturn(Fixtures.contract("QUOTED", 1));

        mvc.perform(patch("/internal/v1/contracts/{id}/status", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "QUOTED", "expectedVersion": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.version").value(1));

        verify(repository).updateStatus(TENANT, CONTRACT_ID, ContractStatus.QUOTED, 0);
    }

    /** SPEC 11.3.8: bind is a command with preconditions and approval, not a bare PATCH. */
    @Test
    void rejectsBoundTransitionThroughStatusPatch() throws Exception {
        when(repository.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("PLACED", 2)));

        mvc.perform(patch("/internal/v1/contracts/{id}/status", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "BOUND", "expectedVersion": 2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BIND_REQUIRES_COMMAND"))
                .andExpect(jsonPath("$.details[0].field").value("status"));

        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }
}
