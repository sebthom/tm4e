/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * - Sebastian Thomschke (Vegard IT) - initial implementation
 */
package org.eclipse.tm4e.ui.tests;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.core.TMException;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Verifies suggestion persistence, isolation, save failures, overlapping edits, import order and cache updates through edit sessions.
 * Each test removes its own imports and temporary files without changing other registered grammars.
 */
class GrammarRegistryImportTest {

	/** Makes the import-order regression independent of the JVM's object identity hash codes. */
	private static final class TestGrammarDefinition extends GrammarDefinition {
		private final transient int hashCode;

		TestGrammarDefinition(final String scope, final String path, final int hashCode) {
			super(scope, path);
			this.hashCode = hashCode;
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(final @Nullable Object other) {
			// Preserve identity equality; the chosen hash only controls test iteration order.
			return this == other;
		}
	}

	private final IGrammarRegistryManager registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
	private final List<IGrammarDefinition> grammars = new ArrayList<>();
	private final List<Path> files = new ArrayList<>();

	@AfterEach
	void cleanUp() throws Exception {
		final var session = registry.newEditSession();
		for (final var grammar : grammars) {
			session.unregisterGrammarDefinition(grammar);
		}
		session.save();
		for (final var file : files) {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void cancelledAdditionDoesNotChangeOtherSessionsOrTheLiveRegistry() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var first = createGrammar(scope, "first");
		saveImport(first);
		final var session = registry.newEditSession();
		final var otherSession = registry.newEditSession();
		final var second = createGrammar(scope, "second");

		session.registerGrammarDefinition(second);
		assertDefinitions(session, scope, first, second);
		assertDefinitions(registry, scope, first);
		assertDefinitions(otherSession, scope, first);

		session.reset();
		assertDefinitions(session, scope, first);
	}

	@Test
	void cancelledRemovalDoesNotChangeOtherSessionsOrTheLiveRegistry() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "original");
		saveImport(grammar);
		final var session = registry.newEditSession();
		final var otherSession = registry.newEditSession();

		session.unregisterGrammarDefinition(grammar);
		assertDefinitions(session, scope);
		assertDefinitions(registry, scope, grammar);
		assertDefinitions(otherSession, scope, grammar);

		session.reset();
		assertDefinitions(session, scope, grammar);
	}

	@Test
	void matchingFilesPersistIncludingAnExplicitEmptyList() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "matching-files");
		final var session = registry.newEditSession();
		session.importGrammar(grammar, null, List.of("*.m", "Makefile"));

		assertThat(session.getSavedGrammarFileAssociations(grammar)).containsExactly("*.m", "Makefile");
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).isNull();
		session.save();
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).containsExactly("*.m", "Makefile");
		assertThat(reloadRegistry().getSavedGrammarFileAssociations(grammar)).containsExactly("*.m", "Makefile");

		final var clear = registry.newEditSession();
		clear.importGrammar(grammar, null, List.of());
		clear.save();
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).isEmpty();
		assertThat(reloadRegistry().getSavedGrammarFileAssociations(grammar)).isEmpty();
	}

	@Test
	void resettingMatchingFilesRestoresTheSavedValue() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "matching-files-reset");
		final var saved = registry.newEditSession();
		saved.importGrammar(grammar, null, List.of("*.saved"));
		saved.save();
		final var session = registry.newEditSession();
		session.importGrammar(grammar, null, List.of("*.cancelled"));
		assertThat(session.getSavedGrammarFileAssociations(grammar)).containsExactly("*.cancelled");

		session.reset();
		assertThat(session.getSavedGrammarFileAssociations(grammar)).containsExactly("*.saved");
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).containsExactly("*.saved");
	}

	@Test
	void staleSessionCannotOverwriteMatchingFilesSavedByAnotherSession() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "matching-files-stale");
		final var saved = registry.newEditSession();
		saved.importGrammar(grammar, null, List.of("*.original"));
		saved.save();
		final var first = registry.newEditSession();
		final var stale = registry.newEditSession();
		first.importGrammar(grammar, null, List.of("*.first"));
		stale.importGrammar(grammar, null, List.of("*.stale"));

		first.save();
		assertThatThrownBy(stale::save).isInstanceOf(BackingStoreException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).containsExactly("*.first");
		assertThat(reloadRegistry().getSavedGrammarFileAssociations(grammar)).containsExactly("*.first");
	}

	@Test
	void matchingFileEditFailsWhenAnotherSessionRemovesTheGrammar() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "matching-files-concurrent-removal");
		saveImport(grammar);
		final var stale = registry.newEditSession();
		stale.importGrammar(grammar, null, List.of("*.stale"));
		final var remove = registry.newEditSession();
		remove.unregisterGrammarDefinition(grammar);
		remove.save();

		assertThatThrownBy(stale::save).isInstanceOf(BackingStoreException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
		assertThat(registry.getDefinitions()).doesNotContain(grammar);
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).isNull();
	}

	@Test
	void matchingFileEditMayBeDiscardedByRemovingTheGrammarInTheSameSession() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "matching-files-local-removal");
		saveImport(grammar);
		final var session = registry.newEditSession();
		session.importGrammar(grammar, null, List.of("*.discarded"));
		session.unregisterGrammarDefinition(grammar);

		session.save();
		assertThat(registry.getDefinitions()).doesNotContain(grammar);
		assertThat(registry.getSavedGrammarFileAssociations(grammar)).isNull();
	}

	@Test
	void removingAnImportRemovesItsMatchingFiles() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "matching-files-removal");
		final var saved = registry.newEditSession();
		saved.importGrammar(grammar, null, List.of("*.removed"));
		saved.save();
		final var remove = registry.newEditSession();
		remove.unregisterGrammarDefinition(grammar);
		remove.save();

		assertThat(registry.getSavedGrammarFileAssociations(grammar)).isNull();
		assertThat(reloadRegistry().getSavedGrammarFileAssociations(grammar)).isNull();
	}

	@Test
	void savingKeepsTheFirstImportForASharedScope() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		// These hash codes make an unordered set save the second import first.
		final var first = createGrammar(scope, "first", 1);
		final var second = createGrammar(scope, "second", 0);
		final var session = registry.newEditSession();
		session.registerGrammarDefinition(first);
		session.registerGrammarDefinition(second);
		assertGrammarName(session, first.getScope(), "first");

		session.save();
		assertDefinitions(registry, scope, first, second);
		assertGrammarName(registry, first.getScope(), "first");
		assertGrammarName(reloadRegistry(), first.getScope(), "first");
	}

	@Test
	void overlappingImportsOfTheSameFileKeepTheSavedEntryAndItsPriority() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var first = createGrammar(scope, "first");
		final var other = createGrammar(scope, "other");
		final var duplicate = new GrammarDefinition(scope, first.getPath());
		grammars.add(duplicate);
		final var firstSession = registry.newEditSession();
		final var secondSession = registry.newEditSession();
		firstSession.registerGrammarDefinition(first);
		firstSession.registerGrammarDefinition(other);
		secondSession.registerGrammarDefinition(duplicate);

		firstSession.save();
		secondSession.save();
		assertDefinitions(registry, scope, first, other);
		assertDefinitions(secondSession, scope, first, other);
		assertThat(reloadRegistry().getDefinitions()).filteredOn(definition -> scope.equals(definition.getScope().getName()))
				.extracting(IGrammarDefinition::getURI).containsExactly(first.getURI(), other.getURI());
	}

	@Test
	void savingAnOlderSessionPreservesOtherSavedAdditionsAndRemovals() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var original = createGrammar(scope, "original");
		saveImport(original);
		final var first = createGrammar(scope, "first");
		final var second = createGrammar(scope, "second");
		final var firstSession = registry.newEditSession();
		final var secondSession = registry.newEditSession();
		firstSession.unregisterGrammarDefinition(original);
		firstSession.registerGrammarDefinition(first);
		secondSession.registerGrammarDefinition(second);

		firstSession.save();
		secondSession.save();
		assertDefinitions(registry, scope, first, second);
		assertDefinitions(secondSession, scope, first, second);
		assertThat(reloadRegistry().getDefinitions()).filteredOn(definition -> scope.equals(definition.getScope().getName()))
				.extracting(IGrammarDefinition::getURI).containsExactly(first.getURI(), second.getURI());
	}

	@Test
	void conflictingScopeFromAnotherSessionDoesNotPublishPartialEdits() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var original = createGrammar(scope, "original");
		saveImport(original);
		final var first = createGrammar(scope, "first");
		final var pending = createGrammar(scope, "pending");
		final var conflicting = new GrammarDefinition(scope + ".changed", first.getPath());
		grammars.add(conflicting);
		final var firstSession = registry.newEditSession();
		final var secondSession = registry.newEditSession();
		firstSession.registerGrammarDefinition(first);
		secondSession.unregisterGrammarDefinition(original);
		secondSession.registerGrammarDefinition(pending);
		secondSession.registerGrammarDefinition(conflicting);
		firstSession.save();
		final var prefs = InstanceScope.INSTANCE.getNode(TMEclipseRegistryPlugin.PLUGIN_ID);
		final var savedGrammars = prefs.get("org.eclipse.tm4e.registry.grammars", null);

		assertThatThrownBy(secondSession::save).isInstanceOf(BackingStoreException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
		assertDefinitions(registry, scope, original, first);
		assertDefinitions(registry, conflicting.getScope().getName());
		assertThat(prefs.get("org.eclipse.tm4e.registry.grammars", null)).isEqualTo(savedGrammars);
		// The rejected session keeps its edits so the user can remove the conflicting import and retry.
		secondSession.unregisterGrammarDefinition(conflicting);
		secondSession.save();
		assertDefinitions(registry, scope, first, pending);
	}

	@Test
	void removingAndReimportingTheSameFileCanChangeItsScope() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var original = createGrammar(scope, "original");
		saveImport(original);
		final var replacement = new GrammarDefinition(scope + ".changed", original.getPath());
		grammars.add(replacement);
		final var session = registry.newEditSession();
		session.unregisterGrammarDefinition(original);
		session.registerGrammarDefinition(replacement);
		session.save();
		assertDefinitions(registry, scope);
		assertDefinitions(registry, replacement.getScope().getName(), replacement);
		assertThat(reloadRegistry().getDefinitions()).filteredOn(definition -> definition.getURI().equals(original.getURI()))
				.extracting(definition -> definition.getScope().getName()).containsExactly(replacement.getScope().getName());
	}

	@Test
	void failedSavePreservesRegistryAndPreferencesAndCanBeRetried() throws Exception {
		assertFailedSaveCanBeRetried(true);
	}

	@Test
	void failedSaveRestoresAnAbsentGrammarPreference() throws Exception {
		assertFailedSaveCanBeRetried(false);
	}

	private void assertFailedSaveCanBeRetried(final boolean keepPreviousPreference) throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var original = createGrammar(scope, "original");
		saveImport(original);
		final var replacement = createGrammar(scope, "replacement");
		final var session = registry.newEditSession();
		session.unregisterGrammarDefinition(original);
		session.registerGrammarDefinition(replacement);
		final var prefs = InstanceScope.INSTANCE.getNode(TMEclipseRegistryPlugin.PLUGIN_ID);
		final var grammarKey = "org.eclipse.tm4e.registry.grammars";
		final var markerKey = "tm4e.test.saveFailure." + UUID.randomUUID();
		final var preferenceFile = Platform.getStateLocation(Platform.getBundle("org.eclipse.core.runtime"))
				.append(".settings").append(TMEclipseRegistryPlugin.PLUGIN_ID + ".prefs").toFile().toPath();
		try {
			if (!keepPreviousPreference)
				prefs.remove(grammarKey);
			// Keep a backing file even when the grammar preference was absent.
			prefs.put(markerKey, "test");
			prefs.flush();
			final var previousValue = prefs.get(grammarKey, null);
			final var previousContents = Files.readString(preferenceFile);
			// Eclipse writes an existing preference file through .bak. A directory there fails on all platforms,
			// even when the tests run with permission to write read-only files.
			final var blocker = Files.createDirectory(preferenceFile.resolveSibling(preferenceFile.getFileName() + ".bak"));
			try {
				assertThatThrownBy(session::save).isInstanceOf(BackingStoreException.class)
						.satisfies(ex -> assertThat(ex.getSuppressed()).singleElement().isInstanceOf(BackingStoreException.class));
				assertDefinitions(registry, scope, original);
				assertThat(prefs.get(grammarKey, null)).isEqualTo(previousValue);
				assertThat(Files.readString(preferenceFile)).isEqualTo(previousContents);
			} finally {
				Files.delete(blocker);
			}
			session.save();
			assertDefinitions(registry, scope, replacement);
			assertThat(reloadRegistry().getDefinitions()).filteredOn(definition -> scope.equals(definition.getScope().getName()))
					.extracting(IGrammarDefinition::getURI).containsExactly(replacement.getURI());
			session.save();
			assertDefinitions(registry, scope, replacement);
		} finally {
			prefs.remove(markerKey);
			prefs.flush();
		}
	}

	@Test
	void replacingALoadedImportReloadsAnOlderSource() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var first = createGrammar(scope, "original");
		saveImport(first);
		assertGrammarName(registry, first.getScope(), "original");
		final var replacement = createGrammar(scope, "replacement");
		// The source changed even though its timestamp is older. No reload delay should be necessary.
		Files.setLastModifiedTime(Path.of(replacement.getPath()), FileTime.fromMillis(first.getLastModified() - 60_000));
		final var session = registry.newEditSession();
		session.registerGrammarDefinition(replacement);
		session.unregisterGrammarDefinition(first);

		session.save();
		assertDefinitions(registry, scope, replacement);
		assertGrammarName(registry, first.getScope(), "replacement");
	}

	@Test
	void replacingALoadedImportLoadsNewDependencies() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var original = createGrammar(scope, "original");
		saveImport(original);
		assertGrammarName(registry, original.getScope(), "original");

		final var dependencyScope = scope + ".dependency";
		final var dependency = createGrammar(dependencyScope, "dependency",
				"{\"match\":\"x\",\"name\":\"keyword.tm4e-import-test\"}");
		final var replacement = createGrammar(scope, "replacement", "{\"include\":\"" + dependencyScope + "\"}");
		final var session = registry.newEditSession();
		// Register the dependency without loading it. A direct lookup would hide a missing dependency load in the replacement.
		session.registerGrammarDefinition(dependency);
		session.registerGrammarDefinition(replacement);
		session.unregisterGrammarDefinition(original);
		session.save();

		assertThat(registry.getGrammarForScope(replacement.getScope())).isNotNull()
				.extracting(grammar -> grammar.tokenizeLine("x").getTokens()[0].getScopes())
				.isEqualTo(List.of(scope, "keyword.tm4e-import-test"));
	}

	@Test
	void replacingALoadedImportRetriesAfterADependencyLoadFails() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var original = createGrammar(scope, "original");
		saveImport(original);
		assertGrammarName(registry, original.getScope(), "original");

		final var dependencyScope = scope + ".dependency";
		final var dependency = createGrammar(dependencyScope, "dependency",
				"{\"match\":\"x\",\"name\":\"keyword.tm4e-import-test\"}");
		final var dependencyFile = Path.of(dependency.getPath());
		final var validDependency = Files.readString(dependencyFile);
		Files.writeString(dependencyFile, "{invalid}");
		final var replacement = createGrammar(scope, "replacement", "{\"include\":\"" + dependencyScope + "\"}");
		final var session = registry.newEditSession();
		session.registerGrammarDefinition(dependency);
		session.registerGrammarDefinition(replacement);
		session.unregisterGrammarDefinition(original);
		session.save();

		assertThatThrownBy(() -> registry.getGrammarForScope(replacement.getScope())).isInstanceOf(TMException.class);
		// Keep the dependency invalid for a retry, so cleanup is checked for both the reload and the retry.
		assertThatThrownBy(() -> registry.getGrammarForScope(replacement.getScope())).isInstanceOf(TMException.class);
		// Correct only the dependency. The unchanged root must not bypass the failed dependency load on the next lookup.
		Files.writeString(dependencyFile, validDependency);
		assertThat(registry.getGrammarForScope(replacement.getScope())).isNotNull()
				.extracting(grammar -> grammar.tokenizeLine("x").getTokens()[0].getScopes())
				.isEqualTo(List.of(scope, "keyword.tm4e-import-test"));
	}

	@Test
	void removingALoadedImportDoesNotReturnItsCachedGrammar() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "original");
		saveImport(grammar);
		assertGrammarName(registry, grammar.getScope(), "original");
		final var session = registry.newEditSession();
		session.unregisterGrammarDefinition(grammar);

		session.save();
		assertThat(registry.getGrammarForScope(grammar.getScope())).isNull();
	}

	@Test
	void importingAfterAnUnsuccessfulLookupLoadsTheGrammar() throws Exception {
		final var scope = "source.tm4e-import-test-" + UUID.randomUUID();
		final var grammar = createGrammar(scope, "imported");
		assertThat(registry.getGrammarForScope(grammar.getScope())).isNull();

		saveImport(grammar);
		assertGrammarName(registry, grammar.getScope(), "imported");
	}

	private void saveImport(final IGrammarDefinition grammar) throws Exception {
		final var session = registry.newEditSession();
		session.registerGrammarDefinition(grammar);
		session.save();
	}

	private static void assertDefinitions(final IGrammarRegistryManager registry, final String scope,
			final IGrammarDefinition... expected) {
		assertThat(registry.getDefinitions()).filteredOn(definition -> scope.equals(definition.getScope().getName()))
				.containsExactly(expected);
	}

	private static void assertGrammarName(final IGrammarRegistryManager registry, final ITMScope scope, final String expected) {
		assertThat(registry.getGrammarForScope(scope)).isNotNull().extracting(IGrammar::getName).isEqualTo(expected);
	}

	private IGrammarRegistryManager reloadRegistry() throws Exception {
		// Read the saved preferences through a separate instance, leaving the shared registry used by other tests intact.
		final var implementation = registry.getClass();
		final var constructor = implementation.getDeclaredConstructor();
		constructor.setAccessible(true);
		final var restored = constructor.newInstance();
		final var load = implementation.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(restored);
		return restored;
	}

	private IGrammarDefinition createGrammar(final String scope, final String name, final String... patterns) throws Exception {
		return createGrammar(scope, name, grammars.size(), patterns);
	}

	private IGrammarDefinition createGrammar(final String scope, final String name, final int hashCode, final String... patterns)
			throws Exception {
		final var file = Files.createTempFile("tm4e-grammar-import-", ".json");
		files.add(file);
		Files.writeString(file, "{\"scopeName\":\"" + scope + "\",\"name\":\"" + name + "\",\"patterns\":["
				+ String.join(",", patterns) + "]}");
		final var grammar = new TestGrammarDefinition(scope, file.toString(), hashCode);
		grammars.add(grammar);
		return grammar;
	}
}
