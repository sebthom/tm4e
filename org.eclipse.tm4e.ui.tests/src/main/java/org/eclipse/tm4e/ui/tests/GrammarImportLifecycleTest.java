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
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferencePage;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.tm4e.core.TMException;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.ui.IImportWizard;
import org.junit.jupiter.api.Test;

/** Verifies import retries, shared edit sessions, missing-file recovery and workspace content-type compatibility. */
class GrammarImportLifecycleTest {

	@Test
	void standaloneImportWizardCanBeCreatedFromItsExtension() throws Exception {
		createImportWizard().dispose();
	}

	@Test
	void reimportingAFileWithAChangedScopeShowsAWizardError() throws Exception {
		final var file = createGrammar();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var original = new GrammarDefinition("source.test", file.toString());
		try {
			final var session = registry.newEditSession();
			session.registerGrammarDefinition(original);
			session.save();
			Files.writeString(file, "{\"scopeName\":\"source.changed\",\"patterns\":[]}");
			final var wizard = createImportWizard();
			final var shell = new Shell();
			try {
				wizard.addPages();
				final var page = wizard.getPages()[0];
				page.createControl(shell);
				// Set the private input to exercise real page validation without adding a test-only wizard API.
				final var fileInput = page.getClass().getDeclaredField("grammarFileText");
				fileInput.setAccessible(true);
				((Text) fileInput.get(page)).setText(file.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();
				assertThat(wizard.performFinish()).isFalse();
				assertThat(page.getErrorMessage()).contains("different scope", "Remove the old import first");
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(file.toUri())).containsExactly(original);
			} finally {
				wizard.dispose();
				shell.dispose();
			}
		} finally {
			final var cleanup = registry.newEditSession();
			cleanup.unregisterGrammarDefinition(original);
			cleanup.save();
			Files.deleteIfExists(file);
		}
	}

	@Test
	void overlappingImportShowsWizardErrorAndClearsItAfterRetry() throws Exception {
		final var file = createGrammar();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var competing = new GrammarDefinition("source.changed", file.toString());
		try {
			final var wizard = createImportWizard();
			final var shell = new Shell();
			try {
				wizard.addPages();
				final var page = wizard.getPages()[0];
				page.createControl(shell);
				// Exercise real page validation without exposing the wizard's private input as a test API.
				final var fileInput = page.getClass().getDeclaredField("grammarFileText");
				fileInput.setAccessible(true);
				((Text) fileInput.get(page)).setText(file.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();

				// The wizard already validated the old scope. Finish must check it against the latest saved imports.
				Files.writeString(file, "{\"scopeName\":\"source.changed\",\"patterns\":[]}");
				final var otherSession = registry.newEditSession();
				otherSession.registerGrammarDefinition(competing);
				otherSession.save();
				assertThat(wizard.performFinish()).isFalse();
				assertThat(page.getErrorMessage()).contains("different scope");
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(file.toUri())).containsExactly(competing);

				otherSession.unregisterGrammarDefinition(competing);
				otherSession.save();
				Files.writeString(file, "{\"scopeName\":\"source.test\",\"patterns\":[]}");
				// Keep the input untouched: revalidating it would clear the error and hide a stale save message.
				assertThat(wizard.performFinish()).isTrue();
				assertThat(page.getErrorMessage()).isNull();
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(file.toUri()))
						.extracting(def -> def.getScope().getName()).containsExactly("source.test");
			} finally {
				wizard.dispose();
				shell.dispose();
			}
		} finally {
			removeImportsFor(file);
			Files.deleteIfExists(file);
		}
	}

	@Test
	void selectingAnotherGrammarAfterAConflictKeepsTheCompetingImport() throws Exception {
		final var firstFile = createGrammar();
		final var secondFile = createGrammar();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var competing = new GrammarDefinition("source.changed", firstFile.toString());
		try {
			final var wizard = createImportWizard();
			final var shell = new Shell();
			try {
				wizard.addPages();
				final var page = wizard.getPages()[0];
				page.createControl(shell);
				final var fileInput = page.getClass().getDeclaredField("grammarFileText");
				fileInput.setAccessible(true);
				final var input = (Text) fileInput.get(page);
				input.setText(firstFile.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();
				Files.writeString(firstFile, "{\"scopeName\":\"source.changed\",\"patterns\":[]}");
				final var otherSession = registry.newEditSession();
				otherSession.registerGrammarDefinition(competing);
				otherSession.save();
				assertThat(wizard.performFinish()).isFalse();
				assertThat(page.getErrorMessage()).contains("different scope");

				// Leave the competing import in place: selecting a different file must discard the rejected attempt.
				input.setText(secondFile.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();
				assertThat(wizard.performFinish()).isTrue();
				assertThat(page.getErrorMessage()).isNull();
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(firstFile.toUri())).containsExactly(competing);
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(secondFile.toUri()))
						.extracting(def -> def.getScope().getName()).containsExactly("source.test");
			} finally {
				wizard.dispose();
				shell.dispose();
			}
		} finally {
			removeImportsFor(firstFile);
			removeImportsFor(secondFile);
			Files.deleteIfExists(firstFile);
			Files.deleteIfExists(secondFile);
		}
	}

	@Test
	void retryAfterAStorageFailureImportsOnlyTheCurrentSelection() throws Exception {
		final var firstFile = createGrammar();
		final var secondFile = createGrammar();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var prefs = InstanceScope.INSTANCE.getNode(TMEclipseRegistryPlugin.PLUGIN_ID);
		final var markerKey = "tm4e.test.wizardSaveFailure." + UUID.randomUUID();
		final var preferenceFile = Platform.getStateLocation(Platform.getBundle("org.eclipse.core.runtime"))
				.append(".settings").append(TMEclipseRegistryPlugin.PLUGIN_ID + ".prefs").toFile().toPath();
		try {
			final var wizard = createImportWizard();
			final var shell = new Shell();
			try {
				wizard.addPages();
				final var page = wizard.getPages()[0];
				page.createControl(shell);
				final var fileInput = page.getClass().getDeclaredField("grammarFileText");
				fileInput.setAccessible(true);
				final var input = (Text) fileInput.get(page);
				input.setText(firstFile.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();
				prefs.put(markerKey, "test");
				prefs.flush();
				// Fail the real persistence step, independently of when import conflicts are detected.
				// Eclipse writes existing preferences through .bak; a directory blocks that write on all platforms.
				final var blocker = Files.createDirectory(preferenceFile.resolveSibling(preferenceFile.getFileName() + ".bak"));
				try {
					assertThat(wizard.performFinish()).isFalse();
					assertThat(page.getErrorMessage()).isNotBlank();
					assertThat(registry.getDefinitions()).noneMatch(def -> def.getURI().equals(firstFile.toUri()));
				} finally {
					Files.delete(blocker);
				}
				input.setText(secondFile.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();
				assertThat(wizard.performFinish()).isTrue();
				assertThat(page.getErrorMessage()).isNull();
				assertThat(registry.getDefinitions()).noneMatch(def -> def.getURI().equals(firstFile.toUri()));
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(secondFile.toUri()))
						.extracting(def -> def.getScope().getName()).containsExactly("source.test");
			} finally {
				wizard.dispose();
				shell.dispose();
			}
		} finally {
			prefs.remove(markerKey);
			removeImportsFor(firstFile);
			removeImportsFor(secondFile);
			prefs.flush();
			Files.deleteIfExists(firstFile);
			Files.deleteIfExists(secondFile);
		}
	}

	@Test
	void embeddedImportWizardPreservesTheCallersPendingEdits() throws Exception {
		final var firstFile = createGrammar();
		final var secondFile = createGrammar();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var session = registry.newEditSession();
		final var pending = new GrammarDefinition("source.test", firstFile.toString());
		session.registerGrammarDefinition(pending);
		try {
			// Access the caller-owned constructor without exporting the internal wizard package for tests.
			final var wizard = (IImportWizard) Platform.getBundle("org.eclipse.tm4e.ui")
					.loadClass("org.eclipse.tm4e.ui.internal.wizards.TextMateGrammarImportWizard")
					.getConstructor(IGrammarRegistryManager.EditSession.class, boolean.class).newInstance(session, false);
			final var shell = new Shell();
			try {
				wizard.addPages();
				final var page = wizard.getPages()[0];
				page.createControl(shell);
				final var fileInput = page.getClass().getDeclaredField("grammarFileText");
				fileInput.setAccessible(true);
				((Text) fileInput.get(page)).setText(secondFile.toString());
				disableWorkspaceSetup(page);
				assertThat(page.isPageComplete()).isTrue();
				assertThat(wizard.performFinish()).isTrue();
				assertThat(session.getDefinitions()).contains(pending);
				assertThat(session.getDefinitions()).filteredOn(def -> def.getURI().equals(secondFile.toUri())).hasSize(1);
				// The preference page still owns the commit; Finish must neither save nor reset its other edits.
				assertThat(registry.getDefinitions()).noneMatch(def -> def.getURI().equals(firstFile.toUri())
						|| def.getURI().equals(secondFile.toUri()));
				session.save();
				assertThat(registry.getDefinitions()).contains(pending);
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(secondFile.toUri())).hasSize(1);
			} finally {
				wizard.dispose();
				shell.dispose();
			}
		} finally {
			removeImportsFor(firstFile);
			removeImportsFor(secondFile);
			Files.deleteIfExists(firstFile);
			Files.deleteIfExists(secondFile);
		}
	}

	@Test
	void saveConflictShowsPreferencePageErrorAndClearsItAfterRetry() throws Exception {
		final var file = createGrammar();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var pending = new GrammarDefinition("source.test", file.toString());
		final var competing = new GrammarDefinition("source.changed", file.toString());
		try {
			final var page = createGrammarPreferencePage();
			final var shell = new Shell();
			try {
				page.createControl(shell);
				// Stage through the page's own session without widening the production API for tests.
				final var managerField = page.getClass().getDeclaredField("grammarManager");
				managerField.setAccessible(true);
				final var session = (IGrammarRegistryManager.EditSession) managerField.get(page);
				session.registerGrammarDefinition(pending);
				// Keep the page's snapshot stale so this conflict reaches the save-error handler.
				final var otherSession = registry.newEditSession();
				otherSession.registerGrammarDefinition(competing);
				otherSession.save();

				assertThat(page.performOk()).isFalse();
				assertThat(page.getErrorMessage()).contains("different scope");
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(file.toUri())).containsExactly(competing);
				otherSession.unregisterGrammarDefinition(competing);
				otherSession.save();
				assertThat(page.performOk()).isTrue();
				assertThat(page.getErrorMessage()).isNull();
				assertThat(registry.getDefinitions()).filteredOn(def -> def.getURI().equals(file.toUri())).containsExactly(pending);
			} finally {
				page.dispose();
				shell.dispose();
			}
		} finally {
			removeImportsFor(file);
			Files.deleteIfExists(file);
		}
	}

	@Test
	void reimportingTheSameFileDoesNotAddAnotherDefinition() throws Exception {
		final var file = createGrammar();
		try {
			final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
			final var scope = "source." + UUID.randomUUID();
			final var original = new GrammarDefinition(scope, file.toString());
			session.registerGrammarDefinition(original);
			session.registerGrammarDefinition(new GrammarDefinition(scope, file.toString()));
			assertThat(session.getDefinitions()).filteredOn(def -> def.getURI().equals(file.toUri())).containsExactly(original);
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void deletingALoadedFileStopsReturningItsCachedGrammarAndRestoringItRecovers() throws Exception {
		final var file = createGrammar();
		final var originalTimestamp = Files.getLastModifiedTime(file);
		try {
			final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
			final var definition = new GrammarDefinition("source." + UUID.randomUUID(), file.toString());
			session.registerGrammarDefinition(definition);
			assertThat(session.getGrammarForScope(definition.getScope())).isNotNull();
			Files.delete(file);
			// Exercise the real timestamp check after its throttle, rather than clearing the cache in the test.
			Thread.sleep(5100);
			assertThatThrownBy(() -> session.getGrammarForScope(definition.getScope())).isInstanceOf(TMException.class);
			Files.writeString(file, "{\"scopeName\":\"source.test\",\"name\":\"restored\",\"patterns\":[]}");
			// Restoring a backup may keep its old timestamp. A failed load must still force a fresh read.
			Files.setLastModifiedTime(file, originalTimestamp);
			assertThat(session.getGrammarForScope(definition.getScope())).isNotNull().extracting(IGrammar::getName).isEqualTo("restored");
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void restoringALoadedDependencyWithItsOldTimestampUsesItsNewRules() throws Exception {
		final var rootFile = createGrammar();
		final var dependencyFile = createGrammar();
		try {
			final var scope = "source." + UUID.randomUUID();
			final var dependencyScope = scope + ".dependency";
			Files.writeString(rootFile, "{\"scopeName\":\"" + scope + "\",\"patterns\":[{\"include\":\"" + dependencyScope + "\"}]}");
			Files.writeString(dependencyFile, "{\"scopeName\":\"" + dependencyScope
					+ "\",\"patterns\":[{\"match\":\"x\",\"name\":\"keyword.original\"}]}");
			final var originalTimestamp = Files.getLastModifiedTime(dependencyFile);
			final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
			final var root = new GrammarDefinition(scope, rootFile.toString());
			session.registerGrammarDefinition(root);
			session.registerGrammarDefinition(new GrammarDefinition(dependencyScope, dependencyFile.toString()));
			assertThat(session.getGrammarForScope(root.getScope())).isNotNull()
					.extracting(grammar -> grammar.tokenizeLine("x").getTokens()[0].getScopes())
					.isEqualTo(List.of(scope, "keyword.original"));

			Files.delete(dependencyFile);
			// A root reload checks its dependencies again. Wait for the real timestamp throttle without clearing their caches.
			Files.setLastModifiedTime(rootFile, FileTime.fromMillis(Files.getLastModifiedTime(rootFile).toMillis() + 60_000));
			Thread.sleep(5100);
			assertThatThrownBy(() -> session.getGrammarForScope(root.getScope())).isInstanceOf(TMException.class);
			Files.writeString(dependencyFile, "{\"scopeName\":\"" + dependencyScope
					+ "\",\"patterns\":[{\"match\":\"x\",\"name\":\"keyword.restored\"}]}");
			// No direct dependency lookup: recovery must work through the root even when a backup restores the old timestamp.
			Files.setLastModifiedTime(dependencyFile, originalTimestamp);
			assertThat(session.getGrammarForScope(root.getScope())).isNotNull()
					.extracting(grammar -> grammar.tokenizeLine("x").getTokens()[0].getScopes())
					.isEqualTo(List.of(scope, "keyword.restored"));
		} finally {
			Files.deleteIfExists(rootFile);
			Files.deleteIfExists(dependencyFile);
		}
	}

	@Test
	void missingImportDoesNotAbortExtensionFallback() throws Exception {
		final var missing = createGrammar();
		final var valid = createGrammar();
		try {
			final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
			final var extension = "tm4etest" + UUID.randomUUID().toString().replace("-", "");
			Files.writeString(valid, "{\"scopeName\":\"source.valid\",\"fileTypes\":[\"" + extension + "\"],\"patterns\":[]}");
			Files.delete(missing);
			session.registerGrammarDefinition(new GrammarDefinition("source." + UUID.randomUUID(), missing.toString()));
			// Contributed definitions follow all imports, independent of hash-map iteration order.
			// Only the contribution identity changes; the fixture still reads its temporary file from disk.
			session.registerGrammarDefinition(new GrammarDefinition("source." + UUID.randomUUID(), valid.toString()) {
				@Override
				public String getPluginId() {
					return "org.eclipse.tm4e.ui.tests";
				}
			});
			assertThat(session.getGrammarForFileExtension(extension)).isNotNull();
		} finally {
			Files.deleteIfExists(missing);
			Files.deleteIfExists(valid);
		}
	}

	private static IImportWizard createImportWizard() throws Exception {
		for (final var element : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.importWizards")) {
			if ("org.eclipse.tm4e.ui.wizards.TextMateGrammarWizard".equals(element.getAttribute("id"))) {
				return (IImportWizard) element.createExecutableExtension("class");
			}
		}
		throw new AssertionError("Grammar import wizard is not registered");
	}

	private static IPreferencePage createGrammarPreferencePage() throws Exception {
		// Use the extension to access the internal page without changing package visibility for tests.
		for (final var element : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.preferencePages")) {
			if ("org.eclipse.tm4e.ui.preferences.GrammarPreferencePage".equals(element.getAttribute("id"))) {
				return (IPreferencePage) element.createExecutableExtension("class");
			}
		}
		throw new AssertionError("Grammar preference page is not registered");
	}

	private static void removeImportsFor(final Path file) throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var cleanup = registry.newEditSession();
		// The surviving definition differs before and after a successful retry. Remove only this test's temporary source.
		for (final var definition : registry.getDefinitions()) {
			if (definition.getURI().equals(file.toUri())) {
				cleanup.unregisterGrammarDefinition(definition);
			}
		}
		cleanup.save();
	}

	@Test
	void loadingOldPreferencesPreservesManualTypesAndBindings() throws Exception {
		final var manager = Platform.getContentTypeManager();
		final var id = "org.eclipse.tm4e.tests.legacy." + UUID.randomUUID();
		final var type = manager.addContentType(id, "Manual language", manager.getContentType("org.eclipse.core.runtime.text"));
		final var file = createGrammar();
		final var preferences = InstanceScope.INSTANCE.getNode(TMEclipseRegistryPlugin.PLUGIN_ID);
		final var grammarKey = "org.eclipse.tm4e.registry.grammars";
		final var bindingKey = "org.eclipse.tm4e.registry.grammarBindings";
		final var previousGrammars = preferences.get(grammarKey, null);
		final var previousBindings = preferences.get(bindingKey, null);
		try {
			final var before = manager.getAllContentTypes();
			// This is the existing on-disk format, deliberately without any setup ownership information.
			preferences.put(grammarKey, "[{\"scopeName\":\"source.legacy\",\"path\":\""
					+ file.toString().replace("\\", "\\\\") + "\"}]");
			preferences.put(bindingKey, "{\"" + id + "\":\"source.legacy\"}");
			final var implementation = TMEclipseRegistryPlugin.getGrammarRegistryManager().getClass();
			final var constructor = implementation.getDeclaredConstructor();
			constructor.setAccessible(true);
			final var restored = (IGrammarRegistryManager) constructor.newInstance();
			final var load = implementation.getDeclaredMethod("load");
			load.setAccessible(true);
			load.invoke(restored);
			assertThat(restored.getUserGrammarBinding(type)).isNotNull().extracting(def -> def.getScope().getName())
					.isEqualTo("source.legacy");
			assertThat(manager.getAllContentTypes()).containsExactlyInAnyOrder(before);
		} finally {
			if (previousGrammars == null)
				preferences.remove(grammarKey);
			else
				preferences.put(grammarKey, previousGrammars);
			if (previousBindings == null)
				preferences.remove(bindingKey);
			else
				preferences.put(bindingKey, previousBindings);
			manager.removeContentType(id);
			Files.deleteIfExists(file);
		}
	}

	@Test
	void removingAContentTypeClearsItsFilenameMatches() throws Exception {
		final var manager = Platform.getContentTypeManager();
		final var id = "org.eclipse.tm4e.tests.import." + UUID.randomUUID();
		final var type = manager.addContentType(id, "Import cleanup", manager.getContentType("org.eclipse.core.runtime.text"));
		final var extension = "tm4ecleanup" + UUID.randomUUID();
		try {
			type.addFileSpec(extension, IContentType.FILE_EXTENSION_SPEC);
			assertThat(manager.findContentTypesFor("test." + extension)).extracting(IContentType::getId).contains(id);
			// Match import rollback: the platform's removal API alone leaves this match behind on Eclipse 4.32.
			type.removeFileSpec(extension, IContentType.FILE_EXTENSION_SPEC);
			manager.removeContentType(id);
			assertThat(manager.findContentTypesFor("test." + extension)).extracting(IContentType::getId).doesNotContain(id);
		} finally {
			// The baseline platform can retain the association even after removing the type.
			type.removeFileSpec(extension, IContentType.FILE_EXTENSION_SPEC);
			manager.removeContentType(id);
		}
	}

	private static void disableWorkspaceSetup(final IWizardPage page) throws Exception {
		// These tests exercise import-only retries; managed content-type setup has separate coverage.
		final var workspaceDefault = page.getClass().getDeclaredField("workspaceDefault");
		workspaceDefault.setAccessible(true);
		final var checkbox = (Button) workspaceDefault.get(page);
		checkbox.setSelection(false);
		checkbox.notifyListeners(SWT.Selection, new Event());
	}

	private static Path createGrammar() throws Exception {
		final var file = Files.createTempFile("tm4e-import-lifecycle-", ".json");
		Files.writeString(file, "{\"scopeName\":\"source.test\",\"patterns\":[]}");
		return file;
	}
}
