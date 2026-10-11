package com.vendor.web;

import com.vendor.model.Client;
import com.vendor.repository.ActivityRepository;
import com.vendor.repository.ClientRepository;
import com.vendor.repository.InvoiceRepository;
import com.vendor.repository.IssuedKeyRepository;
import com.vendor.service.AccountService;
import com.vendor.service.Stage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.*;

/** The dashboard and the account page. */
@Controller
public class PageController {

    private final ClientRepository clients;
    private final IssuedKeyRepository keys;
    private final ActivityRepository activity;
    private final AccountService accounts;
    private final InvoiceRepository invoices;

    @Value("${app.silent-after-hours:36}") private int silentAfterHours;

    public PageController(ClientRepository clients, IssuedKeyRepository keys, ActivityRepository activity, AccountService accounts,
                          InvoiceRepository invoices) {
        this.clients = clients;
        this.keys = keys;
        this.activity = activity;
        this.accounts = accounts;
        this.invoices = invoices;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "all") String show, Model model) {
        List<ClientRow> all = clients.findAllByOrderByNameAsc().stream()
                .map(c -> ClientRow.of(c, keys.findFirstByClient_IdOrderBySeqDesc(c.getId()).orElse(null), silentAfterHours))
                .toList();
        String term = q.trim().toLowerCase(Locale.ROOT);
        List<ClientRow> rows = all.stream()
                .filter(r -> term.isEmpty() || matches(r.client(), term))
                .filter(r -> !"attention".equals(show) || r.needsAttention())
                .toList();

        model.addAttribute("rows", rows);
        model.addAttribute("q", q);
        model.addAttribute("show", show);
        model.addAttribute("total", all.size());
        model.addAttribute("active", all.stream().filter(r -> r.stage() == Stage.ACTIVE && !r.expiringSoon()).count());
        model.addAttribute("expiring", all.stream().filter(ClientRow::expiringSoon).count());
        model.addAttribute("overdue", all.stream().filter(r -> r.stage() == Stage.GRACE || r.stage() == Stage.RESTRICTED).count());
        model.addAttribute("suspended", all.stream().filter(r -> r.stage() == Stage.SUSPENDED).count());
        model.addAttribute("silent", all.stream().filter(ClientRow::silent).count());
        model.addAttribute("attention", all.stream().filter(ClientRow::needsAttention).count());
        model.addAttribute("openInvoices", invoices.countByStatus(com.vendor.model.Invoice.Status.OPEN));
        model.addAttribute("recent", activity.findTop15ByOrderByAtDescIdDesc());
        Map<Long, String> names = new HashMap<>();
        all.forEach(r -> names.put(r.client().getId(), r.client().getName()));
        model.addAttribute("clientNames", names);
        model.addAttribute("silentAfterHours", silentAfterHours);
        return "dashboard";
    }

    @GetMapping("/account")
    public String account(Authentication auth, Model model) {
        model.addAttribute("user", accounts.byEmail(auth.getName()));
        model.addAttribute("team", accounts.team());
        model.addAttribute("emailConfigured", accounts.emailConfigured());
        model.addAttribute("fromAddress", accounts.fromAddress());
        return "account";
    }

    @org.springframework.web.bind.annotation.PostMapping("/account/test-email")
    public String testEmail(Authentication auth, org.springframework.web.servlet.mvc.support.RedirectAttributes flash) {
        com.vendor.service.Mailer.Result r = accounts.sendTestEmail(auth.getName());
        if (r.sent()) flash.addFlashAttribute("notice", "Test email sent to " + auth.getName() + " (Mailjet message " + r.messageId()
                + "). Not in your inbox within a minute? Look in Spam / Promotions.");
        else flash.addFlashAttribute("error", r.error());
        return "redirect:/account";
    }

    private static boolean matches(Client c, String term) {
        return c.getName().toLowerCase(Locale.ROOT).contains(term) || c.getCode().toLowerCase(Locale.ROOT).contains(term)
                || (c.getContactEmail() != null && c.getContactEmail().toLowerCase(Locale.ROOT).contains(term));
    }
}
