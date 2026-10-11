package com.vendor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vendor.service.InvoiceService;
import com.vendor.service.PaystackClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Paystack tells us here when a charge succeeds, even if the payer never comes back to the pay page.
 * Only signed calls count, and the payment is confirmed with Paystack's API before it is recorded.
 * A failure answers 5xx so Paystack retries later.
 */
@RestController
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PaystackClient paystack;
    private final InvoiceService invoices;

    public PaystackWebhookController(PaystackClient paystack, InvoiceService invoices) {
        this.paystack = paystack;
        this.invoices = invoices;
    }

    @PostMapping("/api/v1/paystack/webhook")
    public ResponseEntity<Void> webhook(@RequestBody(required = false) byte[] raw,
                                        @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        if (!paystack.validSignature(raw, signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            JsonNode event = JSON.readTree(raw);
            if (!"charge.success".equals(event.path("event").asText())) return ResponseEntity.ok().build();
            String reference = event.path("data").path("reference").asText("");
            InvoiceService.Outcome outcome = invoices.confirm(reference, "Paystack (webhook)");
            log.info("[Paystack] webhook charge.success {} -> {}", reference, outcome);
            return ResponseEntity.ok().build();
        } catch (PaystackClient.PaystackException e) {
            log.warn("[Paystack] webhook could not confirm the charge yet: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.error("[Paystack] webhook failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
