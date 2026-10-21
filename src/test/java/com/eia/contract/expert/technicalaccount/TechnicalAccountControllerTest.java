package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.BusinessKeys;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.ACCOUNT_ID;
import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
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

@WebMvcTest(TechnicalAccountController.class)
class TechnicalAccountControllerTest {

    private static final String PATH = "/api/v1/contracts/{contractId}/technical-accounts";
    private static final String INTERNAL_PATH = "/internal/v1/contracts/{contractId}/technical-accounts";
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");

    private static final String CREATE_BODY = """
            {
              "periodStart": "2026-01-01",
              "periodEnd": "2026-03-31",
              "currency": "USD",
              "cededPremium": "1000",
              "cededLosses": "400",
              "commission": "100",
              "otherCharges": "50"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private TechnicalAccountRepository accounts;

    @MockitoBean
    private TechnicalAccountLineRepository lines;

    @MockitoBean
    private BusinessKeys keys;

    @MockitoBean
    private OutboxRepository outbox;

    private void contractExists() {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("BOUND", 2)));
    }

    private void contractExistsForUpdate() {
        when(contracts.findByIdForUpdate(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("BOUND", 2)));
    }

    @Test
    void createsDraftAccountWithDerivedBalanceAndCreatedEvent() throws Exception {
        contractExistsForUpdate();
        when(keys.nextPortable(TENANT, "TA", 2026)).thenReturn("TA-2026-00042");
        when(accounts.create(any(ContractTechnicalAccount.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value("TA-2026-00042"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-03-31"))
                .andExpect(jsonPath("$.cededPremium").value("1000.0000"))
                .andExpect(jsonPath("$.cededLosses").value("400.0000"))
                .andExpect(jsonPath("$.commission").value("100.0000"))
                .andExpect(jsonPath("$.otherCharges").value("50.0000"))
                .andExpect(jsonPath("$.balance").value("550.0000"));

        ArgumentCaptor<ContractTechnicalAccount> account = ArgumentCaptor.forClass(
                ContractTechnicalAccount.class);
        verify(accounts).create(account.capture());
        assertThat(account.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(account.getValue().contractId()).isEqualTo(CONTRACT_ID);
        assertThat(account.getValue().balance()).isEqualByComparingTo("550.0000");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.technical-account.created");
        assertThat(event.getValue().aggregateType()).isEqualTo("TechnicalAccount");
        assertThat(event.getValue().aggregateId()).isEqualTo(account.getValue().id());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(1L);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"contractNumber\":\"XOL-2026-00091\"")
                .contains("\"accountId\":\"" + account.getValue().id() + "\"")
                .contains("\"accountNumber\":\"TA-2026-00042\"")
                .contains("\"periodStart\":\"2026-01-01\"")
                .contains("\"periodEnd\":\"2026-03-31\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"balance\":\"550.0000\"")
                .contains("\"status\":\"DRAFT\"");
    }

    @Test
    void listsAccountsForTheContract() throws Exception {
        contractExists();
        when(accounts.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.technicalAccount("DRAFT")));

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountNumber").value("TA-2026-00001"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].balance").value("550.0000"));
    }

    @Test
    void acceptsTheInternalPrefixAndQueryTenant() throws Exception {
        contractExists();
        when(accounts.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.technicalAccount("DRAFT")));

        mvc.perform(get(INTERNAL_PATH, CONTRACT_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountNumber").value("TA-2026-00001"));
    }

    @Test
    void rejectsDuplicatePeriodWith409() throws Exception {
        contractExistsForUpdate();
        when(keys.nextPortable(any(), eq("TA"), eq(2026))).thenReturn("TA-2026-00042");
        when(accounts.create(any(ContractTechnicalAccount.class)))
                .thenThrow(new ContractConflictException("DUPLICATE_TECHNICAL_ACCOUNT_PERIOD",
                        "A technical account already exists for that period",
                        "periodStart", "Periods are unique per tenant and contract"));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TECHNICAL_ACCOUNT_PERIOD"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsPeriodThatDoesNotEndAfterItStarts() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("2026-03-31", "2026-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));

        verify(accounts, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsNegativeAmounts() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"400\"", "\"-0.01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CEDED_LOSSES"));

        verify(accounts, never()).create(any());
    }

    @Test
    void rejectsCurrencyThatIsNotThreeLetters() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"USD\"", "\"US\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENCY"));

        verify(accounts, never()).create(any());
    }

    @Test
    void rejectsMissingAmount() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"commission\": \"100\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COMMISSION"));

        verify(accounts, never()).create(any());
    }

    @Test
    void issuesDraftAccountAndWritesIssuedEvent() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("DRAFT")));
        when(accounts.updateStatus(TENANT, CONTRACT_ID, ACCOUNT_ID,
                TechnicalAccountStatus.DRAFT, TechnicalAccountStatus.ISSUED))
                .thenReturn(Fixtures.technicalAccount("ISSUED"));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.technical-account.issued");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload())
                .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
                .contains("\"accountNumber\":\"TA-2026-00001\"")
                .contains("\"balance\":\"550.0000\"")
                .contains("\"status\":\"ISSUED\"");
    }

    @Test
    void issuesDraftAccountWhenLineTotalsReconcile() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("DRAFT")));
        when(lines.totals(TENANT, ACCOUNT_ID)).thenReturn(List.of(
                new TechnicalAccountLineRepository.LineTotal(
                        "PREMIUM", new BigDecimal("1000.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "LOSS", new BigDecimal("400.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "COMMISSION", new BigDecimal("100.0000")),
                new TechnicalAccountLineRepository.LineTotal(
                        "OTHER", new BigDecimal("50.0000"))));
        when(accounts.updateStatus(TENANT, CONTRACT_ID, ACCOUNT_ID,
                TechnicalAccountStatus.DRAFT, TechnicalAccountStatus.ISSUED))
                .thenReturn(Fixtures.technicalAccount("ISSUED"));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        verify(outbox).insert(any(OutboxEvent.class));
    }

    @Test
    void rejectsIssueWhenLineTotalsDoNotReconcileWithPerTypeDetail() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("DRAFT")));
        when(lines.totals(TENANT, ACCOUNT_ID)).thenReturn(List.of(
                new TechnicalAccountLineRepository.LineTotal(
                        "PREMIUM", new BigDecimal("900.0000"))));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LINES_DO_NOT_RECONCILE"))
                .andExpect(jsonPath("$.details.length()").value(4))
                .andExpect(jsonPath("$.details[0].field").value("PREMIUM"))
                .andExpect(jsonPath("$.details[0].message")
                        .value("PREMIUM lines total 900.0000 but cededPremium is 1000.0000"))
                .andExpect(jsonPath("$.details[1].field").value("LOSS"))
                .andExpect(jsonPath("$.details[2].field").value("COMMISSION"))
                .andExpect(jsonPath("$.details[3].field").value("OTHER"));

        verify(accounts, never()).updateStatus(any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void settlesIssuedAccountAndWritesSettledEvent() throws Exception {        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("ISSUED")));
        when(accounts.updateStatus(TENANT, CONTRACT_ID, ACCOUNT_ID,
                TechnicalAccountStatus.ISSUED, TechnicalAccountStatus.SETTLED))
                .thenReturn(Fixtures.technicalAccount("SETTLED"));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"settled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.technical-account.settled");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(3L);
        assertThat(event.getValue().payload()).contains("\"status\":\"SETTLED\"");
    }

    @Test
    void rejectsSkippingIssued() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("DRAFT")));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(accounts, never()).updateStatus(any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsIssuingAnAlreadyIssuedAccount() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("ISSUED")));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsDraftAsAStatusRequest() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("DRAFT")));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"DRAFT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        verify(accounts, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    void returnsNotFoundForAnAccountOfAnotherContract() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void tenantWallHidesTheContractAndItsAccounts() throws Exception {
        when(contracts.findById(OTHER_TENANT, CONTRACT_ID)).thenReturn(Optional.empty());
        when(contracts.findByIdForUpdate(OTHER_TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(accounts, never()).findAll(any(), any());
        verify(accounts, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void requiresATenant() throws Exception {
        mvc.perform(get(PATH, CONTRACT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void rejectsConflictingTenantHeaderAndQueryParameter() throws Exception {
        mvc.perform(get(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
    }

    @Test
    void returnsNotFoundWhenTheContractDoesNotExist() throws Exception {
        when(contracts.findById(TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void reportsAnAccountMissingFromTheRepositoryAsNotFound() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void surfacesAConcurrentStatusChangeAsConflict() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount("DRAFT")));
        when(accounts.updateStatus(TENANT, CONTRACT_ID, ACCOUNT_ID,
                TechnicalAccountStatus.DRAFT, TechnicalAccountStatus.ISSUED))
                .thenThrow(new ContractConflictException("INVALID_STATUS_TRANSITION",
                        "Technical-account status cannot move from ISSUED to ISSUED"));

        mvc.perform(patch(PATH + "/{accountId}/status", CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
    }
}
