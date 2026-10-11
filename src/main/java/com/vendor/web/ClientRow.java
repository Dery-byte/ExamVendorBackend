package com.vendor.web;

import com.vendor.model.Client;
import com.vendor.model.IssuedKey;
import com.vendor.service.Stage;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** A client with what the pages need to know about it. */
public record ClientRow(Client client, Stage stage, long daysPaidLeft, boolean silent, IssuedKey latestKey) {

    public static ClientRow of(Client c, IssuedKey latest, int silentAfterHours) {
        LocalDate today = LocalDate.now();
        boolean silent = c.getLastCheckInAt() == null || c.getLastCheckInAt().isBefore(LocalDateTime.now().minusHours(silentAfterHours));
        return new ClientRow(c, Stage.of(c, today), Stage.daysPaidLeft(c, today), silent, latest);
    }

    /** The client's portal already holds the newest key. */
    public boolean keyDelivered() {
        return latestKey != null && latestKey.getKeyId().equals(client.getReportedKeyId());
    }

    /** The portal says one thing, these terms another (e.g. a key not yet picked up). */
    public boolean stateMismatch() {
        return client.getReportedState() != null && !client.getReportedState().equals(stage.name());
    }

    public boolean expiringSoon() {
        return stage == Stage.ACTIVE && daysPaidLeft <= 30;
    }

    public boolean needsAttention() {
        return stage != Stage.ACTIVE || expiringSoon() || silent || client.getReportedKeyProblem() != null;
    }
}
