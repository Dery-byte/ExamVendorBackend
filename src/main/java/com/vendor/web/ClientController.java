package com.vendor.web;

import com.vendor.model.Client;
import com.vendor.model.Invoice;
import com.vendor.repository.ActivityRepository;
import com.vendor.repository.InvoiceRepository;
import com.vendor.repository.IssuedKeyRepository;
import com.vendor.repository.PaymentRepository;
import com.vendor.service.ClientForm;
import com.vendor.service.ClientService;
import com.vendor.service.InvoiceForm;
import com.vendor.service.InvoiceService;
import com.vendor.service.PaymentForm;
import com.vendor.service.PaystackClient;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adding and editing clients, recording payments, suspending, and replacing check-in tokens. */
@Controller
@RequestMapping("/clients")
public class ClientController {

    private final ClientService service;
    private final InvoiceService invoiceService;
    private final IssuedKeyRepository keys;
    private final PaymentRepository payments;
    private final ActivityRepository activity;
    private final InvoiceRepository invoices;
    private final PaystackClient paystack;

    @Value("${app.public-url:http://localhost:8090}") private String publicUrl;
    @Value("${app.silent-after-hours:36}") private int silentAfterHours;

    public ClientController(ClientService service, InvoiceService invoiceService, IssuedKeyRepository keys, PaymentRepository payments,
                            ActivityRepository activity, InvoiceRepository invoices, PaystackClient paystack) {
        this.service = service;
        this.invoiceService = invoiceService;
        this.keys = keys;
        this.payments = payments;
        this.activity = activity;
        this.invoices = invoices;
        this.paystack = paystack;
    }

    @GetMapping("/new")
    public String newClient(Model model) {
        ClientForm f = new ClientForm();
        f.setPaidUntil(LocalDate.now().plusYears(1));
        model.addAttribute("form", f);
        model.addAttribute("editing", false);
        return "client-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") ClientForm form, BindingResult errors, Authentication auth,
                         Model model, RedirectAttributes flash) {
        if (!errors.hasErrors()) {
            try {
                ClientService.Created c = service.create(form, auth.getName());
                flash.addFlashAttribute("newToken", c.token());
                flash.addFlashAttribute("notice", "Client added and its first key issued.");
                return "redirect:/clients/" + c.client().getId();
            } catch (IllegalArgumentException | IllegalStateException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        model.addAttribute("editing", false);
        return "client-form";
    }

    @GetMapping("/{id}")
    public String show(@PathVariable Long id, Model model) {
        Client c = service.get(id);
        if (!model.containsAttribute("payment")) model.addAttribute("payment", defaultPayment(c));
        if (!model.containsAttribute("invoiceForm")) model.addAttribute("invoiceForm", defaultInvoice(c));
        List<Invoice> list = invoices.findByClient_IdOrderByCreatedAtDescIdDesc(id);
        Map<Long, String> payUrls = new HashMap<>();
        list.forEach(i -> payUrls.put(i.getId(), invoiceService.payUrl(i)));
        model.addAttribute("invoices", list);
        model.addAttribute("payUrls", payUrls);
        model.addAttribute("hasOpenInvoice", list.stream().anyMatch(i -> i.getStatus() == Invoice.Status.OPEN));
        model.addAttribute("paystackMode", paystack.mode());
        model.addAttribute("row", ClientRow.of(c, keys.findFirstByClient_IdOrderBySeqDesc(id).orElse(null), silentAfterHours));
        model.addAttribute("c", c);
        model.addAttribute("payments", payments.findByClient_IdOrderByPaidOnDescIdDesc(id));
        model.addAttribute("keys", keys.findTop20ByClient_IdOrderBySeqDesc(id));
        model.addAttribute("activity", activity.findTop30ByClientIdOrderByAtDescIdDesc(id));
        model.addAttribute("publicUrl", publicUrl);
        return "client";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        Client c = service.get(id);
        model.addAttribute("form", ClientService.formOf(c));
        model.addAttribute("c", c);
        model.addAttribute("editing", true);
        return "client-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id, @Valid @ModelAttribute("form") ClientForm form, BindingResult errors,
                         Authentication auth, Model model, RedirectAttributes flash) {
        Client c = service.get(id);
        if (!errors.hasErrors()) {
            try {
                service.update(id, form, auth.getName());
                flash.addFlashAttribute("notice", "Saved.");
                return "redirect:/clients/" + id;
            } catch (IllegalArgumentException | IllegalStateException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        model.addAttribute("c", c);
        model.addAttribute("editing", true);
        return "client-form";
    }

    @PostMapping("/{id}/payments")
    public String pay(@PathVariable Long id, @Valid @ModelAttribute("payment") PaymentForm form, BindingResult errors,
                      Authentication auth, Model model, RedirectAttributes flash) {
        if (!errors.hasErrors()) {
            try {
                service.recordPayment(id, form, auth.getName());
                flash.addFlashAttribute("notice", "Payment recorded. The client's portal picks up its new key at its next check-in.");
                return "redirect:/clients/" + id;
            } catch (IllegalArgumentException | IllegalStateException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        model.addAttribute("paymentOpen", true);
        return show(id, model);
    }

    @PostMapping("/{id}/invoices")
    public String raiseInvoice(@PathVariable Long id, @Valid @ModelAttribute("invoiceForm") InvoiceForm form, BindingResult errors,
                               Authentication auth, Model model, RedirectAttributes flash) {
        if (!errors.hasErrors()) {
            try {
                Invoice i = invoiceService.create(id, form, auth.getName());
                flash.addFlashAttribute("notice", i.getNumber() + " raised. The client's portal shows it with a Pay online button from its next check-in.");
                return "redirect:/clients/" + id;
            } catch (IllegalArgumentException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        model.addAttribute("invoiceOpen", true);
        return show(id, model);
    }

    @PostMapping("/{id}/invoices/{invoiceId}/cancel")
    public String cancelInvoice(@PathVariable Long id, @PathVariable Long invoiceId, Authentication auth, RedirectAttributes flash) {
        return act(id, flash, () -> invoiceService.cancel(invoiceId, auth.getName()), "Invoice cancelled.");
    }

    @PostMapping("/{id}/suspend")
    public String suspend(@PathVariable Long id, @RequestParam(defaultValue = "") String reason, Authentication auth, RedirectAttributes flash) {
        return act(id, flash, () -> service.suspend(id, reason, auth.getName()),
                "Suspension issued. The client's portal is suspended at its next check-in.");
    }

    @PostMapping("/{id}/resume")
    public String resume(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        return act(id, flash, () -> service.resume(id, auth.getName()),
                "Suspension lifted. The client's portal picks it up at its next check-in.");
    }

    @PostMapping("/{id}/token")
    public String rotate(@PathVariable Long id, Authentication auth, RedirectAttributes flash) {
        try {
            flash.addFlashAttribute("newToken", service.rotateToken(id, auth.getName()));
            flash.addFlashAttribute("notice", "New token made. The old one no longer works: enter the new one in the client's portal.");
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/clients/" + id;
    }

    private String act(Long id, RedirectAttributes flash, Runnable action, String ok) {
        try {
            action.run();
            flash.addFlashAttribute("notice", ok);
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/clients/" + id;
    }

    /** A year's renewal at the client's price, due when the paid period ends. */
    private static InvoiceForm defaultInvoice(Client c) {
        InvoiceForm f = new InvoiceForm();
        f.setAmount(c.getAnnualPrice());
        f.setCurrency(c.getCurrency());
        f.setCoversUntil(c.getPaidUntil().plusYears(1));
        f.setDueDate(c.getPaidUntil().isBefore(LocalDate.now()) ? LocalDate.now() : c.getPaidUntil());
        return f;
    }

    /** Today's payment, covering a year from where the subscription now ends. */
    private static PaymentForm defaultPayment(Client c) {
        PaymentForm p = new PaymentForm();
        p.setPaidOn(LocalDate.now());
        LocalDate from = c.getPaidUntil().isAfter(LocalDate.now()) ? c.getPaidUntil() : LocalDate.now();
        p.setCoversUntil(from.plusYears(1));
        return p;
    }
}
