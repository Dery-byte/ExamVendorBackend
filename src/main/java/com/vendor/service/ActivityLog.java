package com.vendor.service;

import com.vendor.model.Activity;
import com.vendor.repository.ActivityRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Writes the audit trail. */
@Service
public class ActivityLog {

    private final ActivityRepository repository;

    public ActivityLog(ActivityRepository repository) {
        this.repository = repository;
    }

    public void record(Long clientId, String actor, String action, String details) {
        Activity a = new Activity();
        a.setAt(LocalDateTime.now().withNano(0));
        a.setClientId(clientId);
        a.setActor(actor);
        a.setAction(action);
        a.setDetails(details == null || details.length() <= 1000 ? details : details.substring(0, 999) + "…");
        repository.save(a);
    }
}
