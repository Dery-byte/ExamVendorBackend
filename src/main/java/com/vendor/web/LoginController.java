package com.vendor.web;

import com.vendor.model.VendorUser;
import com.vendor.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in in two steps, like the exam portal's developer sign-in: an email, then the 6-digit code sent
 * to it. The reply is the same whether or not the email may sign in.
 */
@Controller
public class LoginController {

    static final String EMAIL = LoginController.class.getName() + ".email";
    private static final int MAX_REQUESTS = 10;
    private static final long WINDOW_MS = 10 * 60_000L;

    private final AccountService accounts;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();
    private final Map<String, long[]> requests = new ConcurrentHashMap<>();   // ip -> [count, window start]

    public LoginController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/login")
    public String login(Authentication auth) {
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_VENDOR"))
                ? "redirect:/" : "login";
    }

    @PostMapping("/login/code")
    public String sendCode(@RequestParam(defaultValue = "") String email, HttpServletRequest request, HttpSession session,
                           RedirectAttributes flash) {
        String e = AccountService.normalise(email);
        if (!e.matches("[^@\\s]+@[^@\\s]+") || e.length() > 150) {
            flash.addFlashAttribute("error", "Enter your email address.");
            return "redirect:/login";
        }
        if (tooMany(request.getRemoteAddr())) {
            flash.addFlashAttribute("error", "Too many codes requested. Please wait a few minutes.");
            return "redirect:/login";
        }
        accounts.requestCode(e);
        session.setAttribute(EMAIL, e);
        return "redirect:/login/code";
    }

    @GetMapping("/login/code")
    public String codeForm(HttpSession session, Model model) {
        Object email = session.getAttribute(EMAIL);
        if (email == null) return "redirect:/login";
        model.addAttribute("email", email);
        model.addAttribute("maxAttempts", AccountService.MAX_ATTEMPTS);
        model.addAttribute("fromAddress", accounts.fromAddress());
        return "login-code";
    }

    @PostMapping("/login/verify")
    public String verify(@RequestParam(defaultValue = "") String code, HttpServletRequest request, HttpServletResponse response,
                         HttpSession session, RedirectAttributes flash) {
        Object email = session.getAttribute(EMAIL);
        if (email == null) return "redirect:/login";
        VendorUser user;
        try {
            user = accounts.verify((String) email, code);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/login/code";
        }
        session.removeAttribute(EMAIL);
        request.changeSessionId();   // a new session id once signed in
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user.getEmail(), null, List.of(new SimpleGrantedAuthority("ROLE_VENDOR"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return "redirect:/";
    }

    private boolean tooMany(String ip) {
        long now = System.currentTimeMillis();
        long[] v = requests.compute(ip, (k, old) -> old == null || now - old[1] > WINDOW_MS ? new long[]{1, now} : new long[]{old[0] + 1, old[1]});
        return v[0] > MAX_REQUESTS;
    }
}
