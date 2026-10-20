package com.eia.contract.expert;

import com.eia.contract.expert.cession.ContractCession;
import com.eia.contract.expert.commutation.ContractCommutation;
import com.eia.platform.libs.events.Book;
import com.eia.platform.libs.events.EventActor;
import com.eia.platform.libs.events.EventEnvelope;
import com.eia.platform.libs.events.EventIds;
import com.eia.platform.libs.events.EventMetadata;
import com.eia.platform.libs.events.EventSubject;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event factory for the Contract vertical.
 *
 * <p>The canonical catalog ({@code eia-contracts/asyncapi/event-catalog.yaml},
 * SPEC 11.3.9) names {@code contract.contract.created}, {@code .bound},
 * {@code .signed}, {@code .endorsed}, {@code .expired}, {@code .cancelled},
 * {@code .commuted}, and {@code contract.cession.recorded}. It has no generic
 * {@code contract.contract.updated} name, so a status change publishes the
 * requested generic fact and, when the target status has a catalog lifecycle
 * event, that canonical event as well. {@code contract.contract.cession-added}
 * from the task brief maps to the catalog name {@code contract.cession.recorded}.
 * The mismatches are recorded in {@code IMPLEMENTATION.md}.</p>
 *
 * <p>Contract is an ASSUMED-book-only vertical, so every envelope carries
 * {@code book=ASSUMED}. Contract rows start at version 0 for API compatibility
 * while the canonical envelope requires {@code aggregateVersion >= 1}, so the
 * event version is the row version plus one (create -> 1, first update -> 2).
 * A cession is a child aggregate, so its event uses the cession id and version;
 * the outbox partition key remains the contract id to preserve contract order.</p>
 */
public final class ContractEvents {
    static final String CREATED = "contract.contract.created";
    static final String UPDATED = "contract.contract.updated";
    static final String BOUND = "contract.contract.bound";
    static final String SIGNED = "contract.contract.signed";
    static final String ENDORSED = "contract.contract.endorsed";
    static final String EXPIRED = "contract.contract.expired";
    static final String CANCELLED = "contract.contract.cancelled";
    static final String COMMUTED = "contract.contract.commuted";
    static final String CESSION_RECORDED = "contract.cession.recorded";

    private static final Book EVENT_BOOK = Book.ASSUMED;
    private static final String VERTICAL = "contract";
    private static final String CONTRACT_AGGREGATE = "Contract";
    private static final String CESSION_AGGREGATE = "Cession";
    private static final String SOURCE_BUILD = "eia-contract-expert@0.1.0-SNAPSHOT";

    private ContractEvents() {
    }

    /** @return the single {@code contract.contract.created} event for a new contract */
    public static List<EventEnvelope> created(Contract contract, Instant now) {
        Map<String, Object> data = contractPayload(contract);
        return List.of(contractEnvelope(contract, CREATED, contract.version() + 1L, now, data));
    }

    /**
     * A lifecycle write publishes the generic {@code contract.contract.updated}
     * fact and, when the target status is one of the catalog lifecycle facts, the
     * matching canonical event at the same aggregate version.
     *
     * @param limitAmount total live tower capacity, or null when no layer exists
     */
    public static List<EventEnvelope> statusChanged(
            Contract before, Contract after, BigDecimal limitAmount, Instant now) {
        long aggregateVersion = after.version() + 1L;
        List<EventEnvelope> events = new ArrayList<>(2);
        events.add(contractEnvelope(after, UPDATED, aggregateVersion, now, changedPayload(before, after)));

        String lifecycleType = lifecycleEventType(after.status());
        if (lifecycleType != null) {
            Map<String, Object> data = changedPayload(before, after);
            if (BOUND.equals(lifecycleType)) {
                putIfPresent(data, "boundAt", instant(after.boundAt()));
                putIfPresent(data, "boundBy", after.boundBy());
                putIfPresent(data, "limitAmount", money(limitAmount));
            }
            events.add(contractEnvelope(after, lifecycleType, aggregateVersion, now, data));
        }
        return events;
    }

    /**
     * Execution of a commutation publishes the generic update plus the executed
     * commutation detail. A FULL commutation closes the contract
     * ({@code status=CLOSED}); the other types leave it {@code COMMUTED}.
     */
    public static List<EventEnvelope> commutationExecuted(
            Contract before, Contract after, ContractCommutation commutation,
            int releasedSecurities, Instant now) {
        long aggregateVersion = after.version() + 1L;
        List<EventEnvelope> events = new ArrayList<>(2);
        events.add(contractEnvelope(after, UPDATED, aggregateVersion, now, changedPayload(before, after)));

        Map<String, Object> data = changedPayload(before, after);
        data.put("commutationId", commutation.id());
        data.put("commutationNumber", commutation.commutationNumber());
        data.put("commutationType", commutation.commutationType());
        data.put("basis", commutation.basis());
        data.put("commutationStatus", commutation.status());
        putIfPresent(data, "agreedAmount", money(commutation.agreedAmount()));
        putIfPresent(data, "savingsAmount", money(commutation.savingsAmount()));
        putIfPresent(data, "commutationEffectiveDate", date(commutation.effectiveDate()));
        putIfPresent(data, "valuationDate", date(commutation.valuationDate()));
        putIfPresent(data, "releaseDocumentId", commutation.releaseDocumentId());
        putIfPresent(data, "releaseDate", date(commutation.releaseDate()));
        putIfPresent(data, "executedAt", instant(commutation.executedAt()));
        putIfPresent(data, "commutedAt", instant(after.commutedAt()));
        putIfPresent(data, "closedAt", instant(after.closedAt()));
        data.put("contractClosed", after.closedAt() != null);
        data.put("releasedSecurities", releasedSecurities);

        events.add(contractEnvelope(after, COMMUTED, aggregateVersion, now, data));
        return events;
    }

    /**
     * A recorded cession is a child-aggregate fact under the canonical
     * {@code contract.cession.recorded} name. The payload carries the parent
     * contract identity and the cession amounts as decimal strings.
     *
     * @param contractLimitAmount total live tower capacity, or null when none
     */
    public static EventEnvelope cessionRecorded(
            Contract contract, ContractCession cession, BigDecimal contractLimitAmount, Instant now) {
        Map<String, Object> data = identity(contract);
        data.put("contractStatus", contract.status());
        data.put("contractVersion", contract.version());
        data.put("status", cession.status());
        data.put("version", cession.version());
        data.put("cessionId", cession.id());
        data.put("cessionNumber", cession.cessionNumber());
        data.put("cessionType", cession.cessionType());
        data.put("sourceVertical", cession.sourceVertical());
        data.put("sourceType", cession.sourceType());
        putIfPresent(data, "layerId", cession.layerId());
        putIfPresent(data, "policyRef", cession.policyRef());
        putIfPresent(data, "claimRef", cession.claimRef());
        putIfPresent(data, "cessionPct", money(cession.cessionPct()));
        putIfPresent(data, "cededPremiumAmount", money(cession.cededPremiumAmount()));
        putIfPresent(data, "cededLossAmount", money(cession.cededLossAmount()));
        putIfPresent(data, "contractLimitAmount", money(contractLimitAmount));
        putIfPresent(data, "effectiveFrom", date(cession.effectiveFrom()));
        putIfPresent(data, "effectiveTo", date(cession.effectiveTo()));
        putIfPresent(data, "treatyYear", cession.treatyYear());
        putIfPresent(data, "bordereauId", cession.bordereauId());
        putIfPresent(data, "recordedAt", instant(cession.recordedAt()));

        long aggregateVersion = cession.version() + 1L;
        UUID eventId = EventIds.deterministic(contract.tenantId(), CESSION_AGGREGATE, cession.id(),
                aggregateVersion, CESSION_RECORDED);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(CESSION_RECORDED)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(contract.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(CESSION_AGGREGATE)
                .aggregateId(cession.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-contract-expert"))
                .subject(EventSubject.of(VERTICAL, "cession", cession.id(), cession.cessionNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static String lifecycleEventType(String status) {
        return switch (status) {
            case "BOUND" -> BOUND;
            case "SIGNED" -> SIGNED;
            case "ENDORSED" -> ENDORSED;
            case "EXPIRED" -> EXPIRED;
            case "CANCELLED" -> CANCELLED;
            case "COMMUTED" -> COMMUTED;
            default -> null;
        };
    }

    private static Map<String, Object> changedPayload(Contract before, Contract after) {
        Map<String, Object> data = contractPayload(after);
        data.put("changedFields", List.of("status"));
        data.put("from", before.status());
        data.put("to", after.status());
        return data;
    }

    private static Map<String, Object> contractPayload(Contract contract) {
        Map<String, Object> data = identity(contract);
        data.put("status", contract.status());
        data.put("version", contract.version());
        return data;
    }

    private static Map<String, Object> identity(Contract contract) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("contractId", contract.id());
        data.put("contractNumber", contract.contractNumber());
        data.put("contractType", contract.contractType());
        data.put("cedentPartyId", contract.cedentPartyId());
        data.put("reinsurerPartyId", contract.reinsurerPartyId());
        data.put("currency", contract.currency());
        putIfPresent(data, "effectiveDate", date(contract.effectiveDate()));
        putIfPresent(data, "expirationDate", date(contract.expirationDate()));
        return data;
    }

    private static EventEnvelope contractEnvelope(
            Contract contract, String eventType, long aggregateVersion, Instant now,
            Map<String, Object> data) {
        UUID eventId = EventIds.deterministic(contract.tenantId(), CONTRACT_AGGREGATE, contract.id(),
                aggregateVersion, eventType);
        return EventEnvelope.builder()
                .eventId(eventId)
                .eventType(eventType)
                .eventVersion(1)
                .occurredAt(now)
                .recordedAt(now)
                .tenantId(contract.tenantId())
                .book(EVENT_BOOK)
                .vertical(VERTICAL)
                .aggregateType(CONTRACT_AGGREGATE)
                .aggregateId(contract.id())
                .aggregateVersion(aggregateVersion)
                .sequence(0)
                .actor(EventActor.system("eia-contract-expert"))
                .subject(EventSubject.of(VERTICAL, "contract", contract.id(), contract.contractNumber()))
                .links(List.of())
                .data(data)
                .metadata(EventMetadata.v1(SOURCE_BUILD))
                .build();
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String money(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    private static String date(java.time.LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }
}
