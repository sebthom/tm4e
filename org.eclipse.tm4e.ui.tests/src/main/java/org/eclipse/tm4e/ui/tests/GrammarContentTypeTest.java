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
import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNonNull;
import static org.eclipse.tm4e.ui.tests.support.TestUtils.waitForAndAssertCondition;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.assertj.core.api.iterable.ThrowingExtractor;
import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.GrammarContentType;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.AbstractElementListSelectionDialog;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

/** Exercises managed import setup through edit sessions, Eclipse matching, wizards, and the preference page. */
class GrammarContentTypeTest {

	private final IGrammarRegistryManager registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
	private final List<IGrammarDefinition> definitions = new ArrayList<>();
	private final List<Path> files = new ArrayList<>();
	private final Set<String> typeIds = new HashSet<>();

	@AfterEach
	void cleanUp() throws Exception {
		final var session = registry.newEditSession();
		definitions.forEach(session::unregisterGrammarDefinition);
		session.save();
		for (final var id : typeIds) {
			removeTestType(id);
		}
		for (final var file : files) {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void setupIsDeferredAndMatchesNamesAndSuffixesWithoutClaimingTextFiles() throws Exception {
		final var definition = grammar();
		final var extension = "tm4e" + UUID.randomUUID();
		final var name = "Makefile-" + UUID.randomUUID();
		final var session = registry.newEditSession();
		final var setup = configure(session, definition, List.of("*." + extension, name, "*.spec." + extension));
		final var manager = Platform.getContentTypeManager();
		assertThat(manager.getContentType(setup.id())).isNull();
		assertThat(registry.getGrammarContentType(definition)).isNull();
		session.save();
		final var type = castNonNull(manager.getContentType(setup.id()));
		assertThat(castNonNull(type.getBaseType()).getId()).isEqualTo(GrammarContentType.BASE_TYPE_ID);
		assertThat(manager.findContentTypesFor("file." + extension)).contains(type);
		assertThat(manager.findContentTypesFor(name)).contains(type);
		assertThat(manager.findContentTypesFor("file.spec." + extension)).contains(type);
		assertThat(manager.findContentTypesFor("ordinary.txt")).doesNotContain(type);
		assertThat(registry.getGrammarFor(type)).isNotNull().extracting((Function<? super @Nullable IGrammar, String>) @Nullable IGrammar::getScopeName)
				.isEqualTo(definition.getScope().getName());
		assertThat(reloadRegistry().getGrammarContentType(definition)).isEqualTo(setup);
		assertThat(reloadRegistry().getUserGrammarBinding(type)).isNotNull();
	}

	@Test
	void importedTypesInheritEditorAndCompareViewersFromTheSharedBase() throws Exception {
		final var definition = grammar();
		final var extension = "viewers-" + UUID.randomUUID();
		final var session = registry.newEditSession();
		final var setup = configure(session, definition, List.of("*." + extension));
		session.save();
		final var type = castNonNull(Platform.getContentTypeManager().getContentType(setup.id()));
		final var fileName = "example." + extension;
		assertThat(castNonNull(PlatformUI.getWorkbench().getEditorRegistry().getDefaultEditor(fileName, type)).getId())
				.isEqualTo("org.eclipse.ui.genericeditor.GenericEditor");
		final var contents = new TextElement(fileName);
		final var shell = new Shell();
		try {
			// Exercise both extension points: a single content viewer and a two-sided merge viewer.
			for (final var input : List.of(contents, new DiffNode(contents, contents))) {
				final var viewer = castNonNull(CompareUI.findContentViewer(null, input, shell, new CompareConfiguration()));
				assertThat(viewer.getClass().getName()).startsWith("org.eclipse.ui.internal.genericeditor.");
				viewer.getControl().dispose();
			}
		} finally {
			shell.dispose();
		}
	}

	@Test
	void reimportKeepsTheLegacyLanguagePackParent() throws Exception {
		checkReimportWithParent("org.eclipse.tm4e.language_pack.basetype", true);
	}

	@Test
	void reimportRejectsOtherParentsEvenBelowTheSharedBase() throws Exception {
		checkReimportWithParent("org.eclipse.tm4e.tests.parent." + UUID.randomUUID(), false);
	}

	private void checkReimportWithParent(final String parentId, final boolean supported) throws Exception {
		final var definition = grammar();
		final var originalExtension = "old-parent-" + UUID.randomUUID();
		var session = registry.newEditSession();
		final var setup = configure(session, definition, List.of("*." + originalExtension));
		session.save();
		final var manager = Platform.getContentTypeManager();
		final var existingParent = manager.getContentType(parentId);
		// The UI test runtime omits the optional language pack; reproduce its old parent ID without adding that dependency.
		final var parent = existingParent == null
				? manager.addContentType(parentId, "Parent test", manager.getContentType(GrammarContentType.BASE_TYPE_ID))
				: existingParent;
		try {
			// Recreate only the test's type to represent a saved workspace with the same import metadata and another parent.
			removeTestType(setup.id());
			final var type = manager.addContentType(setup.id(), setup.name(), parent);
			type.addFileSpec(originalExtension, IContentType.FILE_EXTENSION_SPEC);
			session = reloadRegistry().newEditSession();
			final var replacement = "new-parent-" + UUID.randomUUID();
			if (supported) {
				assertThat(configure(session, definition, List.of("*." + replacement)).id()).isEqualTo(setup.id());
				session.save();
				assertThat(castNonNull(manager.getContentType(setup.id())).getBaseType()).isEqualTo(parent);
				assertThat(type.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).containsExactly(replacement);
				assertThat(manager.findContentTypesFor("ordinary.txt")).doesNotContain(type);
			} else {
				final var reimport = session;
				assertThatIllegalArgumentException().isThrownBy(() -> configure(reimport, definition, List.of("*." + replacement)));
				assertThat(type.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).containsExactly(originalExtension);
			}
		} finally {
			// Remove the child before its temporary parent so Eclipse can still resolve it during cleanup.
			removeTestType(setup.id());
			if (existingParent == null)
				removeTestType(parentId);
		}
	}

	@Test
	void cancellationAndImportOnlyDoNotCreateTypes() throws Exception {
		final var definition = grammar();
		final var session = registry.newEditSession();
		final var setup = configure(session, definition, List.of("*.cancel" + UUID.randomUUID()));
		session.reset();
		session.save();
		assertThat(Platform.getContentTypeManager().getContentType(setup.id())).isNull();
		session.importGrammar(definition, null, List.of());
		session.save();
		assertThat(registry.getDefinitions()).contains(definition);
		assertThat(registry.getGrammarContentType(definition)).isNull();
	}

	@Test
	void oldImportCanOptInAndReimportReusesItsSetup() throws Exception {
		final var definition = grammar();
		var session = registry.newEditSession();
		session.registerGrammarDefinition(definition);
		session.save();
		assertThat(registry.getGrammarContentType(definition)).isNull();
		session = registry.newEditSession();
		final var setup = configure(session, definition, List.of("*.first" + UUID.randomUUID()));
		session.save();
		final var extension = "second" + UUID.randomUUID();
		session = registry.newEditSession();
		final var sameFile = new GrammarDefinition(definition.getScope().getName(), definition.getPath());
		assertThat(session.importGrammar(sameFile, "Imported test", List.of("*." + extension))).isSameAs(definition);
		assertThat(castNonNull(session.getGrammarContentType(definition)).id()).isEqualTo(setup.id());
		session.save();
		assertThat(registry.getDefinitions()).filteredOn(item -> item.getURI().equals(definition.getURI())).hasSize(1);
		assertThat(castNonNull(Platform.getContentTypeManager().getContentType(setup.id()))
				.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).containsExactly(extension);
	}

	@Test
	void reimportAcceptsAssociationsDeduplicatedByEclipse() throws Exception {
		final var definition = grammar();
		final var suffix = UUID.randomUUID().toString();
		final var name = "Makefile-" + suffix;
		final var extension = "CASE-" + suffix;
		final var pattern = "*.Spec." + suffix;
		var session = registry.newEditSession();
		final var setup = configure(session, definition, List.of(name, name.toLowerCase(java.util.Locale.ROOT),
				"*." + extension, "*." + extension.toLowerCase(java.util.Locale.ROOT),
				pattern, pattern.toLowerCase(java.util.Locale.ROOT)));
		session.save();
		final var type = castNonNull(Platform.getContentTypeManager().getContentType(setup.id()));
		// Eclipse keeps the first spelling. Its deduplication is not a manual change to the managed type.
		assertThat(type.getFileSpecs(IContentType.FILE_NAME_SPEC)).containsExactly(name);
		assertThat(type.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).containsExactly(extension);
		assertThat(type.getFileSpecs(IContentType.FILE_PATTERN_SPEC)).containsExactly(pattern);

		// Reload the ownership metadata too, so the check covers persisted case variants.
		session = reloadRegistry().newEditSession();
		final var replacement = "*.replacement-" + suffix;
		assertThat(configure(session, definition, List.of(replacement)).id()).isEqualTo(setup.id());
		session.save();
		assertThat(type.getFileSpecs(IContentType.FILE_NAME_SPEC)).isEmpty();
		assertThat(type.getFileSpecs(IContentType.FILE_PATTERN_SPEC)).isEmpty();
		assertThat(type.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).containsExactly(replacement.substring(2));
	}

	@Test
	void removingAndReimportingTheSameFileKeepsItsSetupAndBindings() throws Exception {
		removeAndReimport(false);
	}

	@Test
	void removingAndReimportingTheSameFileCanChangeItsScope() throws Exception {
		removeAndReimport(true);
	}

	private void removeAndReimport(final boolean changeScope) throws Exception {
		final var original = grammar();
		var session = registry.newEditSession();
		final var setup = configure(session, original, List.of("*.reimport" + UUID.randomUUID()));
		session.save();
		final var scope = changeScope ? "source." + UUID.randomUUID() : original.getScope().getName();
		final var path = Path.of(original.getPath());
		Files.writeString(path, Files.readString(path).replace(original.getScope().getName(), scope));
		// The wizard constructs a new instance even when the user selects the same file again.
		final var replacement = new GrammarDefinition(scope, original.getPath());
		definitions.add(replacement);
		session = registry.newEditSession();
		session.unregisterGrammarDefinition(original);
		if (changeScope) {
			assertThat(configure(session, replacement, setup.fileAssociations())).isEqualTo(setup);
		} else {
			// Import-only must also keep saved bindings when a replacement still supplies the same scope.
			session.importGrammar(replacement, null, List.of());
		}
		session.save();
		assertThat(registry.getDefinitions()).filteredOn(item -> item.getURI().equals(original.getURI()))
				.containsExactly(replacement);
		assertThat(registry.getGrammarContentType(replacement)).isEqualTo(setup);
		final var type = castNonNull(Platform.getContentTypeManager().getContentType(setup.id()));
		assertThat(registry.getUserGrammarBinding(type)).isSameAs(replacement);
		assertThat(reloadRegistry().getUserGrammarBinding(type)).isNotNull()
				.extracting(item -> item.getScope().getName()).isEqualTo(scope);
	}

	@Test
	void conflictChecksUseManuallyEditedAssociationsWithoutOverwritingTheOwnershipSnapshot() throws Exception {
		final var first = grammar();
		final var second = grammar();
		final var released = "released" + UUID.randomUUID();
		final var manual = "manual" + UUID.randomUUID();
		var session = registry.newEditSession();
		final var setup = configure(session, first, List.of("*." + released));
		session.save();
		final var type = castNonNull(Platform.getContentTypeManager().getContentType(setup.id()));
		type.removeFileSpec(released, IContentType.FILE_EXTENSION_SPEC);
		type.addFileSpec(manual, IContentType.FILE_EXTENSION_SPEC);
		session = registry.newEditSession();
		configure(session, second, List.of("*." + released));
		session.save();
		assertThat(type.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).containsExactly(manual);
		final var later = registry.newEditSession();
		assertThatIllegalArgumentException().isThrownBy(() -> later.importGrammar(second, "Second", List.of("*." + manual)));
		// The old snapshot must still protect manual changes from a later reimport of the first grammar.
		assertThatIllegalArgumentException().isThrownBy(() -> later.importGrammar(first, "First", List.of("*." + released)));
		assertThat(registry.getGrammarContentType(first)).isEqualTo(setup);
	}

	@Test
	void queuedAssociationChangesReleaseTheOldExtensionBeforeSave() throws Exception {
		final var first = grammar();
		final var second = grammar();
		final var released = "released" + UUID.randomUUID();
		final var replacement = "replacement" + UUID.randomUUID();
		var session = registry.newEditSession();
		final var firstSetup = configure(session, first, List.of("*." + released));
		session.save();
		final var manager = Platform.getContentTypeManager();
		final var childId = "org.eclipse.tm4e.tests.child." + UUID.randomUUID();
		typeIds.add(childId);
		manager.addContentType(childId, "Child test", manager.getContentType(firstSetup.id()));
		session = registry.newEditSession();
		configure(session, first, List.of("*." + replacement));
		final var secondSetup = configure(session, second, List.of("*." + released));
		session.save();
		assertThat(manager.findContentTypesFor("file." + released)).extracting(IContentType::getId)
				.contains(secondSetup.id()).doesNotContain(firstSetup.id(), childId);
		assertThat(manager.findContentTypesFor("file." + replacement)).extracting(IContentType::getId)
				.contains(firstSetup.id(), childId).doesNotContain(secondSetup.id());
	}

	@Test
	void platformPathPatternsOnlyConflictWithOverlappingAssociations() throws Exception {
		final var first = grammar();
		final var second = grammar();
		final var manager = Platform.getContentTypeManager();
		final var id = "org.eclipse.tm4e.tests.pattern." + UUID.randomUUID();
		typeIds.add(id);
		final var type = manager.addContentType(id, "Pattern test", manager.getContentType(GrammarContentType.BASE_TYPE_ID));
		// JSON with Comments contributes this pattern; it must not reserve unrelated suffixes such as .m.
		type.addFileSpec("**/.github/hooks/*.json", IContentType.FILE_PATTERN_SPEC);
		final var session = registry.newEditSession();
		session.importGrammar(first, null, List.of());
		session.setUserGrammarBinding(type, first);
		configure(session, second, List.of("*.m", "Makefile"));
		assertThatIllegalArgumentException().isThrownBy(() -> session.importGrammar(second, "Second", List.of("*.json")));
		assertThatIllegalArgumentException().isThrownBy(() -> session.importGrammar(second, "Second", List.of("*.spec.json")));
		// A single sample such as file.m would miss overlaps that require a particular prefix.
		type.addFileSpec("module-?.m", IContentType.FILE_PATTERN_SPEC);
		assertThatIllegalArgumentException().isThrownBy(() -> session.importGrammar(second, "Second", List.of("*.m")));
		assertThatIllegalArgumentException().isThrownBy(() -> session.importGrammar(second, "Second", List.of("module-a.m")));
		configure(session, second, List.of("*.long.module-a.m", "Makefile"));
		type.removeFileSpec("module-?.m", IContentType.FILE_PATTERN_SPEC);
		configure(session, second, List.of("*.m", "Makefile"));
		session.save();
		assertThat(registry.getGrammarContentType(second)).isNotNull();
	}

	@Test
	void conflictsDoNotLeavePartialImports() throws Exception {
		final var first = grammar();
		final var second = grammar();
		final var association = "*.conflict" + UUID.randomUUID();
		final var session = registry.newEditSession();
		configure(session, first, List.of(association));
		assertThatIllegalArgumentException().isThrownBy(() -> session.importGrammar(second, "Second", List.of(association)));
		assertThat(session.getDefinitions()).doesNotContain(second);
		session.save();
		final var later = registry.newEditSession();
		assertThatIllegalArgumentException().isThrownBy(() -> later.importGrammar(second, "Second", List.of(association)));
		assertThat(later.getDefinitions()).doesNotContain(second);
	}

	@Test
	void anotherSourceWithTheSameScopeCannotBecomeTheDefault() throws Exception {
		final var first = grammar();
		final var other = grammar();
		final var second = new GrammarDefinition(first.getScope().getName(), other.getPath());
		final var session = registry.newEditSession();
		session.importGrammar(first, null, List.of());
		assertThatIllegalArgumentException().isThrownBy(() -> session.importGrammar(second, "Second", List.of("*.same-scope")));
		assertThat(session.getDefinitions()).doesNotContain(second);
	}

	@Test
	void anotherDialogCannotCreateASecondTypeForTheSameImport() throws Exception {
		final var definition = grammar();
		final var first = registry.newEditSession();
		final var second = registry.newEditSession();
		final var firstSetup = configure(first, definition, List.of("*.first" + UUID.randomUUID()));
		final var secondSetup = configure(second, definition, List.of("*.second" + UUID.randomUUID()));
		first.save();
		assertThatThrownBy(second::save).isInstanceOf(BackingStoreException.class);
		assertThat(registry.getGrammarContentType(definition)).isEqualTo(firstSetup);
		assertThat(Platform.getContentTypeManager().getContentType(secondSetup.id())).isNull();
	}

	@Test
	void saveRechecksWhichSourceWouldBeSelectedForTheScope() throws Exception {
		final var first = grammar();
		final var other = grammar();
		final var second = new GrammarDefinition(first.getScope().getName(), other.getPath());
		definitions.add(second);
		final var pending = registry.newEditSession();
		final var setup = configure(pending, second, List.of("*.pending" + UUID.randomUUID()));
		final var savedFirst = registry.newEditSession();
		savedFirst.importGrammar(first, null, List.of());
		savedFirst.save();
		// A binding stores the scope, so this pending setup would now select the other file.
		assertThatThrownBy(pending::save).isInstanceOf(BackingStoreException.class);
		assertThat(registry.getDefinitions()).contains(first).doesNotContain(second);
		assertThat(Platform.getContentTypeManager().getContentType(setup.id())).isNull();
	}

	@Test
	void customizationsAndPublishedTypesSurviveImportRemoval() throws Exception {
		final var definition = grammar();
		var session = registry.newEditSession();
		final var setup = configure(session, definition, List.of("*.owned" + UUID.randomUUID()));
		session.save();
		final var type = castNonNull(Platform.getContentTypeManager().getContentType(setup.id()));
		final String manual = "manual" + UUID.randomUUID();
		type.addFileSpec(manual, IContentType.FILE_EXTENSION_SPEC);
		final var reimport = registry.newEditSession();
		assertThatIllegalArgumentException().isThrownBy(() -> reimport.importGrammar(definition, "Changed", List.of("*.replacement")));
		session = registry.newEditSession();
		session.unregisterGrammarDefinition(definition);
		session.save();
		assertThat(Platform.getContentTypeManager().getContentType(setup.id())).isNotNull();
		assertThat(type.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)).contains(manual);
		assertThat(registry.getUserGrammarBinding(type)).isNull();
		assertThat(registry.getGrammarContentType(definition)).isNull();
	}

	@Test
	void laterSetupFailureRollsBackAlreadyCreatedTypesAndRegistryChanges() throws Exception {
		final var first = grammar();
		final var second = grammar();
		final var session = registry.newEditSession();
		final var firstSetup = configure(session, first, List.of("*.rollback" + UUID.randomUUID()));
		final var secondSetup = configure(session, second, List.of("*.collision" + UUID.randomUUID()));
		final var manager = Platform.getContentTypeManager();
		// Simulate a platform change after the dialog was filled, before Apply commits the two queued imports.
		manager.addContentType(secondSetup.id(), "Created elsewhere", manager.getContentType("org.eclipse.core.runtime.text"));
		assertThatThrownBy(session::save).isInstanceOf(BackingStoreException.class);
		assertThat(manager.getContentType(firstSetup.id())).isNull();
		assertThat(manager.findContentTypesFor("file" + firstSetup.fileAssociations().getFirst().substring(1)))
				.extracting(IContentType::getId).doesNotContain(firstSetup.id());
		assertThat(manager.getContentType(secondSetup.id())).isNotNull();
		assertThat(registry.getDefinitions()).doesNotContain(first, second);
		assertThat(reloadRegistry().getGrammarContentType(first)).isNull();
	}

	@Test
	void fileAssociationsPreserveMakefileAndRejectUnsupportedPaths() {
		assertThat(GrammarContentType.suggestFileAssociations(List.of("int", "Makefile", ".git/config")))
				.contains("int", "*.int", "Makefile", "*.Makefile", ".git/config");
		assertThatIllegalArgumentException().isThrownBy(() -> new GrammarContentType("test", "Test", List.of(".git/config")));
		assertThatIllegalArgumentException().isThrownBy(() -> new GrammarContentType("test", "Test", List.of("*.a+b.rb")));
	}

	@Test
	void standaloneWizardSavesAtFinish() throws Exception {
		wizardImport(true, true);
	}

	@Test
	void preferencesWizardWaitsForApply() throws Exception {
		wizardImport(false, true);
	}

	@Test
	void standaloneWizardImportsOnlyByDefault() throws Exception {
		wizardImport(true, false);
	}

	@Test
	void standaloneWizardRejectsMatchingFilesChangedAfterThePageLoaded() throws Exception {
		final var definition = grammar();
		final var saved = registry.newEditSession();
		saved.importGrammar(definition, null, List.of("*.original"));
		saved.save();
		final var wizardClass = Platform.getBundle("org.eclipse.tm4e.ui")
				.loadClass("org.eclipse.tm4e.ui.internal.wizards.TextMateGrammarImportWizard");
		final var wizard = (Wizard) wizardClass.getConstructor().newInstance();
		final var shell = new Shell();
		final var dialog = new WizardDialog(shell, wizard);
		try {
			dialog.create();
			final var page = wizard.getPages()[0];
			((Text) field(page, "grammarFileText")).setText(definition.getPath());
			assertThat(((Text) field(page, "fileAssociations")).getText()).isEqualTo("*.original");
			final var concurrent = registry.newEditSession();
			concurrent.importGrammar(definition, null, List.of("*.newer"));
			concurrent.save();

			assertThat(wizard.performFinish()).isFalse();
			assertThat(registry.getSavedGrammarFileAssociations(definition)).containsExactly("*.newer");
		} finally {
			dialog.close();
			shell.dispose();
		}
	}

	@Test
	void preferencesWizardImportsOnlyByDefault() throws Exception {
		wizardImport(false, false);
	}

	@Test
	void wizardRestoresSetupOnlyForTheSelectedImport() throws Exception {
		final var configured = grammar();
		final var importOnly = grammar();
		final var newImport = grammar();
		final var session = registry.newEditSession();
		final var setup = configure(session, configured, List.of("*.configured" + UUID.randomUUID()));
		session.registerGrammarDefinition(importOnly);
		final var wizardClass = Platform.getBundle("org.eclipse.tm4e.ui")
				.loadClass("org.eclipse.tm4e.ui.internal.wizards.TextMateGrammarImportWizard");
		final var wizard = (Wizard) wizardClass.getConstructor(IGrammarRegistryManager.EditSession.class, boolean.class)
				.newInstance(session, false);
		final var shell = new Shell();
		final var dialog = new WizardDialog(shell, wizard);
		try {
			dialog.create();
			final var page = wizard.getPages()[0];
			final var file = (Text) field(page, "grammarFileText");
			final var workspaceDefault = (Button) field(page, "workspaceDefault");
			final var associations = (Text) field(page, "fileAssociations");
			file.setText(configured.getPath());
			assertThat(workspaceDefault.getSelection()).isTrue();
			assertThat(associations.getText()).isEqualTo(String.join(", ", setup.fileAssociations()));
			assertThat(associations.isEnabled()).isTrue();
			file.setText(importOnly.getPath());
			assertThat(workspaceDefault.getSelection()).isFalse();
			// Opting in for one file must not opt in a different file selected later in the same wizard.
			workspaceDefault.setSelection(true);
			workspaceDefault.notifyListeners(SWT.Selection, new Event());
			file.setText(newImport.getPath());
			assertThat(workspaceDefault.getSelection()).isFalse();
			assertThat(associations.isEnabled()).isTrue();
		} finally {
			dialog.close();
			shell.dispose();
		}
	}

	@Test
	void applyRefreshesContentTypesAndKeepsTheSelectedGrammar() throws Exception {
		final var existing = grammar();
		final var saved = registry.newEditSession();
		// Removing the pending label moves the new grammar ahead of this row in the sorted table.
		saved.importGrammar(existing, "Imported test (0)", List.of("*.existing" + UUID.randomUUID()));
		typeIds.add(castNonNull(saved.getGrammarContentType(existing)).id());
		saved.save();
		final var definition = grammar();
		final var pageClass = Platform.getBundle("org.eclipse.tm4e.ui")
				.loadClass("org.eclipse.tm4e.ui.internal.preferences.GrammarPreferencePage");
		final var page = (PreferencePage) pageClass.getConstructor().newInstance();
		final var shell = new Shell();
		try {
			page.createControl(shell);
			final var session = (IGrammarRegistryManager.EditSession) field(page, "grammarManager");
			final var setup = configure(session, definition, List.of("*.apply" + UUID.randomUUID()));
			final var grammars = (TableViewer) field(page, "grammarsTable");
			// Match the page state after its import wizard finishes, before Apply creates the content type.
			grammars.refresh();
			grammars.setSelection(new StructuredSelection(definition));
			final int selectedIndex = grammars.getTable().getSelectionIndex();
			final var contentTypesWidget = field(page, "contentTypesWidget");
			final var contentTypes = (TableViewer) contentTypesWidget.getClass().getMethod("getTable").invoke(contentTypesWidget);
			assertThat(contentTypes.getTable().getItemCount()).isZero();
			final var getApplyButton = PreferencePage.class.getDeclaredMethod("getApplyButton");
			getApplyButton.setAccessible(true);
			((Button) getApplyButton.invoke(page)).notifyListeners(SWT.Selection, new Event());
			assertThat(page.getErrorMessage()).isNull();
			final var type = castNonNull(Platform.getContentTypeManager().getContentType(setup.id()));
			assertThat(registry.getUserGrammarBinding(type)).isSameAs(definition);
			assertThat(contentTypes.getTable().getItems()).extracting(item -> ((IContentType) item.getData()).getId())
					.containsExactly(setup.id());
			assertThat(grammars.getStructuredSelection().getFirstElement()).isSameAs(definition);
			assertThat(grammars.getTable().getSelection()[0].getText(0)).isEqualTo(setup.name());
			assertThat(grammars.getTable().getSelectionIndex()).isNotEqualTo(selectedIndex);
		} finally {
			page.dispose();
			shell.dispose();
		}
	}

	@Test
	void addingBindingKeepsTheSelectedGrammarAfterSorting() throws Exception {
		changeBindingAndCheckSelection(true);
	}

	@Test
	void removingBindingKeepsTheSelectedGrammarAfterSorting() throws Exception {
		changeBindingAndCheckSelection(false);
	}

	private void changeBindingAndCheckSelection(final boolean add) throws Exception {
		final var existing = grammar();
		final var saved = registry.newEditSession();
		// This row stays between the selected grammar's bound and unbound positions.
		saved.importGrammar(existing, "Binding test B", List.of("*.binding" + UUID.randomUUID()));
		typeIds.add(castNonNull(saved.getGrammarContentType(existing)).id());
		saved.save();
		final var manager = Platform.getContentTypeManager();
		final var typeId = "org.eclipse.tm4e.tests.binding." + UUID.randomUUID();
		typeIds.add(typeId);
		final var type = manager.addContentType(typeId, "Binding test A", manager.getContentType(GrammarContentType.BASE_TYPE_ID));
		final var definition = grammar();
		final var pageClass = Platform.getBundle("org.eclipse.tm4e.ui")
				.loadClass("org.eclipse.tm4e.ui.internal.preferences.GrammarPreferencePage");
		final var page = (PreferencePage) pageClass.getConstructor().newInstance();
		final var shell = new Shell();
		try {
			page.createControl(shell);
			final var session = (IGrammarRegistryManager.EditSession) field(page, "grammarManager");
			session.importGrammar(definition, null, List.of());
			if (!add)
				session.setUserGrammarBinding(type, definition);
			final var grammars = (TableViewer) field(page, "grammarsTable");
			grammars.refresh();
			grammars.setSelection(new StructuredSelection(definition));
			final int selectedIndex = grammars.getTable().getSelectionIndex();
			final var contentTypesWidget = (Composite) field(page, "contentTypesWidget");
			final var contentTypes = (TableViewer) contentTypesWidget.getClass().getMethod("getTable").invoke(contentTypesWidget);
			if (!add)
				contentTypes.setSelection(new StructuredSelection(type));
			final var button = buttons(contentTypesWidget).get(add ? 0 : 1);
			assertThat(button.getEnabled()).isTrue();
			if (add) {
				selectContentTypeInDialog(shell, type);
			}
			button.notifyListeners(SWT.Selection, new Event());
			assertThat(session.getUserGrammarBinding(type)).isSameAs(add ? definition : null);
			assertThat(grammars.getStructuredSelection().getFirstElement()).isSameAs(definition);
			assertThat(grammars.getTable().getSelectionIndex()).isNotEqualTo(selectedIndex);
			assertThat(contentTypes.getTable().getItems()).extracting((ThrowingExtractor<? super TableItem, @Nullable Object, RuntimeException>) TableItem::getData)
					.containsExactlyElementsOf(add ? List.of(type) : List.of());
		} finally {
			page.dispose();
			shell.dispose();
		}
	}

	private static void selectContentTypeInDialog(final Shell parent, final IContentType type) throws Exception {
		final var setSelection = AbstractElementListSelectionDialog.class.getDeclaredMethod("setSelection", Object[].class);
		setSelection.setAccessible(true);
		// Add opens a modal dialog; its event loop runs this selection before the button callback returns.
		parent.getDisplay().asyncExec(() -> {
			for (final var shell : parent.getShells()) {
				if (!(shell.getData() instanceof final ElementListSelectionDialog dialog))
					continue;
				try {
					final var ok = castNonNull(shell.getDefaultButton());
					// The dialog fills its list asynchronously; wait until the requested selection is accepted.
					waitForAndAssertCondition(5_000, parent.getDisplay(), () -> {
						setSelection.invoke(dialog, (Object) new Object[] { type });
						return ok.getEnabled();
					});
					ok.notifyListeners(SWT.Selection, new Event());
				} finally {
					// Close the dialog even if its selection assertion fails, so the test cannot remain blocked.
					dialog.close();
				}
				return;
			}
			fail("The content-type selection dialog did not open");
		});
	}

	private static List<Button> buttons(final Composite parent) {
		final var buttons = new ArrayList<Button>();
		for (final var child : parent.getChildren()) {
			if (child instanceof final Button button)
				buttons.add(button);
			else if (child instanceof final Composite composite)
				buttons.addAll(buttons(composite));
		}
		return buttons;
	}

	private void wizardImport(final boolean saveOnFinish, final boolean workspaceSetup) throws Exception {
		final var definition = grammar();
		final var session = registry.newEditSession();
		final var typesBefore = Platform.getContentTypeManager().getAllContentTypes();
		final var wizardClass = Platform.getBundle("org.eclipse.tm4e.ui")
				.loadClass("org.eclipse.tm4e.ui.internal.wizards.TextMateGrammarImportWizard");
		final var wizard = (Wizard) wizardClass.getConstructor(IGrammarRegistryManager.EditSession.class, boolean.class)
				.newInstance(session, saveOnFinish);
		final var shell = new Shell();
		final var dialog = new WizardDialog(shell, wizard);
		try {
			dialog.create();
			final var page = wizard.getPages()[0];
			((Text) field(page, "grammarFileText")).setText(definition.getPath());
			final var workspaceDefault = (Button) field(page, "workspaceDefault");
			// A new import must not change workspace language choices without an explicit opt-in.
			assertThat(workspaceDefault.getSelection()).isFalse();
			final var associations = (Text) field(page, "fileAssociations");
			assertThat(associations.isEnabled()).isTrue();
			String expectedAssociation = "int";
			if (workspaceSetup) {
				workspaceDefault.setSelection(true);
				workspaceDefault.notifyListeners(SWT.Selection, new Event());
				assertThat(associations.isEnabled()).isTrue();
				expectedAssociation = "*.wizard" + UUID.randomUUID();
				associations.setText(expectedAssociation);
			}
			assertThat(page.isPageComplete()).isTrue();
			assertThat(wizard.performFinish()).isTrue();
			// The wizard creates its own definition instance; cleanup must remove that registered instance.
			definitions.add((IGrammarDefinition) wizardClass.getMethod("getCreatedDefinition").invoke(wizard));
			assertThat(session.getSavedGrammarFileAssociations(definition))
					.containsExactlyElementsOf(workspaceSetup ? List.of(expectedAssociation) : List.of("int", "*.int"));
			final var setup = session.getGrammarContentType(definition);
			if (workspaceSetup) {
				final var configured = castNonNull(setup);
				typeIds.add(configured.id());
				assertThat(Platform.getContentTypeManager().getContentType(configured.id()) != null).isEqualTo(saveOnFinish);
			} else {
				assertThat(setup).isNull();
			}
			if (!saveOnFinish)
				session.save();
			assertThat(registry.getGrammarContentType(definition)).isEqualTo(setup);
			assertThat(registry.getDefinitions()).filteredOn(item -> item.getURI().equals(definition.getURI())).hasSize(1);
			if (!workspaceSetup)
				assertThat(Platform.getContentTypeManager().getAllContentTypes()).containsExactlyInAnyOrder(typesBefore);
		} finally {
			dialog.close();
			shell.dispose();
		}
	}

	/** Supplies named text to Eclipse's real compare-viewer selection, including content-type detection. */
	private record TextElement(String name) implements ITypedElement, IStreamContentAccessor {
		@Override
		public String getName() {
			return name;
		}

		@Override
		public @Nullable Image getImage() {
			return null;
		}

		@Override
		public String getType() {
			return TEXT_TYPE;
		}

		@Override
		public InputStream getContents() {
			return new ByteArrayInputStream("example\n".getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void removeTestType(final String id) throws Exception {
		final var manager = Platform.getContentTypeManager();
		final var type = manager.getContentType(id);
		if (type != null) {
			// Test-owned types have no outside references; clear their matches before removing them.
			for (final int kind : List.of(IContentType.FILE_NAME_SPEC, IContentType.FILE_EXTENSION_SPEC,
					IContentType.FILE_PATTERN_SPEC)) {
				for (final var spec : type.getFileSpecs(kind)) {
					type.removeFileSpec(spec, kind);
				}
			}
			manager.removeContentType(id);
		}
	}

	private static Object field(final Object object, final String name) throws Exception {
		final var field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return castNonNull(field.get(object));
	}

	private GrammarContentType configure(final IGrammarRegistryManager.EditSession session, final IGrammarDefinition definition,
			final List<String> associations) {
		session.importGrammar(definition, "Imported test", associations);
		final var setup = castNonNull(session.getGrammarContentType(definition));
		typeIds.add(setup.id());
		return setup;
	}

	private IGrammarDefinition grammar() throws Exception {
		final var file = Files.createTempFile("tm4e-managed-import-", ".json");
		files.add(file);
		final var scope = "source." + UUID.randomUUID();
		Files.writeString(file, "{\"scopeName\":\"" + scope + "\",\"name\":\"Imported test\",\"fileTypes\":[\"int\"],\"patterns\":[]}");
		final var definition = new GrammarDefinition(scope, file.toString());
		definitions.add(definition);
		return definition;
	}

	private IGrammarRegistryManager reloadRegistry() throws Exception {
		final var implementation = registry.getClass();
		final var constructor = implementation.getDeclaredConstructor();
		constructor.setAccessible(true);
		final var restored = (IGrammarRegistryManager) constructor.newInstance();
		final var load = implementation.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(restored);
		return restored;
	}
}
