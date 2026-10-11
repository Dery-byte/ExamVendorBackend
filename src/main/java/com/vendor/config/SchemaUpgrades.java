package com.vendor.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Changes ddl-auto=update can't make, applied once at start-up and skipped when already done.
 * Sign-in moved from passwords to emailed codes, so vendor_user.password_hash (NOT NULL) is dropped:
 * otherwise rows added by hand, without a password, would be refused.
 */
@Component
@Order(0)
public class SchemaUpgrades implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaUpgrades.class);
    private final DataSource dataSource;

    public SchemaUpgrades(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        dropColumn("vendor_user", "password_hash");
    }

    private void dropColumn(String table, String column) {
        try (Connection c = dataSource.getConnection()) {
            if (!hasColumn(c, table, column) && !hasColumn(c, table.toUpperCase(), column.toUpperCase())) return;
            try (Statement s = c.createStatement()) {
                s.execute("ALTER TABLE " + table + " DROP COLUMN " + column);
            }
            log.info("[SCHEMA] Dropped {}.{}", table, column);
        } catch (Exception e) {
            log.error("[SCHEMA] Could not drop {}.{}: {}", table, column, e.getMessage());
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws Exception {
        try (ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }
}
