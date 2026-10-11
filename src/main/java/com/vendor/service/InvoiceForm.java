package com.vendor.service;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Raising an invoice for a client. */
@Getter @Setter
public class InvoiceForm {

    @NotNull(message = "Enter the amount.")
    @DecimalMin(value = "1.00", message = "Enter an amount of at least 1.")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @Pattern(regexp = "[A-Z]{3}", message = "Use a 3-letter currency code, e.g. GHS.")
    private String currency = "GHS";

    @NotNull(message = "Choose the date paying this renews the subscription to.")
    private LocalDate coversUntil;

    @NotNull(message = "Choose the due date.")
    private LocalDate dueDate;

    @Size(max = 300)
    private String notes;
}
