package com.eia.contract.expert.security;

import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityController.class)
class SecurityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private SecurityRepository securities;

    @MockitoBean
    private OutboxRepository outbox;

    private void contractInStatus(String status) {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.of(Fixtures.contract(status, 1)));
    }

    private static String body(String required, String held) {
        return """
                {
                  "securityType": "LETTER_OF_CREDIT",
                  "requiredAmount": "%s",
                  "heldAmount": "%s"
                }
                """.formatted(required, held);
    }

    @Test
    void listsSecurity() throws Exception {
        contractInStatus("BOUND");
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(
                Fixtures.security("5000000", "5000000", "OUTSTANDING")));

        mvc.perform(get("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].securityType").value("LETTER_OF_CREDIT"))
                .andExpect(jsonPath("$[0].requiredAmount").value("5000000"))
                .andExpect(jsonPath("$[0].heldAmount").value("5000000"))
                .andExpect(jsonPath("$[0].status").value("OUTSTANDING"));
    }

    @Test
    void createsFullyCollateralisedSecurity() throws Exception {
        contractInStatus("BOUND");
        when(securities.create(any(ContractSecurity.class)))
                .thenReturn(Fixtures.security("5000000", "5000000", "OUTSTANDING"));

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "5000000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OUTSTANDING"))
                .andExpect(jsonPath("$.currency").value("USD"));

        verify(outbox).insert(any());
    }

    @Test
    void defaultsStatusToRequiredWhenNothingIsHeld() throws Exception {
        contractInStatus("BOUND");
        when(securities.create(any(ContractSecurity.class)))
                .thenReturn(Fixtures.security("5000000", "0", "REQUIRED"));

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REQUIRED"));
    }

    /** The over-collateralisation control: collateral above the obligation needs approval. */
    @Test
    void rejectsCollateralAboveObligationWithoutApproval() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "7500000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLATERAL_EXCEEDS_OBLIGATION"))
                .andExpect(jsonPath("$.details[0].field").value("heldAmount"));

        verify(securities, never()).create(any());
    }

    @Test
    void acceptsCollateralAboveObligationWithApproval() throws Exception {
        contractInStatus("BOUND");
        when(securities.create(any(ContractSecurity.class)))
                .thenReturn(Fixtures.security("5000000", "7500000", "OUTSTANDING"));

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "7500000")
                                .replace("\"heldAmount\"",
                                        "\"approvalRef\": \"APR-2026-0007\", \"approvedAt\": \"2026-01-10T09:00:00Z\", \"heldAmount\"")))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsApprovalReferenceWithoutTimestamp() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "7500000")
                                .replace("\"heldAmount\"",
                                        "\"approvalRef\": \"APR-2026-0007\", \"heldAmount\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLATERAL_APPROVAL_INCOMPLETE"));
    }

    @Test
    void rejectsCurrencyMismatch() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "5000000")
                                .replace("\"heldAmount\"", "\"currency\": \"GBP\", \"heldAmount\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECURITY_CURRENCY_MISMATCH"));
    }

    @Test
    void rejectsExpiryBeforeEffectiveDate() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "5000000")
                                .replace("\"heldAmount\"", "\"expiryDate\": \"2025-06-01\", \"heldAmount\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_EXPIRY_DATE"));
    }

    @Test
    void rejectsSecurityWhenContractIsLocked() throws Exception {
        contractInStatus("IN_FORCE");

        mvc.perform(post("/internal/v1/contracts/{id}/security", CONTRACT_ID)
                        .param("tenantId", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("5000000", "5000000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_LOCKED"));
    }
}
