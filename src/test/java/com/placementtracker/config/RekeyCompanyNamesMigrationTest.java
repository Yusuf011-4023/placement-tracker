package com.placementtracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RekeyCompanyNamesMigrationTest {

	@Test
	void storedKeysAreRecomputedWithTheCurrentRules(@TempDir Path dir) throws Exception {
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("t.db"))) {
			try (Statement s = c.createStatement()) {
				s.execute("CREATE TABLE applications (id INTEGER PRIMARY KEY, company TEXT NOT NULL, "
						+ "company_key TEXT NOT NULL)");
				// keys as the old rules stored them
				s.execute("INSERT INTO applications (id, company, company_key) VALUES (1, 'Nestlé', 'nestlé'), "
						+ "(2, 'Marks and Spencer', 'marks and spencer'), (3, 'Acme Ltd', 'acme')");
			}

			RekeyCompanyNamesMigration.rekey(c);

			try (Statement s = c.createStatement();
					ResultSet rs = s.executeQuery("SELECT company_key FROM applications ORDER BY id")) {
				rs.next();
				assertThat(rs.getString(1)).isEqualTo("nestle");
				rs.next();
				assertThat(rs.getString(1)).isEqualTo("marks spencer");
				rs.next();
				assertThat(rs.getString(1)).isEqualTo("acme");
			}
		}
	}
}
