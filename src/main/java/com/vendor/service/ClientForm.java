package com.vendor.service;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** Adding or editing a client. The code can only be set when the client is created. */
@Getter @Setter
public class ClientForm {

    @Pattern(regexp = "[A-Za-z0-9_-]{1,40}", message = "Use 1–40 letters, digits, - or _.")
    private String code;

    @NotBlank(message = "Enter the institution's name.")
    @Size(max = 200)
    private String name;

    @Size(max = 120)
    private String contactName;

    @Email(message = "Enter a valid email address.")
    @Size(max = 150)
    private String contactEmail;

    @Size(max = 40)
    private String contactPhone;

    @Size(max = 300)
    @Pattern(regexp = "^$|https?://\\S+", message = "Enter a web address starting with https://")
    private String portalUrl;

    @NotBlank(message = "Enter the plan.")
    @Size(max = 40)
    private String plan = "standard";

    @NotNull(message = "Choose the date the subscription is paid until.")
    private LocalDate paidUntil;

    @Min(0) @Max(90)
    private int graceDays = 14;

    @Min(0) @Max(60)
    private int restrictedDays = 7;

    @Size(max = 2000)
    private String notes;

    @DecimalMin(value = "0.00", message = "Enter a price of zero or more.")
    @Digits(integer = 10, fraction = 2)
    private java.math.BigDecimal annualPrice;

    @Pattern(regexp = "[A-Z]{3}", message = "Use a 3-letter currency code, e.g. GHS.")
    private String currency = "GHS";

    @Email(message = "Enter a valid email address.")
    @Size(max = 150)
    private String billingEmail;
}
