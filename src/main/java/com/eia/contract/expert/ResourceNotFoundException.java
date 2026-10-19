package com.eia.contract.expert;

import java.util.UUID;

/** A child resource of a contract does not exist for the tenant. Mapped to 404. */
public class ResourceNotFoundException extends RuntimeException {

    private final transient UUID id;

    public ResourceNotFoundException(String type, UUID id) {
        super(type + " " + id + " was not found");
        this.id = id;
    }

    public UUID id() {
        return id;
    }
}
