package com.placementtracker.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DatabaseConfig {

	@Bean
	public DataSource dataSource(DataDir dataDir) {
		SQLiteDataSource ds = new SQLiteDataSource();
		ds.setUrl("jdbc:sqlite:" + dataDir.resolve("tracker.db"));
		return ds;
	}
}
