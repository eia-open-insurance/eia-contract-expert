package com.eia.contract.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Internal create payload.
 *
 * <p>Contract number format follows the SPEC example {@code XOL-2025-00091}: an
 * alphanumeric prefix, optional separators, up to 32 characters. Currency is an
 * uppercase three-letter code. {@code status} is optional and defaults to DRAFT.</p>
 */
public record CreateContractRequest(
        @NotNull UUID tenantId,
        @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{1,31}",
                message = "must start with an alphanumeric character and contain only letters, digits, '.', '_', '/' or '-'")
        String contractNumber,
        @NotBlank String contractType,
        @NotNull UUID cedentPartyId,
        @NotNull UUID reinsurerPartyId,
        @NotNull LocalDate effectiveDate,
        @NotNull LocalDate expirationDate,
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter uppercase ISO currency code")
        String currency,
        String status
) {}
