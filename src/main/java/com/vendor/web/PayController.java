package com.vendor.web;

import com.vendor.model.Invoice;
import com.vendor.service.InvoiceService;
import com.vendor.service.PaystackClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The public page behind an invoice's secret link: the client's Super Admin (or bursar) pays it through
 * Paystack, returns here, and the payment is confirmed with Paystack before it counts.
 */
@Controller
@RequestMapping("/pay/{token}")
public class PayController {

    private final InvoiceService invoices;
    private final PaystackClient paystack;

    @Value("${app.vendor-name:Exam Portal subscriptions}") private String vendorName;

    public PayController(InvoiceService invoices, PaystackClient paystack) {
        this.invoices = invoices;
        this.paystack = paystack;
    }

    @GetMapping
    public String show(@PathVariable String token, Model model) {
        Invoice i = invoices.byPayToken(token);
        model.addAttribute("invoice", i);
        model.addAttribute("clientName", i.getClient().getName());
        model.addAttribute("returnUrl", i.getClient().getPortalUrl());
        model.addAttribute("vendorName", vendorName);
        model.addAttribute("payable", i.getStatus() == Invoice.Status.OPEN && paystack.isConfigured());
        model.addAttribute("onlineAvailable", paystack.isConfigured());
        if (!model.containsAttribute("email"))
            model.addAttribute("email", i.getClient().getBillingEmail() != null ? i.getClient().getBillingEmail() : i.getClient().getContactEmail());
        return "pay";
    }

    @PostMapping
    public String pay(@PathVariable String token, @RequestParam(defaultValue = "") String email, RedirectAttributes flash) {
        try {
            return "redirect:" + invoices.startCheckout(token, email);
        } catch (IllegalArgumentException | PaystackClient.PaystackException e) {
            flash.addFlashAttribute("error", e.getMessage());
            flash.addFlashAttribute("email", email);
            return "redirect:/pay/" + token;
        }
    }

    /** Paystack sends the payer back here with ?reference=…; the payment only counts once Paystack confirms it. */
    @GetMapping("/return")
    public String back(@PathVariable String token, @RequestParam(defaultValue = "") String reference, RedirectAttributes flash) {
        invoices.byPayToken(token);
        try {
            switch (invoices.confirm(reference, "Paystack (payer returned)")) {
                case PAID, ALREADY_PAID -> flash.addFlashAttribute("notice", "Payment received. Thank you!");
                case NOT_PAID -> flash.addFlashAttribute("error", "The payment was not completed. You can try again.");
                case MISMATCH -> flash.addFlashAttribute("error", "The amount paid doesn't match this invoice. Please contact us; nothing is lost.");
                case UNKNOWN_REFERENCE -> flash.addFlashAttribute("error", "We couldn't find that payment.");
            }
        } catch (PaystackClient.PaystackException e) {
            flash.addFlashAttribute("error", "We couldn't confirm the payment with Paystack just now. If you were charged, it will show here shortly.");
        }
        return "redirect:/pay/" + token;
    }
}
