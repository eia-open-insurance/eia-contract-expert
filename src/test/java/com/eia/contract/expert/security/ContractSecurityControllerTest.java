package com.eia.contract.expert.security;

import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.FROM;
import static com.eia.contract.expert.Fixtures.REINSURER;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractSecurityController.class)
class ContractSecurityControllerTest {

    private static final String PATH = "/api/v1/contracts/{contractId}/security";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final LocalDate FUTURE = LocalDate.of(2030, 12, 31);
    private static final LocalDate PAST = LocalDate.of(2020, 1, 1);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private SecurityRepository securities;

    @MockitoBean
    private OutboxRepository outbox;

    private void contractInStatus(String status) {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract(status, 1)));
    }

    private static ContractSecurity security(String required, String held, String status,
                                             UUID provider, LocalDate expiry, String approvalRef) {
        return ContractSecurity.create(TENANT, CONTRACT_ID, "LETTER_OF_CREDIT",
                new BigDecimal(required).setScale(4), new BigDecimal(held).setScale(4),
                "USD", provider, null,
                FROM, null, expiry, status, null, null, null, approvalRef, null);
    }

    private static String body(String securityType, String required, String held,
                               String currency, String extra) {
        return """
                {
                  "securityType": %s,
                  "requiredAmount": %s,
                  "heldAmount": %s,
                  "currency": %s
                  %s
                }
                """.formatted(securityType, required, held, currency, extra);
    }

    @Test
    void listsNewestFirstWithExactlyThePublicFields() throws Exception {
        contractInStatus("BOUND");
        when(securities.findAll(TENANT, CONTRACT_ID)).thenReturn(List.of(
                security("5000000", "4000000", "ACTIVE", REINSURER, FUTURE, "APR-2026-0007"),
                security("1000000", "0", "EXPIRED", null, PAST, null)));

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].securityType").value("LETTER_OF_CREDIT"))
                .andExpect(jsonPath("$[0].requiredAmount").value("5000000.0000"))
                .andExpect(jsonPath("$[0].heldAmount").value("4000000.0000"))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].providerPartyId").value(REINSURER.toString()))
                .andExpect(jsonPath("$[0].expiryDate").value("2030-12-31"))
                .andExpect(jsonPath("$[0].approvalRef").value("APR-2026-0007"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(jsonPath("$[0].contractId").doesNotExist())
                .andExpect(jsonPath("$[0].beneficiaryPartyId").doesNotExist())
                .andExpect(jsonPath("$[1].status").value("EXPIRED"));
    }

    @Test
    void createsActiveSecurityAndPublishesTheRecordedEvent() throws Exception {
        contractInStatus("BOUND");
        when(securities.create(any(ContractSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"5000000\"", "\"4000000\"",
                                "\"USD\"", """
                                        ,"providerPartyId": "%s",
                                          "expiryDate": "2030-12-31",
                                          "approvalRef": "  APR-2026-0007  "
                                        """.formatted(REINSURER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.securityType").value("LETTER_OF_CREDIT"))
                .andExpect(jsonPath("$.requiredAmount").value("5000000.0000"))
                .andExpect(jsonPath("$.heldAmount").value("4000000.0000"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.providerPartyId").value(REINSURER.toString()))
                .andExpect(jsonPath("$.expiryDate").value("2030-12-31"))
                .andExpect(jsonPath("$.approvalRef").value("APR-2026-0007"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.tenantId").doesNotExist())
                .andExpect(jsonPath("$.contractId").doesNotExist());

        ArgumentCaptor<ContractSecurity> created = ArgumentCaptor.forClass(ContractSecurity.class);
        verify(securities).create(created.capture());
        assertThat(created.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(created.getValue().contractId()).isEqualTo(CONTRACT_ID);
        assertThat(created.getValue().status()).isEqualTo("ACTIVE");
        assertThat(created.getValue().requiredAmount()).isEqualByComparingTo("5000000.0000");
        assertThat(created.getValue().heldAmount()).isEqualByComparingTo("4000000.0000");
        assertThat(created.getValue().approvalRef()).isEqualTo("APR-2026-0007");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.security.recorded");
        assertThat(event.getValue().aggregateType()).isEqualTo("Security");
        assertThat(event.getValue().aggregateId()).isEqualTo(created.getValue().id());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(1L);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"securityId\":\"" + created.getValue().id() + "\"")
                .contains("\"securityType\":\"LETTER_OF_CREDIT\"")
                .contains("\"requiredAmount\":\"5000000.0000\"")
                .contains("\"heldAmount\":\"4000000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"status\":\"ACTIVE\"")
                .doesNotContain("\"contractNumber\"");
    }

    @Test
    void recordsAnExpiredSecurityWhenTheExpiryDateIsAlreadyPast() throws Exception {
        contractInStatus("BOUND");
        when(securities.create(any(ContractSecurity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"BANK_GUARANTEE\"", "\"1000000\"", null,
                                "\"USD\"", """
                                        ,"expiryDate": "2020-01-01"
                                        """)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.heldAmount").value("0.0000"));

        ArgumentCaptor<ContractSecurity> created = ArgumentCaptor.forClass(ContractSecurity.class);
        verify(securities).create(created.capture());
        assertThat(created.getValue().status()).isEqualTo("EXPIRED");
        // The legacy table's expiry check requires expiry >= effective_from.
        assertThat(created.getValue().effectiveFrom()).isEqualTo(PAST);
    }

    @Test
    void rejectsMissingSecurityType() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("null", "\"5000000\"", "\"0\"", "\"USD\"", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_TYPE"));

        verify(securities, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsUnknownSecurityType() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"PROMISE\"", "\"5000000\"", "\"0\"", "\"USD\"", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SECURITY_TYPE"));
    }

    @Test
    void rejectsNonPositiveRequiredAmount() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"0\"", "\"0\"", "\"USD\"", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUIRED_AMOUNT"));

        verify(securities, never()).create(any());
    }

    @Test
    void rejectsNegativeHeldAmount() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"5000000\"", "\"-1\"",
                                "\"USD\"", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_HELD_AMOUNT"));

        verify(securities, never()).create(any());
    }

    @Test
    void rejectsMissingCurrency() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"5000000\"", "\"0\"",
                                "null", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENCY"));

        verify(securities, never()).create(any());
    }

    @Test
    void rejectsApprovalReferenceLongerThanTheColumn() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"5000000\"", "\"0\"",
                                "\"USD\"", """
                                        ,"approvalRef": "%s"
                                        """.formatted("A".repeat(65)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPROVAL_REF"));
    }

    @Test
    void tenantWallReturnsNotFoundForAnotherTenant() throws Exception {
        contractInStatus("BOUND");

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"5000000\"", "\"0\"",
                                "\"USD\"", "")))
                .andExpect(status().isNotFound());

        verify(securities, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsTenantMismatchBetweenHeaderAndQuery() throws Exception {
        mvc.perform(get(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
    }

    @Test
    void requiresATenant() throws Exception {
        mvc.perform(get(PATH, CONTRACT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void rejectsSecurityWhenContractIsLocked() throws Exception {
        contractInStatus("IN_FORCE");

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"LETTER_OF_CREDIT\"", "\"5000000\"", "\"0\"",
                                "\"USD\"", "")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTRACT_LOCKED"));

        verify(securities, never()).create(any());
    }
}
