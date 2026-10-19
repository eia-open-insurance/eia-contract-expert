package com.eia.contract.expert;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Internal status transition payload with optimistic concurrency control. */
public record UpdateStatusRequest(
        @NotBlank String status,
        @NotNull @Min(0) Integer expectedVersion
) {}
