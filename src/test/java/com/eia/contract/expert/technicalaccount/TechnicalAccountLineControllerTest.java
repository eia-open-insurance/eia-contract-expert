package com.eia.contract.expert.technicalaccount;

import com.eia.contract.expert.ContractRepository;
import com.eia.contract.expert.Fixtures;
import com.eia.platform.libs.outbox.OutboxEvent;
import com.eia.platform.libs.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import static com.eia.contract.expert.Fixtures.LINE_ID;
import static com.eia.contract.expert.Fixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TechnicalAccountLineController.class)
class TechnicalAccountLineControllerTest {

    private static final String PATH =
            "/api/v1/contracts/{contractId}/technical-accounts/{accountId}/lines";
    private static final String INTERNAL_PATH =
            "/internal/v1/contracts/{contractId}/technical-accounts/{accountId}/lines";
    private static final UUID OTHER_TENANT =
            UUID.fromString("99999999-9999-7999-8999-999999999999");
    private static final UUID OTHER_LINE_ID =
            UUID.fromString("aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaab");

    private static final String CREATE_BODY = """
            {
              "lineType": "PREMIUM",
              "reference": "BORD-2026-001",
              "description": "Q1 ceded premium",
              "amount": "1000.0000"
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
    private OutboxRepository outbox;

    private void contractExists() {
        when(contracts.findById(TENANT, CONTRACT_ID))
                .thenReturn(Optional.of(Fixtures.contract("BOUND", 2)));
    }

    private void accountExists(String status) {
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(Fixtures.technicalAccount(status)));
    }

    @Test
    void listsLinesAndDetailedAndBucketTotals() throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.findAll(TENANT, ACCOUNT_ID)).thenReturn(List.of(
                Fixtures.technicalAccountLine("PREMIUM", "1000.0000"),
                Fixtures.technicalAccountLine(OTHER_LINE_ID, "PREMIUM", "250.0000"),
                Fixtures.technicalAccountLine(UUID.randomUUID(), "PAID_LOSS", "400.0000"),
                Fixtures.technicalAccountLine(UUID.randomUUID(), "IBNR", "50.0000")));

        mvc.perform(get(PATH, CONTRACT_ID, ACCOUNT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[0].lineType").value("PREMIUM"))
                .andExpect(jsonPath("$.lines[0].reference").value("REF-2026-0001"))
                .andExpect(jsonPath("$.lines[0].amount").value("1000.0000"))
                .andExpect(jsonPath("$.lines[2].lineType").value("PAID_LOSS"))
                .andExpect(jsonPath("$.lines[3].lineType").value("IBNR"))
                .andExpect(jsonPath("$.totals.PREMIUM").value("1250.0000"))
                .andExpect(jsonPath("$.totals.PAID_LOSS").value("400.0000"))
                .andExpect(jsonPath("$.totals.IBNR").value("50.0000"))
                .andExpect(jsonPath("$.totals.OUTSTANDING_LOSS").value("0.0000"))
                .andExpect(jsonPath("$.totals.COMMISSION").value("0.0000"))
                .andExpect(jsonPath("$.totals.OTHER").value("0.0000"))
                .andExpect(jsonPath("$.bucketTotals.premium").value("1250.0000"))
                .andExpect(jsonPath("$.bucketTotals.loss").value("450.0000"))
                .andExpect(jsonPath("$.bucketTotals.commission").value("0.0000"))
                .andExpect(jsonPath("$.bucketTotals.other").value("0.0000"));
    }

    @Test
    void rollsEveryDetailedTypeIntoItsBucketTotal() throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.findAll(TENANT, ACCOUNT_ID)).thenReturn(List.of(
                Fixtures.technicalAccountLine("PREMIUM", "100.0000"),
                Fixtures.technicalAccountLine("COMMISSION", "20.0000"),
                Fixtures.technicalAccountLine("BROKERAGE", "5.0000"),
                Fixtures.technicalAccountLine("PAID_LOSS", "30.0000"),
                Fixtures.technicalAccountLine("OUTSTANDING_LOSS", "10.0000"),
                Fixtures.technicalAccountLine("IBNR", "7.0000"),
                Fixtures.technicalAccountLine("ALAE", "3.0000"),
                Fixtures.technicalAccountLine("REINSTATEMENT", "5.0000"),
                Fixtures.technicalAccountLine("CASH_CALL", "5.0000"),
                Fixtures.technicalAccountLine("FUNDS_WITHHELD", "4.0000"),
                Fixtures.technicalAccountLine("TAX", "3.0000"),
                Fixtures.technicalAccountLine("ADJUSTMENT", "2.0000"),
                Fixtures.technicalAccountLine("OTHER", "1.0000")));

        mvc.perform(get(PATH, CONTRACT_ID, ACCOUNT_ID).header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(13))
                .andExpect(jsonPath("$.totals.REINSTATEMENT").value("5.0000"))
                .andExpect(jsonPath("$.totals.CASH_CALL").value("5.0000"))
                .andExpect(jsonPath("$.totals.FUNDS_WITHHELD").value("4.0000"))
                .andExpect(jsonPath("$.bucketTotals.premium").value("100.0000"))
                .andExpect(jsonPath("$.bucketTotals.loss").value("60.0000"))
                .andExpect(jsonPath("$.bucketTotals.commission").value("25.0000"))
                .andExpect(jsonPath("$.bucketTotals.other").value("10.0000"));
    }

    @Test
    void acceptsTheInternalPrefixAndQueryTenant() throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.findAll(TENANT, ACCOUNT_ID)).thenReturn(List.of());

        mvc.perform(get(INTERNAL_PATH, CONTRACT_ID, ACCOUNT_ID)
                        .param("tenantId", TENANT.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.totals.PREMIUM").value("0.0000"))
                .andExpect(jsonPath("$.bucketTotals.premium").value("0.0000"))
                .andExpect(jsonPath("$.bucketTotals.loss").value("0.0000"))
                .andExpect(jsonPath("$.bucketTotals.commission").value("0.0000"))
                .andExpect(jsonPath("$.bucketTotals.other").value("0.0000"));
    }

    @Test
    void addsDraftLineAndWritesLineAddedEvent() throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.create(any(ContractTechnicalAccountLine.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineType").value("PREMIUM"))
                .andExpect(jsonPath("$.reference").value("BORD-2026-001"))
                .andExpect(jsonPath("$.description").value("Q1 ceded premium"))
                .andExpect(jsonPath("$.amount").value("1000.0000"));

        ArgumentCaptor<ContractTechnicalAccountLine> line =
                ArgumentCaptor.forClass(ContractTechnicalAccountLine.class);
        verify(lines).create(line.capture());
        assertThat(line.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(line.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(line.getValue().lineType()).isEqualTo(TechnicalAccountLineType.PREMIUM);
        assertThat(line.getValue().amount()).isEqualByComparingTo("1000.0000");

        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType())
                .isEqualTo("contract.technical-account.line-added");
        assertThat(event.getValue().aggregateType()).isEqualTo("TechnicalAccountLine");
        assertThat(event.getValue().aggregateId()).isEqualTo(line.getValue().id());
        assertThat(event.getValue().aggregateVersion()).isEqualTo(1L);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
                .contains("\"lineId\":\"" + line.getValue().id() + "\"")
                .contains("\"lineType\":\"PREMIUM\"")
                .contains("\"amount\":\"1000.0000\"");
    }

    @Test
    void rejectsAddingALineToANonDraftAccount() throws Exception {
        contractExists();
        accountExists("ISSUED");

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_EDITABLE"));

        verify(lines, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void deletesDraftLineAndWritesLineRemovedEvent() throws Exception {
        contractExists();
        accountExists("DRAFT");
        ContractTechnicalAccountLine line =
                Fixtures.technicalAccountLine("COMMISSION", "100.0000");
        when(lines.findById(TENANT, ACCOUNT_ID, LINE_ID)).thenReturn(Optional.of(line));
        when(lines.delete(TENANT, ACCOUNT_ID, LINE_ID)).thenReturn(true);

        mvc.perform(delete(PATH + "/{lineId}", CONTRACT_ID, ACCOUNT_ID, LINE_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNoContent());

        verify(lines).delete(TENANT, ACCOUNT_ID, LINE_ID);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).insert(event.capture());
        assertThat(event.getValue().eventType())
                .isEqualTo("contract.technical-account.line-removed");
        assertThat(event.getValue().aggregateType()).isEqualTo("TechnicalAccountLine");
        assertThat(event.getValue().aggregateId()).isEqualTo(LINE_ID);
        assertThat(event.getValue().partitionKey()).isEqualTo(CONTRACT_ID.toString());
        assertThat(event.getValue().payload())
                .contains("\"contractId\":\"" + CONTRACT_ID + "\"")
                .contains("\"accountId\":\"" + ACCOUNT_ID + "\"")
                .contains("\"lineId\":\"" + LINE_ID + "\"")
                .contains("\"lineType\":\"COMMISSION\"")
                .contains("\"amount\":\"100.0000\"");
    }

    @Test
    void rejectsDeletingALineFromANonDraftAccount() throws Exception {
        contractExists();
        accountExists("SETTLED");

        mvc.perform(delete(PATH + "/{lineId}", CONTRACT_ID, ACCOUNT_ID, LINE_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_EDITABLE"));

        verify(lines, never()).findById(any(), any(), any());
        verify(lines, never()).delete(any(), any(), any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void returnsNotFoundWhenTheLineDoesNotExist() throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.findById(TENANT, ACCOUNT_ID, LINE_ID)).thenReturn(Optional.empty());

        mvc.perform(delete(PATH + "/{lineId}", CONTRACT_ID, ACCOUNT_ID, LINE_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(lines, never()).delete(any(), any(), any());
    }

    @Test
    void returnsNotFoundWhenTheAccountIsNotInThisContract() throws Exception {
        contractExists();
        when(accounts.findById(TENANT, CONTRACT_ID, ACCOUNT_ID)).thenReturn(Optional.empty());

        mvc.perform(get(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        verify(lines, never()).findAll(any(), any());
    }

    @Test
    void tenantWallHidesTheContractAndItsLines() throws Exception {
        when(contracts.findById(OTHER_TENANT, CONTRACT_ID)).thenReturn(Optional.empty());

        mvc.perform(get(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound());

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isNotFound());

        mvc.perform(delete(PATH + "/{lineId}", CONTRACT_ID, ACCOUNT_ID, LINE_ID)
                        .header("X-EIA-Tenant", OTHER_TENANT.toString()))
                .andExpect(status().isNotFound());

        verify(lines, never()).findAll(any(), any());
        verify(lines, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void requiresATenant() throws Exception {
        mvc.perform(get(PATH, CONTRACT_ID, ACCOUNT_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void rejectsConflictingTenantHeaderAndQueryParameter() throws Exception {
        mvc.perform(get(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .param("tenantId", OTHER_TENANT.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TENANT_MISMATCH"));
    }

    @Test
    void rejectsZeroAndNegativeAmounts() throws Exception {
        contractExists();
        accountExists("DRAFT");

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"1000.0000\"", "\"0\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"1000.0000\"", "\"-0.01\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));

        verify(lines, never()).create(any());
        verify(outbox, never()).insert(any());
    }

    @Test
    void rejectsUnknownOrMissingLineType() throws Exception {
        contractExists();
        accountExists("DRAFT");

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"PREMIUM\"", "\"LOSS\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINE_TYPE"));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("\"PREMIUM\"", "\"FEE\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINE_TYPE"));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference": "BORD-1", "amount": "10.0000"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINE_TYPE"));

        verify(lines, never()).create(any());
    }

    @Test
    void rejectsReferenceAndDescriptionBeyondTheirColumnWidths() throws Exception {
        contractExists();
        accountExists("DRAFT");

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("BORD-2026-001", "R".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REFERENCE"));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY.replace("Q1 ceded premium", "D".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DESCRIPTION"));

        verify(lines, never()).create(any());
    }

    @Test
    void acceptsCaseInsensitiveLineTypeAndTrimsOptionalText() throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.create(any(ContractTechnicalAccountLine.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineType": "paid_loss", "reference": "  CLM-1  ",
                                 "description": "  Paid loss  ", "amount": "400"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineType").value("PAID_LOSS"))
                .andExpect(jsonPath("$.reference").value("CLM-1"))
                .andExpect(jsonPath("$.description").value("Paid loss"));

        ArgumentCaptor<ContractTechnicalAccountLine> line =
                ArgumentCaptor.forClass(ContractTechnicalAccountLine.class);
        verify(lines).create(line.capture());
        assertThat(line.getValue().lineType()).isEqualTo(TechnicalAccountLineType.PAID_LOSS);
        assertThat(line.getValue().amount()).isEqualByComparingTo(
                new BigDecimal("400.0000"));
    }

    @ParameterizedTest
    @EnumSource(TechnicalAccountLineType.class)
    void acceptsEveryExpandedLineType(TechnicalAccountLineType lineType) throws Exception {
        contractExists();
        accountExists("DRAFT");
        when(lines.create(any(ContractTechnicalAccountLine.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post(PATH, CONTRACT_ID, ACCOUNT_ID)
                        .header("X-EIA-Tenant", TENANT.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lineType": "%s", "amount": "10.0000"}
                                """.formatted(lineType.name())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineType").value(lineType.name()));

        ArgumentCaptor<ContractTechnicalAccountLine> line =
                ArgumentCaptor.forClass(ContractTechnicalAccountLine.class);
        verify(lines).create(line.capture());
        assertThat(line.getValue().lineType()).isEqualTo(lineType);
        assertThat(line.getValue().lineType().bucket()).isEqualTo(lineType.bucket());
    }
}
