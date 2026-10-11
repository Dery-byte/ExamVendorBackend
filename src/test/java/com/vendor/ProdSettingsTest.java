package com.vendor;

import com.vendor.config.StartupCheck;
import com.vendor.service.KeySigner;
import com.vendor.service.Mailer;
import com.vendor.service.PaystackClient;
import com.vendor.service.InvoiceService;
import com.vendor.model.Invoice;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The server settings (prod profile), as on Render. */
class ProdSettingsTest {

    /** Render sets RENDER_EXTERNAL_URL; with no PUBLIC_URL the portal uses it. */
    @Nested
    @SpringBootTest(properties = {
            "DB_URL=jdbc:h2:mem:prodsettings;MODE=MySQL;DB_CLOSE_DELAY=-1", "DB_USERNAME=sa", "DB_PASSWORD=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "RENDER_EXTERNAL_URL=https://vendor-portal.onrender.com",
            "MAILJET_SMTP_USERNAME=key", "MAILJET_SMTP_PASSWORD=secret",
            "app.billing.renewal-cron=-", "PAYSTACK_SECRET_KEY=", "VENDOR_ADMIN_EMAIL=",
    })
    @ActiveProfiles("prod")
    class OnRender {
        @Autowired InvoiceService invoices;

        @Test
        void startsAndUsesRendersAddress() {
            Invoice i = new Invoice();
            i.setPayToken("t");
            assertThat(invoices.payUrl(i)).isEqualTo("https://vendor-portal.onrender.com/pay/t");
        }
    }

    @Test
    void withoutAnyAddressTheServerStopsWithAPlainMessage() throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        StartupCheck check = new StartupCheck(env, new KeySigner(""),
                new Mailer("key", "secret", "https://api.mailjet.com", "codes@example.com", "Vendor portal"),
                new PaystackClient("", "https://api.paystack.co"));
        ReflectionTestUtils.setField(check, "publicUrl", "");
        ReflectionTestUtils.setField(check, "adminEmail", "");
        Method m = StartupCheck.class.getDeclaredMethod("check");
        m.setAccessible(true);
        assertThatThrownBy(() -> {
            try { m.invoke(check); } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
        }).hasMessageContaining("PUBLIC_URL").hasMessageContaining("environment variables");
    }

    @Test
    void theSigningKeyIsAcceptedHowEverItWasPasted() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded());
        assertThat(new KeySigner(b64).available()).isTrue();
        assertThat(new KeySigner("\"" + b64 + "\"").available()).isTrue();
        assertThat(new KeySigner("-----BEGIN PRIVATE KEY-----\n" + b64.substring(0, 30) + "\n" + b64.substring(30) + "\n-----END PRIVATE KEY-----").available()).isTrue();
        assertThatThrownBy(() -> new KeySigner("not a key")).hasMessageContaining("MC4CAQAw");
    }
}
