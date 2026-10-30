package com.placementtracker.config;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.stereotype.Component;

import com.placementtracker.matching.CompanyNames;

/**
 * V2: recomputes every stored {@code company_key} after {@link CompanyNames#key} started folding accents and
 * dropping "and", so an existing "Nestlé" or "Marks and Spencer" still matches new emails. Registered with Flyway
 * as a bean by Spring Boot.
 */
@Component
public class RekeyCompanyNamesMigration implements JavaMigration {

	@Override
	public MigrationVersion getVersion() {
		return MigrationVersion.fromVersion("2");
	}

	@Override
	public String getDescription() {
		return "rekey company names";
	}

	@Override
	public Integer getChecksum() {
		return null;
	}

	@Override
	public boolean canExecuteInTransaction() {
		return true;
	}

	@Override
	public void migrate(Context context) throws SQLException {
		rekey(context.getConnection());
	}

	static void rekey(Connection connection) throws SQLException {
		Map<Long, String> keys = new LinkedHashMap<>();
		try (Statement s = connection.createStatement();
				ResultSet rs = s.executeQuery("SELECT id, company FROM applications")) {
			while (rs.next()) {
				keys.put(rs.getLong(1), CompanyNames.key(rs.getString(2)));
			}
		}
		try (PreparedStatement update = connection
				.prepareStatement("UPDATE applications SET company_key = ? WHERE id = ?")) {
			for (Map.Entry<Long, String> e : keys.entrySet()) {
				update.setString(1, e.getValue());
				update.setLong(2, e.getKey());
				update.executeUpdate();
			}
		}
	}
}
