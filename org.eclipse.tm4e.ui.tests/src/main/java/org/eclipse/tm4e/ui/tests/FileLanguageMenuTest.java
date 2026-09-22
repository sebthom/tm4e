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

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNonNull;
import static org.eclipse.tm4e.ui.tests.support.TestUtils.waitForAndAssertCondition;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection.Language;
import org.eclipse.tm4e.ui.internal.utils.UI;
import org.eclipse.tm4e.ui.tests.support.TestUtils;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.ui.ISources;
import org.eclipse.ui.dialogs.AbstractElementListSelectionDialog;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.FilteredList;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.part.ISetSelectionTarget;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks language ranking, labels and filtering, menu visibility, and live updates after choosing or resetting a language.
 * Each test owns its workspace project and saved file choices.
 */
class FileLanguageMenuTest {

	private final IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("file-language-menu-" + UUID.randomUUID());
	private final IFile file = project.getFile("example.ts");
	private ITextEditor editor;
	private IContributionItem contribution;
	private Menu menu;

	@BeforeEach
	void setup() throws Exception {
		project.create(null);
		project.open(null);
		file.create(new ByteArrayInputStream("let value = 1;".getBytes(StandardCharsets.UTF_8)), true, null);
	}

	@AfterEach
	void teardown() throws Exception {
		disposeMenu();
		TestUtils.closeEditor(editor);
		if (project.exists())
			project.delete(true, true, null);
	}

	@Test
	void languageMenuIsHiddenInProjectExplorerWithAnEditorStillOpen() throws Exception {
		openEditor();
		final var page = editor.getSite().getPage();
		final var explorer = page.showView("org.eclipse.ui.navigator.ProjectExplorer");
		try {
			page.activate(editor);
			assertThat(isLanguageMenuVisible()).isTrue();

			page.activate(explorer);
			((ISetSelectionTarget) explorer).selectReveal(new StructuredSelection(file));
			// activeEditor still points to the open editor. Menu visibility must follow the part receiving the click.
			assertThat(page.getActiveEditor()).isSameAs(editor);
			assertThat(page.getActivePart()).isSameAs(explorer);
			assertThat(isLanguageMenuVisible()).isFalse();
		} finally {
			page.hideView(explorer);
		}
	}

	@Test
	void preferencesMenuCommandFollowsThemeSubmenu() {
		final var textMateMenu = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus"))
				.flatMap(element -> Arrays.stream(element.getChildren("menu")))
				.filter(element -> "org.eclipse.tm4e.ui.internal.menus.TextMate".equals(element.getAttribute("id")))
				.findFirst().orElseThrow();
		final var children = textMateMenu.getChildren();
		final var themeIndex = IntStream.range(0, children.length)
				.filter(index -> "menu".equals(children[index].getName())
						&& "org.eclipse.tm4e.ui.internal.menus.Theme".equals(children[index].getAttribute("id")))
				.findFirst().orElseThrow();
		final var separator = children[themeIndex + 1];
		assertThat(separator.getName()).isEqualTo("separator");
		assertThat(separator.getAttribute("name")).isEqualTo("preferences");
		final var preferences = children[themeIndex + 2];
		assertThat(preferences.getName()).isEqualTo("command");
		assertThat(preferences.getAttribute("commandId")).isEqualTo("org.eclipse.tm4e.ui.openTextMatePreferencesCommand");
	}

	@Test
	void languageMenuIsAvailableBeforeAGrammarIsSelected() throws Exception {
		final var types = Platform.getContentTypeManager();
		final var extension = "menu-plain-" + UUID.randomUUID();
		final var type = types.addContentType("org.eclipse.tm4e.ui.tests." + extension, "Menu plain text",
				types.getContentType("org.eclipse.core.runtime.text"));
		try {
			type.addFileSpec(extension, IContentType.FILE_EXTENSION_SPEC);
			final var plainFile = project.getFile("example." + extension);
			plainFile.create(new ByteArrayInputStream("plain text".getBytes(StandardCharsets.UTF_8)), true, null);
			editor = (ITextEditor) IDE.openEditor(castNonNull(UI.getActivePage()), plainFile, "org.eclipse.ui.genericeditor.GenericEditor");
			final var reconciler = castNonNull(TMPresentationReconciler.getTMPresentationReconciler(editor));
			assertThat(reconciler.isEnabled()).isFalse();
			// Requiring active highlighting would hide the action needed to choose the file's first grammar.
			assertThat(isLanguageMenuVisible()).isTrue();
			final var items = createMenu().getItems();
			assertThat(items).extracting(MenuItem::getText).containsExactly("Current language: None", "Choose Language...");
			assertThat(items[0].getEnabled()).isFalse();
		} finally {
			types.removeContentType(type.getId());
		}
	}

	@Test
	void resetIsHiddenWithoutAFileChoice() throws Exception {
		openEditor();
		final var items = createMenu().getItems();
		assertThat(items).extracting(MenuItem::getText).containsExactly("Current language: TypeScript", "Choose Language...");
		assertThat(items[0].getEnabled()).isFalse();
		assertThat(items[1].getEnabled()).isTrue();
	}

	@Test
	void externalFilesShowTheirCurrentLanguageWithoutFileChoiceActions() throws Exception {
		final var externalFile = Files.createTempFile("tm4e-menu-", ".ts");
		try {
			Files.writeString(externalFile, "let value = 1;");
			final var input = new FileStoreEditorInput(EFS.getStore(externalFile.toUri()));
			editor = (ITextEditor) castNonNull(UI.getActivePage()).openEditor(input, "org.eclipse.ui.genericeditor.GenericEditor");
			assertThat(editor.getEditorInput().getAdapter(IFile.class)).isNull();
			assertThat(isLanguageMenuVisible()).isTrue();
			final var items = createMenu().getItems();
			assertThat(items).extracting(MenuItem::getText).containsExactly("Current language: TypeScript");
			assertThat(items[0].getEnabled()).isFalse();
		} finally {
			TestUtils.closeEditor(editor);
			Files.deleteIfExists(externalFile);
		}
	}

	@Test
	void resetClearsOnlyThisFileAndTakesEffectImmediately() throws Exception {
		// Use this bundle's fixture so the menu test does not require the optional language pack.
		final var language = new Language("source.ts@org.eclipse.tm4e.ui.tests", "org.eclipse.tm4e.ui.tests.testContentType.child");
		final var otherFile = project.getFile("other.ts");
		otherFile.create(new ByteArrayInputStream(new byte[0]), true, null);
		FileLanguageSelection.setLanguage(file, language);
		FileLanguageSelection.setLanguage(otherFile, language);
		openEditor();
		final var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		final var selection = FileLanguageSelection.getForDocument(document);
		assertThat(selection).isNotNull();
		final var items = createMenu().getItems();
		assertThat(items).extracting(MenuItem::getText)
				.containsExactly("Current language: TypeScript", "Choose Language (TypeScript)...", "Reset Language to Default");
		final var reset = items[2];
		assertThat(reset.getEnabled()).isTrue();
		runReset(reset);
		assertThat(FileLanguageSelection.hasSavedLanguage(file)).isFalse();
		assertThat(FileLanguageSelection.getSavedLanguage(otherFile)).isEqualTo(language);
		// Reset now updates the existing document, including its editing features, without requiring a reopen.
		assertThat(FileLanguageSelection.getForDocument(document)).isNull();
		assertThat(document.get()).isEqualTo("let value = 1;");
		assertThat(createMenu().getItems()).extracting(MenuItem::getText)
				.containsExactly("Current language: TypeScript", "Choose Language...");
		assertThat(editor.getDocumentProvider().getDocument(editor.getEditorInput())).isSameAs(document);
	}

	@Test
	void resetCanClearAChoiceWhoseGrammarIsUnavailable() throws Exception {
		// Removing a grammar leaves the file's saved choice in place, even though getSavedLanguage returns null.
		final var property = new QualifiedName(TMUIPlugin.PLUGIN_ID, "fileLanguage");
		file.setPersistentProperty(property, "{\"scopeName\":\"source.removed-" + UUID.randomUUID() + "\",\"contentTypeId\":null}");
		openEditor();
		assertThat(FileLanguageSelection.getSavedLanguage(file)).isNull();
		final var items = createMenu().getItems();
		assertThat(items).extracting(MenuItem::getText)
				.containsExactly("Current language: TypeScript", "Choose Language (unavailable)...", "Reset Language to Default");
		final var reset = items[2];
		assertThat(reset.getEnabled()).isTrue();
		runReset(reset);
		assertThat(file.getPersistentProperty(property)).isNull();
	}

	@Test
	void menuShowsTheAppliedSyntaxOnlyChoice() throws Exception {
		openEditor();
		final var document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
		assertThat(FileLanguageSelection.getForDocument(document)).isNull();
		// The label must use the saved grammar's name even without a content type.
		FileLanguageSelection.setLanguage(file, new Language("source.ts@org.eclipse.tm4e.ui.tests", null));
		assertThat(createMenu().getItems()).extracting(MenuItem::getText)
				.containsExactly("Current language: TypeScript", "Choose Language (TypeScript)...", "Reset Language to Default");
		assertThat(FileLanguageSelection.getForDocument(document)).isNotNull();
	}

	@Test
	void currentLanguageFollowsTheEditorThroughWorkspaceChangesAndFileOverrides() throws Exception {
		openEditor();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var scope = "source.menu-" + UUID.randomUUID();
		final var grammarFile = project.getFile("menu.tmLanguage.json");
		grammarFile.create(new ByteArrayInputStream(("{\"scopeName\":\"" + scope
				+ "\",\"name\":\"Workspace & test\",\"patterns\":[]}").getBytes(StandardCharsets.UTF_8)), true, null);
		final var grammar = new GrammarDefinition(scope, castNonNull(grammarFile.getLocation()).toOSString());
		try {
			final var session = registry.newEditSession();
			session.registerGrammarDefinition(grammar);
			session.setUserGrammarBinding(castNonNull(Platform.getContentTypeManager()
					.getContentType("org.eclipse.tm4e.ui.tests.testContentType")), grammar);
			session.save();
			// Workspace changes apply after reopening; the menu must not advertise a grammar the editor is not using yet.
			assertThat(createMenu().getItem(0).getText()).isEqualTo("Current language: TypeScript");
			TestUtils.closeEditor(editor);
			openEditor();
			assertThat(createMenu().getItem(0).getText()).isEqualTo("Current language: Workspace && test");
			assertThat(menu.getItem(0).getEnabled()).isFalse();

			FileLanguageSelection.setLanguage(file, new Language("source.ts@org.eclipse.tm4e.ui.tests", null));
			assertThat(createMenu().getItem(0).getText()).isEqualTo("Current language: TypeScript");
			runReset(menu.getItem(2));
			assertThat(createMenu().getItem(0).getText()).isEqualTo("Current language: Workspace && test");
		} finally {
			final var cleanup = registry.newEditSession();
			cleanup.unregisterGrammarDefinition(grammar);
			cleanup.save();
		}
	}

	@Test
	void matchingUsesGrammarMetadataUntilAnExplicitEmptyListIsSaved() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var scope = "source.matching-metadata-" + UUID.randomUUID();
		final var grammarFile = project.getFile("matching-metadata.tmLanguage.json");
		grammarFile.create(new ByteArrayInputStream(("{\"scopeName\":\"" + scope
				+ "\",\"name\":\"Metadata match\",\"fileTypes\":[\"TS\"],\"patterns\":[]}")
						.getBytes(StandardCharsets.UTF_8)), true, null);
		final var grammar = new GrammarDefinition(scope, castNonNull(grammarFile.getLocation()).toOSString());
		try {
			final var add = registry.newEditSession();
			add.registerGrammarDefinition(grammar);
			add.save();
			final var language = new Language(scope, null);
			final var languages = FileLanguageSelection.getAvailableLanguages();
			assertThat(FileLanguageSelection.getMatchingLanguages("example.ts", languages)).contains(language);

			final var clear = registry.newEditSession();
			clear.importGrammar(grammar, null, java.util.List.of());
			clear.save();
			assertThat(FileLanguageSelection.getMatchingLanguages("example.ts", languages)).doesNotContain(language);
		} finally {
			final var cleanup = registry.newEditSession();
			cleanup.unregisterGrammarDefinition(grammar);
			cleanup.save();
		}
	}

	@Test
	void chooserFindsImportedGrammarsByDisplayNameAndScope() throws Exception {
		openEditor();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		// The display name is absent from the scope, so a scope-only label cannot pass the name search.
		final var scope = "source.chooser-" + UUID.randomUUID();
		final var grammarFile = project.getFile("chooser.tmLanguage.json");
		grammarFile.create(new ByteArrayInputStream(("{\"scopeName\":\"" + scope
				+ "\",\"name\":\"Mumps & test\",\"patterns\":[]}").getBytes(StandardCharsets.UTF_8)), true, null);
		final var grammar = new GrammarDefinition(scope, castNonNull(grammarFile.getLocation()).toOSString());
		final var unnamedScope = "source.unnamed-" + UUID.randomUUID();
		final var unnamedFile = project.getFile("unnamed.tmLanguage.json");
		unnamedFile.create(new ByteArrayInputStream(("{\"scopeName\":\"" + unnamedScope
				+ "\",\"name\":\" \",\"patterns\":[]}").getBytes(StandardCharsets.UTF_8)), true, null);
		final var unnamedGrammar = new GrammarDefinition(unnamedScope, castNonNull(unnamedFile.getLocation()).toOSString());
		try {
			final var session = registry.newEditSession();
			// Uppercase verifies that matching is case-insensitive for the lowercase workspace file name.
			session.importGrammar(grammar, null, java.util.List.of("*.TS"));
			session.registerGrammarDefinition(unnamedGrammar);
			session.save();
			final var expected = new Language(scope, null);
			assertThat(FileLanguageSelection.getAvailableLanguages()).contains(expected);
			final var type = castNonNull(Platform.getContentTypeManager()
					.getContentType("org.eclipse.tm4e.ui.tests.testContentType"));
			FileLanguageSelection.setLanguage(file,
					new Language("source.ts@org.eclipse.tm4e.ui.tests", type.getId()));
			final var parent = editor.getSite().getShell();
			final var failure = new AtomicReference<Throwable>();
			final var listField = AbstractElementListSelectionDialog.class.getDeclaredField("fFilteredList");
			listField.setAccessible(true);
			createMenu();
			// The modal dialog runs this callback; propagate failures after closing it so the test cannot remain blocked.
			parent.getDisplay().asyncExec(() -> {
				for (final var shell : parent.getShells()) {
					if (!(shell.getData() instanceof final ElementListSelectionDialog dialog))
						continue;
					try {
						final var list = (FilteredList) listField.get(dialog);
						final var table = Arrays.stream(list.getChildren()).filter(Table.class::isInstance)
								.map(Table.class::cast).findFirst().orElseThrow();
						final var mumpsLabel = "Mumps & test (" + scope + ") (syntax highlighting only)";
						final var typeScriptLabel = "TypeScript (" + type.getName()
								+ ", source.ts@org.eclipse.tm4e.ui.tests)";
						final var unnamedLabel = unnamedScope + " (syntax highlighting only)";
						waitForAndAssertCondition(5_000, table.getDisplay(), () -> {
							final var labels = Arrays.stream(table.getItems()).map(TableItem::getText).toList();
							assertThat(labels).contains(mumpsLabel, typeScriptLabel, unnamedLabel);
							assertThat(labels.indexOf(mumpsLabel)).isLessThan(labels.indexOf(typeScriptLabel));
							assertThat(labels.indexOf(typeScriptLabel)).isLessThan(labels.indexOf(unnamedLabel));
							assertThat(table.getSelection()).extracting(TableItem::getText).containsExactly(typeScriptLabel);
							return true;
						});
						assertChooserFilter(dialog, table, "*" + unnamedScope, unnamedLabel);
						// Other installed plugins may also provide TypeScript; select this test bundle's provider.
						assertChooserFilter(dialog, table, "TypeScript*org.eclipse.tm4e.ui.tests", typeScriptLabel);
						assertChooserFilter(dialog, table, "Mumps & test", mumpsLabel);
						assertChooserFilter(dialog, table, "*" + scope, mumpsLabel);
						final var ok = castNonNull(shell.getDefaultButton());
						assertThat(ok.getEnabled()).isTrue();
						ok.notifyListeners(SWT.Selection, new Event());
					} catch (final Exception | AssertionError ex) {
						failure.set(ex);
					} finally {
						dialog.close();
					}
					return;
				}
				failure.set(new AssertionError("The language selection dialog did not open"));
			});
			menu.getItem(1).notifyListeners(SWT.Selection, new Event());
			if (failure.get() != null)
				throw new AssertionError("Language chooser failed", failure.get());
			assertThat(FileLanguageSelection.getSavedLanguage(file)).isEqualTo(expected);
			assertThat(createMenu().getItem(0).getText()).isEqualTo("Current language: Mumps && test");
		} finally {
			final var cleanup = registry.newEditSession();
			cleanup.unregisterGrammarDefinition(grammar);
			cleanup.unregisterGrammarDefinition(unnamedGrammar);
			cleanup.save();
		}
	}

	private static void assertChooserFilter(final ElementListSelectionDialog dialog, final Table table, final String filter,
			final String expectedLabel) {
		dialog.setFilter(filter);
		// Filtering updates the SWT table asynchronously, including its selected row.
		waitForAndAssertCondition(5_000, table.getDisplay(), () -> {
			assertThat(table.getItems()).extracting(TableItem::getText).containsExactly(expectedLabel);
			return table.getSelectionCount() == 1;
		});
	}

	private boolean isLanguageMenuVisible() throws CoreException {
		final var definition = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus"))
				.flatMap(element -> Arrays.stream(element.getChildren("menu")))
				.filter(element -> "org.eclipse.tm4e.ui.internal.menus.TextMate".equals(element.getAttribute("id")))
				.findFirst().orElseThrow();
		final var visibility = definition.getChildren("visibleWhen");
		// Eclipse shows a contributed menu by default when its parent has no visibility condition.
		if (visibility.length == 0)
			return true;
		final var page = editor.getSite().getPage();
		final var context = new EvaluationContext(null, page.getActivePart());
		context.addVariable(ISources.ACTIVE_PART_NAME, page.getActivePart());
		context.addVariable(ISources.ACTIVE_EDITOR_NAME, page.getActiveEditor());
		context.setAllowPluginActivation(true);
		return ExpressionConverter.getDefault().perform(visibility[0].getChildren()[0]).evaluate(context) == EvaluationResult.TRUE;
	}

	private void openEditor() throws CoreException {
		editor = (ITextEditor) IDE.openEditor(castNonNull(UI.getActivePage()), file, "org.eclipse.ui.genericeditor.GenericEditor");
		assertThat(TMPresentationReconciler.getTMPresentationReconciler(editor)).isNotNull();
	}

	private Menu createMenu() throws CoreException {
		disposeMenu();
		// Load the registered contribution without exporting the implementation package just for tests.
		final var definition = Arrays.stream(Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus"))
				.flatMap(element -> Arrays.stream(element.getChildren("menu")))
				.flatMap(element -> Arrays.stream(element.getChildren("dynamic")))
				.filter(element -> "org.eclipse.tm4e.ui.menus.LanguageContribution".equals(element.getAttribute("id")))
				.findFirst().orElseThrow();
		contribution = (IContributionItem) definition.createExecutableExtension("class");
		((IWorkbenchContribution) contribution).initialize(editor.getSite());
		menu = new Menu(editor.getSite().getShell(), SWT.POP_UP);
		contribution.fill(menu, -1);
		return menu;
	}

	private void runReset(final MenuItem reset) {
		reset.notifyListeners(SWT.Selection, new Event());
	}

	private void disposeMenu() {
		if (menu != null && !menu.isDisposed())
			menu.dispose();
		if (contribution != null)
			contribution.dispose();
	}
}
