package com.vendor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The vendor's own portal: the list of client institutions, their payments and subscription keys.
 * Each client's exam portal checks in here and picks up its latest signed key automatically.
 */
@SpringBootApplication
@EnableScheduling   // daily renewal invoices
public class VendorPortalApplication {

    public static void main(String[] args) {
        SpringApplication.run(VendorPortalApplication.class, args);
    }
}
