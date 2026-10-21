package com.eia.contract.expert.security;

import com.eia.contract.expert.ContractConflictException;
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
import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractSecurityController.class)
class ContractSecurityControllerTest {

    private static final String PATH = "/api/v1/contracts/{contractId}/security";
    private static final String STATUS_PATH = PATH + "/{securityId}/status";
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

    /** Same row with a new lifecycle status, so the id and payload identity stay stable. */
    private static ContractSecurity withStatus(ContractSecurity security, String status) {
        return new ContractSecurity(security.id(), security.tenantId(), security.contractId(),
                security.securityType(), security.requiredAmount(), security.heldAmount(),
                security.currency(), security.providerPartyId(), security.beneficiaryPartyId(),
                security.effectiveFrom(), security.effectiveTo(), security.expiryDate(), status,
                security.collateralQuality(), security.documentId(), security.releaseConditions(),
                security.approvalRef(), security.approvedAt(), security.createdAt(),
                Instant.now());
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

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, UUID.randomUUID())
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RELEASED\"}"))
                .andExpect(status().isNotFound());

        verify(securities, never()).create(any());
        verify(securities, never()).findById(any(), any(), any());
        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
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

    @Test
    void releasesAnActiveSecurityAndWritesTheStatusChangedEvent() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity active = security("5000000", "4000000", "ACTIVE", REINSURER, FUTURE,
                "APR-2026-0007");
        when(securities.findById(TENANT, CONTRACT_ID, active.id()))
                .thenReturn(Optional.of(active));
        when(securities.updateStatus(any(), any(), any(), any(), any(), any()))
                .thenReturn(withStatus(active, "RELEASED"));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, active.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"released\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(active.id().toString()))
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.createdAt").exists());

        verify(securities).updateStatus(eq(TENANT), eq(CONTRACT_ID), eq(active.id()),
                eq(SecurityRules.SecurityStatus.ACTIVE),
                eq(SecurityRules.SecurityStatus.RELEASED), any(Instant.class));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.security.status-changed");
        assertThat(event.getValue().aggregateType()).isEqualTo("Security");
        assertThat(event.getValue().aggregateId()).isEqualTo(active.id());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"securityId\":\"" + active.id() + "\"")
                .contains("\"securityType\":\"LETTER_OF_CREDIT\"")
                .contains("\"previousStatus\":\"ACTIVE\"")
                .contains("\"status\":\"RELEASED\"")
                .doesNotContain("\"requiredAmount\"");
    }

    @Test
    void expiresAnOutstandingSecurityAndWritesTheStatusChangedEvent() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity outstanding = security("5000000", "4000000", "OUTSTANDING", null,
                FUTURE, null);
        when(securities.findById(TENANT, CONTRACT_ID, outstanding.id()))
                .thenReturn(Optional.of(outstanding));
        when(securities.updateStatus(any(), any(), any(), any(), any(), any()))
                .thenReturn(withStatus(outstanding, "EXPIRED"));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, outstanding.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"EXPIRED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        verify(securities).updateStatus(eq(TENANT), eq(CONTRACT_ID), eq(outstanding.id()),
                eq(SecurityRules.SecurityStatus.OUTSTANDING),
                eq(SecurityRules.SecurityStatus.EXPIRED), any(Instant.class));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.security.status-changed");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload())
                .contains("\"previousStatus\":\"OUTSTANDING\"")
                .contains("\"status\":\"EXPIRED\"");
    }

    @Test
    void releasesAnExpiredSecurityAtTheLaterAggregateVersion() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity expired = security("1000000", "0", "EXPIRED", null, PAST, null);
        when(securities.findById(TENANT, CONTRACT_ID, expired.id()))
                .thenReturn(Optional.of(expired));
        when(securities.updateStatus(any(), any(), any(), any(), any(), any()))
                .thenReturn(withStatus(expired, "RELEASED"));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, expired.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RELEASED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.security.status-changed");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(3L);
        assertThat(event.getValue().payload())
                .contains("\"previousStatus\":\"EXPIRED\"")
                .contains("\"status\":\"RELEASED\"");
    }

    @Test
    void releasedIsTerminal() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity released = security("5000000", "4000000", "RELEASED", null, FUTURE, null);
        when(securities.findById(TENANT, CONTRACT_ID, released.id()))
                .thenReturn(Optional.of(released));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, released.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"EXPIRED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, released.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RELEASED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsExpiringAnAlreadyExpiredSecurity() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity expired = security("1000000", "0", "EXPIRED", null, PAST, null);
        when(securities.findById(TENANT, CONTRACT_ID, expired.id()))
                .thenReturn(Optional.of(expired));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, expired.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"EXPIRED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsReleasingABreachedSecurity() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity breach = security("5000000", "0", "BREACH", null, FUTURE, null);
        when(securities.findById(TENANT, CONTRACT_ID, breach.id()))
                .thenReturn(Optional.of(breach));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, breach.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RELEASED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsATargetOutsideTheSecurityLifecycle() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity active = security("5000000", "4000000", "ACTIVE", null, FUTURE, null);
        when(securities.findById(TENANT, CONTRACT_ID, active.id()))
                .thenReturn(Optional.of(active));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, active.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsAMissingTargetStatus() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity active = security("5000000", "4000000", "ACTIVE", null, FUTURE, null);
        when(securities.findById(TENANT, CONTRACT_ID, active.id()))
                .thenReturn(Optional.of(active));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, active.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void returnsNotFoundForAnUnknownSecurity() throws Exception {
        contractInStatus("BOUND");
        UUID securityId = UUID.randomUUID();
        when(securities.findById(TENANT, CONTRACT_ID, securityId)).thenReturn(Optional.empty());

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, securityId)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RELEASED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(securities, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void surfacesAConcurrentStatusChangeAsConflict() throws Exception {
        contractInStatus("BOUND");
        ContractSecurity active = security("5000000", "4000000", "ACTIVE", null, FUTURE, null);
        when(securities.findById(TENANT, CONTRACT_ID, active.id()))
                .thenReturn(Optional.of(active));
        when(securities.updateStatus(any(), any(), any(), any(), any(), any()))
                .thenThrow(new ContractConflictException("INVALID_STATUS_TRANSITION",
                        "Security status cannot move from EXPIRED to RELEASED"));

        mvc.perform(patch(STATUS_PATH, CONTRACT_ID, active.id())
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RELEASED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(outbox, never()).insert(any());
    }
}
