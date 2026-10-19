package com.eia.contract.expert.commutation;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.ContractStatus;
import com.eia.contract.expert.Fixtures;
import com.eia.contract.expert.security.SecurityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.APPROVER;
import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.DOCUMENT;
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

@WebMvcTest(CommutationController.class)
@Import(CommutationService.class)
class CommutationControllerTest {

    private static final UUID COMMUTATION_ID =
            UUID.fromString("88888888-8888-8888-8888-888888888888");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private CommutationRepository commutations;

    @MockitoBean
    private SecurityRepository securities;

    @MockitoBean
    private BusinessKeys keys;

    private static String body(String type) {
        return """
                {
                  "commutationType": "%s",
                  "effectiveDate": "2026-12-31",
                  "valuationDate": "2026-11-30",
                  "agreedAmount": "2650000",
                  "savingsAmount": "230000",
                  "basis": "NEGOTIATED"
                }
                """.formatted(type);
    }

    @Test
    void listsCommutations() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        ContractCommutation commutation = Fixtures.commutation("PROPOSED", "FULL");
        when(commutations.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(commutation));

        mvc.perform(get("/internal/v1/contracts/{id}/commutations", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].commutationNumber").value("COM-2026-00001"))
                .andExpect(jsonPath("$[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$[0].agreedAmount").value("2650000"));
    }

    @Test
    void proposesCommutation() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        when(keys.next(TENANT, "COM", 2026)).thenReturn("COM-2026-00042");
        when(commutations.create(any(ContractCommutation.class)))
                .thenReturn(Fixtures.commutation("PROPOSED", "FULL"));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FULL")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.commutationType").value("FULL"));
    }

    @Test
    void rejectsCommutationOnDraftContract() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("DRAFT", 0)));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FULL")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUTATION_NOT_PERMITTED"));

        verify(commutations, never()).create(any());
    }

    @Test
    void rejectsValuationAfterEffectiveDate() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FULL").replace("\"valuationDate\": \"2026-11-30\"",
                                "\"valuationDate\": \"2027-02-01\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_COMMUTATION_DATES"));
    }

    @Test
    void rejectsCommutationCurrencyMismatch() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("FULL").replace("\"basis\"", "\"currency\": \"EUR\", \"basis\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUTATION_CURRENCY_MISMATCH"));
    }

    @Test
    void rejectsApprovalWithoutApprover() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/approve", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMUTATION_APPROVAL_REQUIRED"));

        verify(commutations, never()).approve(any(), any(), any(), any());
    }

    @Test
    void approvesCommutation() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        when(commutations.approve(TENANT, CONTRACT_ID, COMMUTATION_ID, APPROVER))
                .thenReturn(Fixtures.commutation("APPROVED", "FULL"));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/approve", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvedBy\": \"" + APPROVER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(APPROVER.toString()));
    }

    @Test
    void rejectsExecutionWithoutReleaseDocument() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        when(commutations.get(TENANT, CONTRACT_ID, COMMUTATION_ID))
                .thenReturn(Fixtures.commutation("APPROVED", "FULL"));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/execute", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUTATION_RELEASE_REQUIRED"));

        verify(commutations, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void executesFullCommutationAndClosesContract() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        ContractCommutation approved = Fixtures.commutation("APPROVED", "FULL");
        when(commutations.get(TENANT, CONTRACT_ID, COMMUTATION_ID)).thenReturn(approved);
        when(commutations.hasExecuted(TENANT, CONTRACT_ID)).thenReturn(false);
        when(commutations.execute(eq(TENANT), eq(CONTRACT_ID), eq(COMMUTATION_ID), eq(DOCUMENT), any(LocalDate.class)))
                .thenReturn(Fixtures.commutation("EXECUTED", "FULL"));
        when(securities.releaseAll(eq(TENANT), eq(CONTRACT_ID), any(LocalDate.class))).thenReturn(2);
        Contract closed = new Contract(CONTRACT_ID, TENANT, "XOL-2026-00091", "EXCESS_OF_LOSS",
                Fixtures.CEDENT, Fixtures.REINSURER, "CLOSED", Fixtures.FROM, Fixtures.TO, "USD",
                4, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW, APPROVER, Instant.parse("2026-12-31T00:00:00Z"),
                Instant.parse("2026-12-31T00:00:00Z"));
        when(contracts.applyCommutation(eq(TENANT), eq(CONTRACT_ID), eq(ContractStatus.CLOSED),
                any(Instant.class), any(Instant.class), eq(3))).thenReturn(closed);

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/execute", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseDocumentId\": \"" + DOCUMENT + "\", \"releaseDate\": \"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractClosed").value(true))
                .andExpect(jsonPath("$.releasedSecurities").value(2))
                .andExpect(jsonPath("$.commutation.status").value("EXECUTED"))
                .andExpect(jsonPath("$.contract.status").value("CLOSED"));

        verify(securities).releaseAll(TENANT, CONTRACT_ID, LocalDate.of(2026, 12, 31));
    }

    @Test
    void executesPartialCommutationAndLeavesContractCommutated() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        ContractCommutation approved = Fixtures.commutation("APPROVED", "PARTIAL");
        when(commutations.get(TENANT, CONTRACT_ID, COMMUTATION_ID)).thenReturn(approved);
        when(commutations.hasExecuted(TENANT, CONTRACT_ID)).thenReturn(false);
        when(commutations.execute(eq(TENANT), eq(CONTRACT_ID), eq(COMMUTATION_ID), eq(DOCUMENT), any(LocalDate.class)))
                .thenReturn(Fixtures.commutation("EXECUTED", "PARTIAL"));
        when(securities.releaseAll(eq(TENANT), eq(CONTRACT_ID), any(LocalDate.class))).thenReturn(1);
        Contract commuted = new Contract(CONTRACT_ID, TENANT, "XOL-2026-00091", "EXCESS_OF_LOSS",
                Fixtures.CEDENT, Fixtures.REINSURER, "COMMUTED", Fixtures.FROM, Fixtures.TO, "USD",
                4, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW, APPROVER,
                Instant.parse("2026-12-31T00:00:00Z"), null);
        when(contracts.applyCommutation(eq(TENANT), eq(CONTRACT_ID), eq(ContractStatus.COMMUTED),
                any(Instant.class), isNull(), eq(3))).thenReturn(commuted);

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/execute", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseDocumentId\": \"" + DOCUMENT + "\", \"releaseDate\": \"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractClosed").value(false))
                .andExpect(jsonPath("$.contract.status").value("COMMUTED"));
    }

    @Test
    void rejectsExecutionBeforeApproval() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("IN_FORCE", 3)));
        when(commutations.get(TENANT, CONTRACT_ID, COMMUTATION_ID))
                .thenReturn(Fixtures.commutation("PROPOSED", "FULL"));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/execute", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseDocumentId\": \"" + DOCUMENT + "\", \"releaseDate\": \"2026-12-31\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUTATION_NOT_APPROVED"));

        verify(commutations, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsExecutionOnBoundContract() throws Exception {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("BOUND", 3)));

        mvc.perform(post("/internal/v1/contracts/{id}/commutations/{commutationId}/execute", CONTRACT_ID, COMMUTATION_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"releaseDocumentId\": \"" + DOCUMENT + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUTATION_NOT_PERMITTED"));
    }
}
