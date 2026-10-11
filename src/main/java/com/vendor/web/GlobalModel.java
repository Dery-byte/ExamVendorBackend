package com.vendor.web;

import com.vendor.service.KeySigner;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.NoSuchElementException;

/** Shared by every page: who is signed in, whether keys can be signed; and the "not found" page. */
@ControllerAdvice(assignableTypes = {PageController.class, ClientController.class, PayController.class, LoginController.class, RevenueController.class})
public class GlobalModel {

    private final KeySigner signer;
    private final Fmt fmt;

    public GlobalModel(KeySigner signer, Fmt fmt) {
        this.signer = signer;
        this.fmt = fmt;
    }

    @ModelAttribute
    public void common(Model model) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        model.addAttribute("me", a == null ? null : a.getName());
        model.addAttribute("signerAvailable", signer.available());
        model.addAttribute("fmt", fmt);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String notFound(Model model) {
        model.addAttribute("status", 404);
        model.addAttribute("message", "That page doesn't exist, or the client was removed.");
        return "error";
    }
}
