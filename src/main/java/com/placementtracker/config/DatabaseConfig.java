package com.placementtracker.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DatabaseConfig {

	@Bean
	public DataSource dataSource(DataDir dataDir) {
		SQLiteConfig config = new SQLiteConfig();
		config.setBusyTimeout(5000);
		config.setJournalMode(SQLiteConfig.JournalMode.WAL);
		SQLiteDataSource ds = new SQLiteDataSource(config);
		ds.setUrl("jdbc:sqlite:" + dataDir.resolve("tracker.db"));
		return ds;
	}
}
