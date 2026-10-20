package com.eia.contract.expert.bordereau;

import com.eia.contract.expert.BusinessKeys;
import com.eia.contract.expert.Contract;
import com.eia.contract.expert.ContractConflictException;
import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.contract.expert.technicalaccount.ContractTechnicalAccount;
import com.eia.contract.expert.technicalaccount.CreateTechnicalAccountRequest;
import com.eia.contract.expert.technicalaccount.TechnicalAccountController;
import com.eia.contract.expert.technicalaccount.TechnicalAccountRepository;
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
import static com.eia.contract.expert.Fixtures.BORDEREAU_ID;
import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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

@WebMvcTest(BordereauController.class)
class BordereauControllerTest {

    private static final String PATH = "/api/v1/contracts/{contractId}/bordereaux";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");

    private static final String CREATE_BODY = """
            {
              "periodStart": "2026-01-01",
              "periodEnd": "2026-03-31",
              "receivedDate": "2026-04-15",
              "currency": "USD",
              "premiumAmount": "1000",
              "lossAmount": "400",
              "commissionAmount": "100",
              "otherChargesAmount": "50",
              "lineCount": 10
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private BordereauRepository bordereaux;

    @MockitoBean
    private TechnicalAccountRepository accounts;

    @MockitoBean
    private TechnicalAccountController technicalAccounts;

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
    void createsReceivedBordereauWithGeneratedNumberAndEvent() throws Exception {
        contractExistsForUpdate();
        when(keys.nextPortable(TENANT, "BRD", 2026)).thenReturn("BRD-2026-00042");
        when(bordereaux.create(any(ContractBordereau.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bordereauNumber").value("BRD-2026-00042"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-03-31"))
                .andExpect(jsonPath("$.receivedDate").value("2026-04-15"))
                .andExpect(jsonPath("$.premiumAmount").value("1000.0000"))
                .andExpect(jsonPath("$.lossAmount").value("400.0000"))
                .andExpect(jsonPath("$.commissionAmount").value("100.0000"))
                .andExpect(jsonPath("$.otherChargesAmount").value("50.0000"))
                .andExpect(jsonPath("$.lineCount").value(10))
                .andExpect(jsonPath("$.technicalAccountId").value(nullValue()));

        ArgumentCaptor<ContractBordereau> bordereau =
                ArgumentCaptor.forClass(ContractBordereau.class);
        verify(bordereaux).create(bordereau.capture());
        assertThat(bordereau.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(bordereau.getValue().contractId()).isEqualTo(CONTRACT_ID);
        assertThat(bordereau.getValue().premiumAmount()).isEqualByComparingTo("1000.0000");
        assertThat(bordereau.getValue().lossAmount()).isEqualByComparingTo("400.0000");
        assertThat(bordereau.getValue().notes()).isNull();

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.bordereau.received");
        assertThat(event.getValue().aggregateType()).isEqualTo("Bordereau");
        assertThat(event.getValue().aggregateId()).isEqualTo(bordereau.getValue().id());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(1L);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"contractNumber\":\"XOL-2026-00091\"")
                .contains("\"bordereauId\":\"" + bordereau.getValue().id() + "\"")
                .contains("\"bordereauNumber\":\"BRD-2026-00042\"")
                .contains("\"periodStart\":\"2026-01-01\"")
                .contains("\"periodEnd\":\"2026-03-31\"")
                .contains("\"period\":{")
                .contains("\"currency\":\"USD\"")
                .contains("\"status\":\"RECEIVED\"");
    }

    @Test
    void listsBordereauxNewestFirst() throws Exception {
        contractExists();
        when(bordereaux.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.bordereau("PROCESSED", ACCOUNT_ID),
                        Fixtures.bordereau("RECEIVED")));

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PROCESSED"))
                .andExpect(jsonPath("$[0].technicalAccountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$[1].status").value("RECEIVED"))
                .andExpect(jsonPath("$[1].premiumAmount").value("1000.0000"));
    }

    @Test
    void acceptsTheInternalQueryTenant() throws Exception {
        contractExists();
        when(bordereaux.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.bordereau("RECEIVED")));

        mvc.perform(get(PATH, CONTRACT_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bordereauNumber").value("BRD-2026-00001"));
    }

    @Test
    void rejectsDuplicatePeriodWith409() throws Exception {
        contractExistsForUpdate();
        when(keys.nextPortable(any(), eq("BRD"), eq(2026))).thenReturn("BRD-2026-00042");
        when(bordereaux.create(any(ContractBordereau.class)))
                .thenThrow(new ContractConflictException("DUPLICATE_BORDEREAU_PERIOD",
                        "A bordereau already exists for that period",
                        "periodStart", "Periods are unique per tenant and contract"));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BORDEREAU_PERIOD"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsInvertedPeriod() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"periodEnd\": \"2026-03-31\"",
                                "\"periodEnd\": \"2026-01-01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));

        verify(bordereaux, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsNegativeAmount() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"lossAmount\": \"400\"",
                                "\"lossAmount\": \"-0.01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOSS_AMOUNT"));

        verify(bordereaux, never()).create(any());
    }

    @Test
    void rejectsNegativeLineCount() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"lineCount\": 10", "\"lineCount\": -1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINE_COUNT"));

        verify(bordereaux, never()).create(any());
    }

    @Test
    void rejectsCurrencyThatIsNotThreeUpperCaseLetters() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"USD\"", "\"US\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENCY"));

        verify(bordereaux, never()).create(any());
    }

    @Test
    void rejectsMissingReceivedDate() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"receivedDate\": \"2026-04-15\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECEIVED_DATE"));

        verify(bordereaux, never()).create(any());
    }

    @Test
    void processesReceivedBordereauAndCreatesTechnicalAccount() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID))
                .thenReturn(Optional.of(Fixtures.bordereau("RECEIVED")));
        when(accounts.findForPeriod(TENANT, CONTRACT_ID,
                Fixtures.FROM, java.time.LocalDate.of(2026, 3, 31)))
                .thenReturn(Optional.empty());
        when(technicalAccounts.createForContract(any(Contract.class),
                any(CreateTechnicalAccountRequest.class)))
                .thenReturn(Fixtures.technicalAccount("DRAFT"));
        when(bordereaux.updateStatus(TENANT, CONTRACT_ID, BORDEREAU_ID,
                BordereauStatus.RECEIVED, BordereauStatus.PROCESSED, null, ACCOUNT_ID))
                .thenReturn(Fixtures.bordereau("PROCESSED", ACCOUNT_ID));

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PROCESSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.technicalAccountId").value(ACCOUNT_ID.toString()));

        ArgumentCaptor<CreateTechnicalAccountRequest> request =
                ArgumentCaptor.forClass(CreateTechnicalAccountRequest.class);
        verify(technicalAccounts).createForContract(any(Contract.class), request.capture());
        assertThat(request.getValue().periodStart()).isEqualTo(Fixtures.FROM);
        assertThat(request.getValue().periodEnd()).isEqualTo(java.time.LocalDate.of(2026, 3, 31));
        assertThat(request.getValue().currency()).isEqualTo("USD");
        assertThat(request.getValue().cededPremium()).isEqualByComparingTo("1000.0000");
        assertThat(request.getValue().cededLosses()).isEqualByComparingTo("400.0000");
        assertThat(request.getValue().commission()).isEqualByComparingTo("100.0000");
        assertThat(request.getValue().otherCharges()).isEqualByComparingTo("50.0000");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.bordereau.processed");
        assertThat(event.getValue().aggregateType()).isEqualTo("Bordereau");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload())
                .contains("\"bordereauId\":\"" + BORDEREAU_ID + "\"")
                .contains("\"technicalAccountId\":\"" + ACCOUNT_ID + "\"")
                .contains("\"status\":\"PROCESSED\"");
    }

    @Test
    void processesReceivedBordereauWithExistingTechnicalAccount() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID))
                .thenReturn(Optional.of(Fixtures.bordereau("RECEIVED")));
        when(accounts.findForPeriod(TENANT, CONTRACT_ID,
                Fixtures.FROM, java.time.LocalDate.of(2026, 3, 31)))
                .thenReturn(Optional.of(Fixtures.technicalAccount("ISSUED")));
        when(bordereaux.updateStatus(TENANT, CONTRACT_ID, BORDEREAU_ID,
                BordereauStatus.RECEIVED, BordereauStatus.PROCESSED, null, ACCOUNT_ID))
                .thenReturn(Fixtures.bordereau("PROCESSED", ACCOUNT_ID));

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PROCESSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.technicalAccountId").value(ACCOUNT_ID.toString()));

        verify(technicalAccounts, never()).createForContract(any(), any());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.bordereau.processed");
        assertThat(event.getValue().payload())
                .contains("\"technicalAccountId\":\"" + ACCOUNT_ID + "\"");
    }

    @Test
    void failsReceivedBordereauAndWritesFailedEvent() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID))
                .thenReturn(Optional.of(Fixtures.bordereau("RECEIVED")));
        when(bordereaux.updateStatus(TENANT, CONTRACT_ID, BORDEREAU_ID,
                BordereauStatus.RECEIVED, BordereauStatus.FAILED, "missing lines", null))
                .thenReturn(Fixtures.bordereau("FAILED"));

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FAILED\", \"notes\": \"missing lines\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        verify(accounts, never()).findForPeriod(any(), any(), any(), any());
        verify(technicalAccounts, never()).createForContract(any(), any());
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.bordereau.failed");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload()).contains("\"status\":\"FAILED\"");
    }

    @Test
    void rejectsSecondTransitionOnTerminalBordereau() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID))
                .thenReturn(Optional.of(Fixtures.bordereau("PROCESSED", ACCOUNT_ID)));

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"FAILED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(accounts, never()).findForPeriod(any(), any(), any(), any());
        verify(bordereaux, never()).updateStatus(any(), any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsReceivedAsAStatusRequest() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID))
                .thenReturn(Optional.of(Fixtures.bordereau("RECEIVED")));

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RECEIVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        verify(bordereaux, never()).updateStatus(any(), any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void returnsNotFoundForAnUnknownBordereau() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID)).thenReturn(Optional.empty());

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PROCESSED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void surfacesAConcurrentStatusChangeAsConflict() throws Exception {
        contractExistsForUpdate();
        when(bordereaux.findById(TENANT, CONTRACT_ID, BORDEREAU_ID))
                .thenReturn(Optional.of(Fixtures.bordereau("RECEIVED")));
        when(accounts.findForPeriod(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(technicalAccounts.createForContract(any(Contract.class),
                any(CreateTechnicalAccountRequest.class)))
                .thenReturn(Fixtures.technicalAccount("DRAFT"));
        when(bordereaux.updateStatus(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ContractConflictException("INVALID_STATUS_TRANSITION",
                        "Bordereau status cannot move from PROCESSED to PROCESSED"));

        mvc.perform(patch(PATH + "/{bordereauId}/status", CONTRACT_ID, BORDEREAU_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PROCESSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void tenantWallHidesTheContractAndItsBordereaux() throws Exception {
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

        verify(bordereaux, never()).findAll(any(), any());
        verify(bordereaux, never()).create(any());
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
}
