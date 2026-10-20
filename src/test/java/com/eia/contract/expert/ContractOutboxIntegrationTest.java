package com.eia.contract.expert;

import com.eia.contract.expert.bind.ContractBindService;
import com.eia.contract.expert.cession.CessionController;
import com.eia.contract.expert.cession.ContractCession;
import com.eia.contract.expert.commutation.CommutationController;
import com.eia.contract.expert.commutation.CommutationService;
import com.eia.contract.expert.commutation.ContractCommutation;
import com.eia.contract.expert.layer.ContractLayer;
import com.eia.contract.expert.layer.LayerRepository;
import com.eia.contract.expert.participant.ContractParticipant;
import com.eia.contract.expert.participant.ParticipantRepository;
import com.eia.contract.expert.term.ContractTerm;
import com.eia.contract.expert.term.TermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.eia.contract.expert.Fixtures.APPROVER;
import static com.eia.contract.expert.Fixtures.CEDENT;
import static com.eia.contract.expert.Fixtures.DOCUMENT;
import static com.eia.contract.expert.Fixtures.FROM;
import static com.eia.contract.expert.Fixtures.REINSURER;
import static com.eia.contract.expert.Fixtures.TENANT;
import static com.eia.contract.expert.Fixtures.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the transactional-outbox contract of the Contract expert: every
 * aggregate write appends its canonical event in the same transaction, a
 * rollback leaves neither the row nor the event behind, and the payload carries
 * the agreed contract and child-resource fields as decimal strings.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractOutboxIntegrationTest {
    private static final String CONTRACT_NUMBER = "XOL-2026-00091";
    private static final String CESSION_NUMBER = "CES-2026-00001";
    private static final String COMMUTATION_NUMBER = "COM-2026-00001";

    @Autowired
    private ContractController contracts;

    @Autowired
    private ContractBindService bindService;

    @Autowired
    private CessionController cessions;

    @Autowired
    private CommutationService commutations;

    @Autowired
    private LayerRepository layers;

    @Autowired
    private ParticipantRepository participants;

    @Autowired
    private TermRepository terms;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;

    @BeforeEach
    void clean() {
        transaction = new TransactionTemplate(transactionManager);
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM contract_commutation");
        jdbc.update("DELETE FROM contract_cession");
        jdbc.update("DELETE FROM contract_security");
        jdbc.update("DELETE FROM contract_term");
        jdbc.update("DELETE FROM contract_participant");
        jdbc.update("DELETE FROM contract_layer");
        jdbc.update("DELETE FROM contract");
    }

    @Test
    void createWritesTheContractAndCreatedEventTogether() {
        Contract contract = createDraft();

        assertThat(contract.status()).isEqualTo("DRAFT");
        assertThat(contract.version()).isZero();

        Map<String, Object> event = onlyEvent(contract.id());
        assertThat(event.get("event_type")).isEqualTo("contract.contract.created");
        assertThat(event.get("aggregate_type")).isEqualTo("Contract");
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("tenant_id")).isEqualTo(TENANT);
        assertThat(event.get("partition_key")).isEqualTo(contract.id().toString());

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"contractType\":\"EXCESS_OF_LOSS\"")
                .contains("\"status\":\"DRAFT\"")
                .contains("\"version\":0")
                .contains("\"cedentPartyId\":\"" + CEDENT + "\"")
                .contains("\"reinsurerPartyId\":\"" + REINSURER + "\"")
                .contains("\"currency\":\"USD\"")
                .contains("\"effectiveDate\":\"2026-01-01\"")
                .contains("\"expirationDate\":\"2026-12-31\"");
        String headers = (String) event.get("headers");
        assertThat(headers)
                .contains("\"book\":\"ASSUMED\"")
                .contains("\"vertical\":\"contract\"")
                .contains("\"businessKey\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"schemaVersion\":\"1.0.0\"");
    }

    @Test
    void createRollbackLeavesNeitherTheContractNorTheEvent() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            createDraft();
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(count("contract")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void statusChangeWritesUpdatedAndLifecycleEventsInTheSameTransaction() {
        Contract contract = createDraft();
        Contract quoted = contracts.updateStatus(TENANT, contract.id(),
                new UpdateStatusRequest("QUOTED", 0));

        assertThat(quoted.status()).isEqualTo("QUOTED");
        assertThat(quoted.version()).isEqualTo(1);

        List<Map<String, Object>> events = eventsFor(contract.id());
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("event_type")).isEqualTo("contract.contract.created");
        assertThat(events.get(1).get("event_type")).isEqualTo("contract.contract.updated");
        assertThat(events.get(1).get("aggregate_version")).isEqualTo(2L);

        String payload = (String) events.get(1).get("payload");
        assertThat(payload)
                .contains("\"changedFields\":[\"status\"]")
                .contains("\"status\":\"QUOTED\"")
                .contains("\"version\":1")
                .contains("\"from\":\"DRAFT\"")
                .contains("\"to\":\"QUOTED\"")
                .contains("\"contractId\":\"" + contract.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"");
    }

    @Test
    void statusChangeToACommittedLifecycleStatusAlsoWritesTheCatalogEvent() {
        Contract bound = boundContract();
        Contract signed = contracts.updateStatus(TENANT, bound.id(),
                new UpdateStatusRequest("SIGNED", bound.version()));

        assertThat(signed.status()).isEqualTo("SIGNED");
        assertThat(signed.version()).isEqualTo(4);

        Map<String, Object> signedEvent = eventOfType(bound.id(), "contract.contract.signed");
        assertThat(signedEvent.get("aggregate_type")).isEqualTo("Contract");
        assertThat(signedEvent.get("aggregate_version")).isEqualTo(5L);
        String payload = (String) signedEvent.get("payload");
        assertThat(payload)
                .contains("\"status\":\"SIGNED\"")
                .contains("\"version\":4")
                .contains("\"from\":\"BOUND\"")
                .contains("\"to\":\"SIGNED\"");
    }

    @Test
    void statusRollbackKeepsTheOldStateAndWritesNoEvent() {
        Contract contract = createDraft();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            contracts.updateStatus(TENANT, contract.id(), new UpdateStatusRequest("QUOTED", 0));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = contractRow(contract.id());
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("version")).isEqualTo(0);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(eventCount(contract.id(), "contract.contract.updated")).isZero();
    }

    @Test
    void bindWritesTheBoundEventWithApprovalAndLimitAmount() {
        Contract placed = placedContract();
        addBindData(placed);

        Contract bound = bindService.bind(TENANT, placed.id(), APPROVER, placed.version()).contract();

        assertThat(bound.status()).isEqualTo("BOUND");
        assertThat(bound.version()).isEqualTo(3);
        assertThat(bound.boundBy()).isEqualTo(APPROVER);
        assertThat(bound.boundAt()).isNotNull();

        Map<String, Object> event = eventOfType(placed.id(), "contract.contract.bound");
        assertThat(event.get("aggregate_type")).isEqualTo("Contract");
        assertThat(event.get("aggregate_version")).isEqualTo(4L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + placed.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"status\":\"BOUND\"")
                .contains("\"version\":3")
                .contains("\"from\":\"PLACED\"")
                .contains("\"to\":\"BOUND\"")
                .contains("\"boundBy\":\"" + APPROVER + "\"")
                .contains("\"limitAmount\":\"1000000.0000\"")
                .contains("\"cedentPartyId\":\"" + CEDENT + "\"")
                .contains("\"reinsurerPartyId\":\"" + REINSURER + "\"")
                .contains("\"currency\":\"USD\"");
        assertThat(payload).contains("\"boundAt\":");
        String headers = (String) event.get("headers");
        assertThat(headers).contains("\"book\":\"ASSUMED\"")
                .contains("\"businessKey\":\"" + CONTRACT_NUMBER + "\"");
    }

    @Test
    void bindRollbackKeepsTheContractPlacedAndWritesNoBoundEvent() {
        Contract placed = placedContract();
        addBindData(placed);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            bindService.bind(TENANT, placed.id(), APPROVER, placed.version());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = contractRow(placed.id());
        assertThat(row.get("status")).isEqualTo("PLACED");
        assertThat(row.get("version")).isEqualTo(2);
        assertThat(row.get("bound_at")).isNull();
        assertThat(count("outbox_event")).isEqualTo(3);
        assertThat(eventCount(placed.id(), "contract.contract.bound")).isZero();
    }

    @Test
    void cessionWritesTheCatalogCessionRecordedEventWithCededAmounts() {
        Contract bound = boundContract();

        ContractCession cession = cessions.create(TENANT, bound.id(), cessionRequest());

        assertThat(cession.status()).isEqualTo("RECORDED");
        assertThat(cession.version()).isZero();

        List<Map<String, Object>> events = eventsFor(bound.id());
        assertThat(events).hasSize(6);
        Map<String, Object> event = events.get(events.size() - 1);
        assertThat(event.get("event_type")).isEqualTo("contract.cession.recorded");
        assertThat(event.get("aggregate_type")).isEqualTo("Cession");
        assertThat(event.get("aggregate_id")).isEqualTo(cession.id());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(event.get("partition_key")).isEqualTo(bound.id().toString());

        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + bound.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"contractStatus\":\"BOUND\"")
                .contains("\"contractVersion\":3")
                .contains("\"status\":\"RECORDED\"")
                .contains("\"version\":0")
                .contains("\"cessionId\":\"" + cession.id() + "\"")
                .contains("\"cessionNumber\":\"" + CESSION_NUMBER + "\"")
                .contains("\"cessionType\":\"QUOTA_SHARE\"")
                .contains("\"sourceType\":\"CLAIM\"")
                .contains("\"policyRef\":\"POL-2026-0001\"")
                .contains("\"claimRef\":\"CLM-2026-0007\"")
                .contains("\"cessionPct\":\"40.000000\"")
                .contains("\"cededPremiumAmount\":\"400000.0000\"")
                .contains("\"cededLossAmount\":\"200000.0000\"")
                .contains("\"contractLimitAmount\":\"1000000.0000\"")
                .contains("\"cedentPartyId\":\"" + CEDENT + "\"")
                .contains("\"reinsurerPartyId\":\"" + REINSURER + "\"")
                .contains("\"currency\":\"USD\"");
        String headers = (String) event.get("headers");
        assertThat(headers).contains("\"book\":\"ASSUMED\"")
                .contains("\"vertical\":\"contract\"")
                .contains("\"businessKey\":\"" + CESSION_NUMBER + "\"");
    }

    @Test
    void cessionRollbackLeavesNeitherTheCessionNorTheEvent() {
        Contract bound = boundContract();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            cessions.create(TENANT, bound.id(), cessionRequest());
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("contract_cession")).isZero();
        assertThat(count("outbox_event")).isEqualTo(5);
        assertThat(eventCount(bound.id(), "contract.cession.recorded")).isZero();
    }

    @Test
    void commutationWritesTheCatalogCommutedEventWithExecutionDetail() {
        Contract inForce = inForceContract();
        ContractCommutation proposed = commutations.create(TENANT, inForce.id(), commutationRequest());
        commutations.approve(TENANT, inForce.id(), proposed.id(), APPROVER);

        CommutationService.Execution execution = commutations.execute(TENANT, inForce.id(),
                proposed.id(), new CommutationController.ExecuteCommutationRequest(
                        DOCUMENT, LocalDate.of(2026, 12, 31)));

        assertThat(execution.contract().status()).isEqualTo("CLOSED");
        assertThat(execution.contract().version()).isEqualTo(6);
        assertThat(execution.contractClosed()).isTrue();

        Map<String, Object> event = eventOfType(inForce.id(), "contract.contract.commuted");
        assertThat(event.get("aggregate_type")).isEqualTo("Contract");
        assertThat(event.get("aggregate_version")).isEqualTo(7L);
        String payload = (String) event.get("payload");
        assertThat(payload)
                .contains("\"contractId\":\"" + inForce.id() + "\"")
                .contains("\"contractNumber\":\"" + CONTRACT_NUMBER + "\"")
                .contains("\"status\":\"CLOSED\"")
                .contains("\"version\":6")
                .contains("\"from\":\"IN_FORCE\"")
                .contains("\"to\":\"CLOSED\"")
                .contains("\"commutationId\":\"" + proposed.id() + "\"")
                .contains("\"commutationNumber\":\"" + COMMUTATION_NUMBER + "\"")
                .contains("\"commutationType\":\"FULL\"")
                .contains("\"basis\":\"NEGOTIATED\"")
                .contains("\"commutationStatus\":\"EXECUTED\"")
                .contains("\"agreedAmount\":\"2650000.0000\"")
                .contains("\"savingsAmount\":\"230000.0000\"")
                .contains("\"commutationEffectiveDate\":\"2026-12-31\"")
                .contains("\"valuationDate\":\"2026-11-30\"")
                .contains("\"releaseDocumentId\":\"" + DOCUMENT + "\"")
                .contains("\"contractClosed\":true")
                .contains("\"releasedSecurities\":0")
                .contains("\"cedentPartyId\":\"" + CEDENT + "\"")
                .contains("\"reinsurerPartyId\":\"" + REINSURER + "\"")
                .contains("\"currency\":\"USD\"");
        assertThat(payload).contains("\"executedAt\":")
                .contains("\"commutedAt\":")
                .contains("\"closedAt\":");
    }

    @Test
    void commutationRollbackKeepsTheContractInForceAndWritesNoCommutedEvent() {
        Contract inForce = inForceContract();
        ContractCommutation proposed = commutations.create(TENANT, inForce.id(), commutationRequest());
        commutations.approve(TENANT, inForce.id(), proposed.id(), APPROVER);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            commutations.execute(TENANT, inForce.id(), proposed.id(),
                    new CommutationController.ExecuteCommutationRequest(
                            DOCUMENT, LocalDate.of(2026, 12, 31)));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        Map<String, Object> row = contractRow(inForce.id());
        assertThat(row.get("status")).isEqualTo("IN_FORCE");
        assertThat(row.get("version")).isEqualTo(5);
        assertThat(row.get("commuted_at")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM contract_commutation WHERE id = ?",
                String.class, proposed.id())).isEqualTo("APPROVED");
        assertThat(count("outbox_event")).isEqualTo(8);
        assertThat(eventCount(inForce.id(), "contract.contract.commuted")).isZero();
    }

    private Contract createDraft() {
        return contracts.create(new CreateContractRequest(TENANT, CONTRACT_NUMBER, "EXCESS_OF_LOSS",
                CEDENT, REINSURER, FROM, TO, "USD", null));
    }

    private Contract placedContract() {
        Contract contract = createDraft();
        contract = contracts.updateStatus(TENANT, contract.id(), new UpdateStatusRequest("QUOTED", 0));
        return contracts.updateStatus(TENANT, contract.id(), new UpdateStatusRequest("PLACED", 1));
    }

    private Contract boundContract() {
        Contract placed = placedContract();
        addBindData(placed);
        return bindService.bind(TENANT, placed.id(), APPROVER, placed.version()).contract();
    }

    private Contract inForceContract() {
        Contract bound = boundContract();
        Contract signed = contracts.updateStatus(TENANT, bound.id(),
                new UpdateStatusRequest("SIGNED", bound.version()));
        return contracts.updateStatus(TENANT, signed.id(),
                new UpdateStatusRequest("IN_FORCE", signed.version()));
    }

    private void addBindData(Contract contract) {
        ContractLayer layer = layers.create(ContractLayer.create(TENANT, contract.id(), 1,
                "EXCESS_OF_LOSS", new BigDecimal("0"), new BigDecimal("1000000"), "USD",
                "PER_OCCURRENCE", null, null, "NONE", null, FROM, TO, "QUOTED"));
        participants.create(ContractParticipant.create(TENANT, contract.id(), layer.id(), REINSURER,
                "REINSURER", new BigDecimal("100"), null, 1, false, null, "A+", false,
                "PENDING", null, null, FROM, null, "ACTIVE"));
        terms.create(ContractTerm.create(TENANT, contract.id(), "GOVERNING_LAW",
                "New York", "TEXT", FROM, null, null));
        terms.create(ContractTerm.create(TENANT, contract.id(), "REINSTATEMENT_PROVISION",
                "Free", "TEXT", FROM, null, null));
        terms.create(ContractTerm.create(TENANT, contract.id(), "ULTIMATE_NET_LOSS",
                "110", "PERCENT", FROM, null, null));
    }

    private CessionController.CreateCessionRequest cessionRequest() {
        return new CessionController.CreateCessionRequest(
                null, CESSION_NUMBER, "ASSUMED", "CLAIM", "POL-2026-0001", "CLM-2026-0007",
                "QUOTA_SHARE", new BigDecimal("40"), new BigDecimal("400000"),
                new BigDecimal("200000"), "USD", FROM, null, 2026, "RECORDED", null);
    }

    private CommutationController.CreateCommutationRequest commutationRequest() {
        return new CommutationController.CreateCommutationRequest(
                COMMUTATION_NUMBER, "FULL", LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 11, 30), new BigDecimal("2650000"), "USD", "NEGOTIATED",
                DOCUMENT, null, new BigDecimal("230000"));
    }

    private List<Map<String, Object>> eventsFor(UUID contractId) {
        return jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, status,
                       tenant_id, partition_key, payload, headers
                  FROM outbox_event
                 WHERE partition_key = ?
                 ORDER BY event_sequence
                """, contractId.toString());
    }

    private Map<String, Object> onlyEvent(UUID contractId) {
        List<Map<String, Object>> events = eventsFor(contractId);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    private Map<String, Object> eventOfType(UUID contractId, String eventType) {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT event_type, aggregate_type, aggregate_id, aggregate_version, status,
                       tenant_id, partition_key, payload, headers
                  FROM outbox_event
                 WHERE partition_key = ? AND event_type = ?
                """, contractId.toString(), eventType);
        assertThat(events).as("event " + eventType).hasSize(1);
        return events.get(0);
    }

    private int eventCount(UUID contractId, String eventType) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_event
                 WHERE partition_key = ? AND event_type = ?
                """, Integer.class, contractId.toString(), eventType);
    }

    private Map<String, Object> contractRow(UUID contractId) {
        return jdbc.queryForMap("""
                SELECT status, version, bound_at, commuted_at, closed_at
                  FROM contract
                 WHERE id = ?
                """, contractId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
