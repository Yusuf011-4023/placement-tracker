package com.placementtracker.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.placementtracker.application.Stage;
import com.placementtracker.classify.Relevance;
import com.placementtracker.mail.MailMessage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** One labelled email in the classification evaluation set. Company and stage are null for NO cases. */
public record EvalCase(String id, MailMessage email, Relevance expectedRelevance, String expectedCompany,
		Stage expectedStage) {

	private static final Path SYNTHETIC = Path.of("src/test/resources/eval/synthetic");
	/** Git-ignored; the owner may drop real, labelled emails here in the same JSON shape. */
	private static final Path PRIVATE = Path.of("src/test/resources/eval/private");

	/** Loads every {@code *.json} file (each an array of cases) from the synthetic and private directories. */
	public static List<EvalCase> loadAll() {
		JsonMapper mapper = JsonMapper.builder().build();
		List<EvalCase> all = new ArrayList<>();
		for (Path dir : List.of(SYNTHETIC, PRIVATE)) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> files = Files.list(dir)) {
				for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
					all.addAll(mapper.readValue(Files.readString(f), new TypeReference<List<EvalCase>>() {
					}));
				}
			}
			catch (IOException e) {
				throw new UncheckedIOException("Cannot read eval cases from " + dir, e);
			}
		}
		return all;
	}
}
