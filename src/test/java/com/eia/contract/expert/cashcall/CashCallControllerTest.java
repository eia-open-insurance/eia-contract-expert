package com.eia.contract.expert.cashcall;

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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.CASH_CALL_ID;
import static com.eia.contract.expert.Fixtures.CONTRACT_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CashCallController.class)
class CashCallControllerTest {

    private static final String PATH = "/api/v1/contracts/{contractId}/cash-calls";
    private static final String INTERNAL_PATH = "/internal/v1/contracts/{contractId}/cash-calls";
    private static final UUID OTHER_TENANT = UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final String SETTLEMENT_REFERENCE = "BANK-REF-2026-0001";

    private static final String CREATE_BODY = """
            {
              "periodStart": "2026-01-01",
              "periodEnd": "2026-03-31",
              "amount": "250000",
              "currency": "USD",
              "dueDate": "2026-04-15",
              "notes": "  Q1 cash call  "
            }
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ContractRepository contracts;

    @MockitoBean
    private CashCallRepository cashCalls;

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
    void createsRequestedCashCallWithGeneratedNumberAndEvent() throws Exception {
        contractExistsForUpdate();
        when(keys.nextPortable(TENANT, "CC", 2026)).thenReturn("CC-2026-00042");
        when(cashCalls.create(any(ContractCashCall.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.callNumber").value("CC-2026-00042"))
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andExpect(jsonPath("$.periodStart").value("2026-01-01"))
                .andExpect(jsonPath("$.periodEnd").value("2026-03-31"))
                .andExpect(jsonPath("$.amount").value("250000.0000"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.dueDate").value("2026-04-15"))
                .andExpect(jsonPath("$.notes").value("Q1 cash call"))
                .andExpect(jsonPath("$.settlementReference").value(nullValue()));

        ArgumentCaptor<ContractCashCall> cashCall = ArgumentCaptor.forClass(
                ContractCashCall.class);
        verify(cashCalls).create(cashCall.capture());
        assertThat(cashCall.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(cashCall.getValue().contractId()).isEqualTo(CONTRACT_ID);
        assertThat(cashCall.getValue().callNumber()).isEqualTo("CC-2026-00042");
        assertThat(cashCall.getValue().amount()).isEqualByComparingTo("250000.0000");
        assertThat(cashCall.getValue().notes()).isEqualTo("Q1 cash call");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.cash-call.requested");
        assertThat(event.getValue().aggregateType()).isEqualTo("CashCall");
        assertThat(event.getValue().aggregateId()).isEqualTo(cashCall.getValue().id());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(1L);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"contractNumber\":\"XOL-2026-00091\"")
                .contains("\"cashCallId\":\"" + cashCall.getValue().id() + "\"")
                .contains("\"callNumber\":\"CC-2026-00042\"")
                .contains("\"period\":{\"end\":\"2026-03-31\",\"start\":\"2026-01-01\"}")
                .contains("\"periodStart\":\"2026-01-01\"")
                .contains("\"periodEnd\":\"2026-03-31\"")
                .contains("\"amount\":\"250000.0000\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"dueDate\":\"2026-04-15\"")
                .contains("\"status\":\"REQUESTED\"");
    }

    @Test
    void listsCashCallsForTheContract() throws Exception {
        contractExists();
        when(cashCalls.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.cashCall("REQUESTED")));

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callNumber").value("CC-2026-00001"))
                .andExpect(jsonPath("$[0].status").value("REQUESTED"))
                .andExpect(jsonPath("$[0].amount").value("250000.0000"))
                .andExpect(jsonPath("$[0].dueDate").value("2026-04-15"));
    }

    @Test
    void returnsTheSettlementReferenceInTheCashCallDto() throws Exception {
        contractExists();
        when(cashCalls.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.cashCall("SETTLED", SETTLEMENT_REFERENCE)));

        mvc.perform(get(PATH, CONTRACT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SETTLED"))
                .andExpect(jsonPath("$[0].settlementReference").value(SETTLEMENT_REFERENCE));
    }

    @Test
    void acceptsTheInternalPrefixAndQueryTenant() throws Exception {
        contractExists();
        when(cashCalls.findAll(TENANT, CONTRACT_ID))
                .thenReturn(List.of(Fixtures.cashCall("REQUESTED")));

        mvc.perform(get(INTERNAL_PATH, CONTRACT_ID).param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].callNumber").value("CC-2026-00001"));
    }

    @Test
    void rejectsAPeriodThatDoesNotEndAfterItStarts() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("2026-03-31", "2026-01-01")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));

        verify(cashCalls, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsAZeroAmount() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"250000\"", "\"0\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        verify(cashCalls, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsANegativeAmount() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"250000\"", "\"-0.01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        verify(cashCalls, never()).create(any());
    }

    @Test
    void rejectsAMissingAmount() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"amount\": \"250000\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        verify(cashCalls, never()).create(any());
    }

    @Test
    void rejectsAMissingDueDate() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"dueDate\": \"2026-04-15\",", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DUE_DATE"));

        verify(cashCalls, never()).create(any());
    }

    @Test
    void rejectsACurrencyThatIsNotThreeUppercaseLetters() throws Exception {
        contractExistsForUpdate();

        mvc.perform(post(PATH, CONTRACT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"USD\"", "\"US\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENCY"));

        verify(cashCalls, never()).create(any());
    }

    @Test
    void rejectsAnUnknownStatus() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"APPROVED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsRequestedAsAStatusRequest() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"REQUESTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void issuesARequestedCashCallAndWritesTheIssuedEvent() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.ISSUED, null))
                .thenReturn(Fixtures.cashCall("ISSUED"));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"issued\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.cash-call.issued");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload())
                .contains("\"cashCallId\":\"" + CASH_CALL_ID + "\"")
                .contains("\"callNumber\":\"CC-2026-00001\"")
                .contains("\"status\":\"ISSUED\"")
                .contains("\"amount\":\"250000.0000\"");
    }

    @Test
    void settlesAnIssuedCashCallWithATrimmedReferenceAndWritesTheEvent() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("ISSUED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.ISSUED, CashCallStatus.SETTLED, SETTLEMENT_REFERENCE))
                .thenReturn(Fixtures.cashCall("SETTLED", SETTLEMENT_REFERENCE));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\", \"reference\": \"  "
                                + SETTLEMENT_REFERENCE + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settlementReference").value(SETTLEMENT_REFERENCE));

        verify(cashCalls).updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.ISSUED, CashCallStatus.SETTLED, SETTLEMENT_REFERENCE);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.cash-call.settled");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(3L);
        assertThat(event.getValue().payload())
                .contains("\"status\":\"SETTLED\"")
                .contains("\"settlementReference\":\"" + SETTLEMENT_REFERENCE + "\"");
    }

    @Test
    void settlesWithAReferenceAtTheMaximumLength() throws Exception {
        String reference = "R".repeat(CashCallRules.SETTLEMENT_REFERENCE_MAX_LENGTH);
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("ISSUED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.ISSUED, CashCallStatus.SETTLED, reference))
                .thenReturn(Fixtures.cashCall("SETTLED", reference));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\", \"reference\": \"" + reference + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementReference").value(reference));

        verify(cashCalls).updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.ISSUED, CashCallStatus.SETTLED, reference);
    }

    @Test
    void rejectsSettlingWithoutAReference() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("ISSUED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"))
                .andExpect(jsonPath("$.details[0].field").value("reference"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsSettlingWithABlankReference() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("ISSUED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\", \"reference\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsASettlementReferenceLongerThanTheMaximum() throws Exception {
        String reference = "R".repeat(CashCallRules.SETTLEMENT_REFERENCE_MAX_LENGTH + 1);
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("ISSUED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\", \"reference\": \"" + reference + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void cancelsARequestedCashCallAndWritesTheCancelledEvent() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.CANCELLED, null))
                .thenReturn(Fixtures.cashCall("CANCELLED"));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.cash-call.cancelled");
        assertThat(event.getValue().aggregateVersion()).isEqualTo(2L);
        assertThat(event.getValue().payload()).contains("\"status\":\"CANCELLED\"");
    }

    @Test
    void ignoresTheReferenceWhenIssuing() throws Exception {
        String ignoredReference = "IGNORED".repeat(30);
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.ISSUED, null))
                .thenReturn(Fixtures.cashCall("ISSUED"));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\", \"reference\": \""
                                + ignoredReference + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.settlementReference").value(nullValue()));

        verify(cashCalls).updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.ISSUED, null);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.cash-call.issued");
        assertThat(event.getValue().payload())
                .contains("\"status\":\"ISSUED\"")
                .doesNotContain("settlementReference");
    }

    @Test
    void ignoresTheReferenceWhenCancelling() throws Exception {
        String ignoredReference = "IGNORED".repeat(30);
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.CANCELLED, null))
                .thenReturn(Fixtures.cashCall("CANCELLED"));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\", \"reference\": \""
                                + ignoredReference + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.settlementReference").value(nullValue()));

        verify(cashCalls).updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.CANCELLED, null);

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("contract.cash-call.cancelled");
        assertThat(event.getValue().payload())
                .contains("\"status\":\"CANCELLED\"")
                .doesNotContain("settlementReference");
    }

    @Test
    void rejectsSettlingARequestedCashCall() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsCancellingAnIssuedCashCall() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("ISSUED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsAnyTransitionFromSettled() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("SETTLED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsAnyTransitionFromCancelled() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("CANCELLED")));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"SETTLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void returnsNotFoundForACashCallOfAnotherContract() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID)).thenReturn(Optional.empty());

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(outbox, never()).insert(any());
    }

    @Test
    void tenantWallHidesTheContractAndItsCashCalls() throws Exception {
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

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(cashCalls, never()).findAll(any(), any());
        verify(cashCalls, never()).create(any());
        verify(cashCalls, never()).updateStatus(any(), any(), any(), any(), any(), any());
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
    void surfacesAConcurrentStatusChangeAsConflict() throws Exception {
        contractExists();
        when(cashCalls.findById(TENANT, CONTRACT_ID, CASH_CALL_ID))
                .thenReturn(Optional.of(Fixtures.cashCall("REQUESTED")));
        when(cashCalls.updateStatus(TENANT, CONTRACT_ID, CASH_CALL_ID,
                CashCallStatus.REQUESTED, CashCallStatus.ISSUED, null))
                .thenThrow(new ContractConflictException("INVALID_STATUS_TRANSITION",
                        "Cash-call status cannot move from ISSUED to ISSUED"));

        mvc.perform(patch(PATH + "/{cashCallId}/status", CONTRACT_ID, CASH_CALL_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ISSUED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        verify(outbox, never()).insert(any());
    }
}
