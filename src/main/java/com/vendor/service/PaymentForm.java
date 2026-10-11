package com.vendor.service;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Recording a payment from a client. */
@Getter @Setter
public class PaymentForm {

    @NotNull(message = "Enter the amount.")
    @DecimalMin(value = "0.01", message = "Enter an amount above zero.")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal amount;

    @Pattern(regexp = "[A-Z]{3}", message = "Use a 3-letter currency code, e.g. GHS.")
    private String currency = "GHS";

    @NotNull(message = "Choose the day it was paid.")
    private LocalDate paidOn;

    @NotNull(message = "Choose the date this payment covers the subscription to.")
    private LocalDate coversUntil;

    @Size(max = 30)
    private String method = "Bank transfer";

    @Size(max = 100)
    private String reference;

    @Size(max = 500)
    private String notes;
}
