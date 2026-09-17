/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.ui.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNonNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.ExpressionConverter;
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
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection.Language;
import org.eclipse.tm4e.ui.internal.utils.UI;
import org.eclipse.tm4e.ui.tests.support.TestUtils;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.ui.ISources;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.part.ISetSelectionTarget;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks when the file language menu and reset action are visible, and whether reset updates an open editor.
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
			assertThat(createMenu().getItems()).extracting(MenuItem::getText).containsExactly("Choose Language...");
		} finally {
			types.removeContentType(type.getId());
		}
	}

	@Test
	void resetIsHiddenWithoutAFileChoice() throws Exception {
		openEditor();
		final var items = createMenu().getItems();
		assertThat(items).extracting(MenuItem::getText).containsExactly("Choose Language...");
		assertThat(items[0].getEnabled()).isTrue();
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
				.containsExactly("Choose Language (TypeScript)...", "Reset Language to Default");
		final var reset = items[1];
		assertThat(reset.getEnabled()).isTrue();
		runReset(reset);
		assertThat(FileLanguageSelection.hasSavedLanguage(file)).isFalse();
		assertThat(FileLanguageSelection.getSavedLanguage(otherFile)).isEqualTo(language);
		// Reset now updates the existing document, including its editing features, without requiring a reopen.
		assertThat(FileLanguageSelection.getForDocument(document)).isNull();
		assertThat(document.get()).isEqualTo("let value = 1;");
		assertThat(createMenu().getItems()).extracting(MenuItem::getText).containsExactly("Choose Language...");
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
				.containsExactly("Choose Language (unavailable)...", "Reset Language to Default");
		final var reset = items[1];
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
				.containsExactly("Choose Language (TypeScript)...", "Reset Language to Default");
		assertThat(FileLanguageSelection.getForDocument(document)).isNotNull();
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
