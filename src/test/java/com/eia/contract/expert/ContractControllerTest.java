package com.eia.contract.expert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private static final UUID TENANT = UUID.fromString("11111111-1111-7111-8111-111111111111");
    private static final UUID CONTRACT_ID = UUID.fromString("22222222-2222-7222-8222-222222222222");
    private static final UUID CEDENT = UUID.fromString("33333333-3333-7333-8333-333333333333");
    private static final UUID REINSURER = UUID.fromString("44444444-4444-7444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository repository;

    private static Contract contract(String status, int version) {
        return new Contract(CONTRACT_ID, TENANT, "XOL-2025-00091", "EXCESS_OF_LOSS", CEDENT, REINSURER,
                status, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), "USD", version, NOW, NOW);
    }

    private static String createBody(String effectiveDate, String expirationDate) {
        return """
                {
                  "tenantId": "%s",
                  "contractNumber": "XOL-2025-00091",
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
                .thenReturn(contract("DRAFT", 0));

        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2025-01-01", "2025-12-31")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(CONTRACT_ID.toString()))
                .andExpect(jsonPath("$.contractNumber").value("XOL-2025-00091"))
                .andExpect(jsonPath("$.contractType").value("EXCESS_OF_LOSS"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void rejectsExpirationBeforeEffectiveDate() throws Exception {
        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2025-12-31", "2025-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"))
                .andExpect(jsonPath("$.message").value("effectiveDate must be on or before expirationDate"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnknownContractType() throws Exception {
        String body = createBody("2025-01-01", "2025-12-31")
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
        String body = createBody("2025-01-01", "2025-12-31").replace("XOL-2025-00091", "");

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
                .thenThrow(new DuplicateContractNumberException("XOL-2025-00091"));

        mvc.perform(post("/internal/v1/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("2025-01-01", "2025-12-31")))
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
        when(repository.findAll(TENANT)).thenReturn(List.of(contract("DRAFT", 0)));

        mvc.perform(get("/internal/v1/contracts").param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contractNumber").value("XOL-2025-00091"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void rejectsIllegalStatusTransition() throws Exception {
        when(repository.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(contract("DRAFT", 0)));

        mvc.perform(patch("/internal/v1/contracts/{id}/status", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status": "BOUND", "expectedVersion": 0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(repository, never()).updateStatus(any(), any(), any(), anyInt());
    }

    @Test
    void appliesLegalStatusTransition() throws Exception {
        when(repository.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(contract("DRAFT", 0)));
        when(repository.updateStatus(TENANT, CONTRACT_ID, ContractStatus.QUOTED, 0))
                .thenReturn(contract("QUOTED", 1));

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
}
