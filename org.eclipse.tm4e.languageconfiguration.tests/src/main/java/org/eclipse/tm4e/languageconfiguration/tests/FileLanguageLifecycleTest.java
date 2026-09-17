/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.languageconfiguration.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNonNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.tm4e.languageconfiguration.internal.LanguageConfigurationAutoEditStrategy;
import org.eclipse.tm4e.languageconfiguration.internal.LanguageConfigurationCharacterPairMatcher;
import org.eclipse.tm4e.languageconfiguration.internal.ToggleLineCommentHandler;
import org.eclipse.tm4e.languageconfiguration.internal.folding.FoldingSupport;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationDefinition;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.model.TMModelManager;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection.Language;
import org.eclipse.tm4e.ui.internal.utils.GrammarUtils;
import org.eclipse.tm4e.ui.internal.utils.UI;
import org.eclipse.tm4e.ui.tests.support.TestUtils;
import org.eclipse.tm4e.ui.text.TMPartitions;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.tm4e.ui.themes.ThemeAssociation;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that file language choices are saved, survive reopening, and update editor features even when workspace defaults differ.
 */
class FileLanguageLifecycleTest {

	private final String id = UUID.randomUUID().toString();
	private final org.eclipse.core.resources.IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("file-choice-" + id);
	private final List<IFile> connected = new ArrayList<>();
	private final List<GrammarDefinition> grammars = new ArrayList<>();
	private final List<LanguageConfigurationDefinition> configurations = new ArrayList<>();
	private final List<IContentType> types = new ArrayList<>();
	private IContentType workspaceType;
	private IContentType fileType;
	private GrammarDefinition workspaceGrammar;
	private GrammarDefinition fileGrammar;
	private @Nullable ITextEditor editor;

	@BeforeEach
	void setup() throws Exception {
		project.create(null);
		project.open(null);
		final var typeManager = Platform.getContentTypeManager();
		workspaceType = typeManager.addContentType("org.eclipse.tm4e.tests.file-default." + id, "Workspace language",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		types.add(workspaceType);
		workspaceType.addFileSpec("m", IContentType.FILE_EXTENSION_SPEC);
		fileType = typeManager.addContentType("org.eclipse.tm4e.tests.file-child." + id, "File language", workspaceType);
		types.add(fileType);
		workspaceGrammar = registerGrammar("source.file-default-" + id);
		fileGrammar = registerGrammar("source.file-child-" + id);
		bind(workspaceType, workspaceGrammar);
		registerConfiguration(workspaceType, """
			{"comments":{"lineComment":";"},"brackets":[["[","]"]],
			 "autoClosingPairs":[["[","]"]],
			 "folding":{"offSide":true,"markers":{"start":"^WORKSPACE_BEGIN$","end":"^WORKSPACE_END$"}}}
			""");
		registerConfiguration(fileType, """
			{"comments":{"lineComment":"//file"},"brackets":[["{","}"]],
			 "autoClosingPairs":[["{","}"]],
			 "folding":{"offSide":false,"markers":{"start":"^BEGIN$","end":"^END$"}}}
			""");
	}

	@AfterEach
	void teardown() throws Exception {
		final var openEditor = editor;
		if (openEditor != null) {
			UI.runSync(() -> castNonNull(UI.getActivePage()).closeEditor(openEditor, false));
		}
		for (final var file : connected) {
			FileBuffers.getTextFileBufferManager().disconnect(file.getFullPath(), LocationKind.IFILE, null);
		}
		final var configSession = LanguageConfigurationRegistryManager.getInstance().newEditSession();
		configurations.forEach(configSession::unregisterLanguageConfigurationDefinition);
		configSession.save();
		final var grammarSession = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		grammars.forEach(grammarSession::unregisterGrammarDefinition);
		grammarSession.save();
		// Remove children first because Eclipse content types retain their parent relationship.
		for (final var type : types.reversed()) {
			Platform.getContentTypeManager().removeContentType(type.getId());
		}
		if (project.exists()) {
			project.delete(true, true, null);
		}
	}

	@Test
	void disposingTheLastBufferConnectionReleasesItsLanguageListener() throws Exception {
		// Inspect the listener list directly so this test does not depend on garbage collection timing.
		final var listenerField = FileLanguageSelection.class.getDeclaredField("CHANGE_LISTENERS");
		listenerField.setAccessible(true);
		final var listeners = (ListenerList<?>) listenerField.get(null);
		final int originalCount = listeners.size();
		final var file = createFile("listeners.m", "body\n");
		final var buffers = FileBuffers.getTextFileBufferManager();
		for (int attempt = 0; attempt < 3; attempt++) {
			final var document = connect(file);
			final var partitioner = ((IDocumentExtension3) document).getDocumentPartitioner(TMPartitions.TM_PARTITIONING);
			try {
				assertThat(listeners.size()).isEqualTo(originalCount + 1);
				assertThat(connect(file)).isSameAs(document);
				buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
				connected.remove(file);
				// Closing one editor must leave the shared document subscribed for the remaining editor.
				assertThat(listeners.size()).isEqualTo(originalCount + 1);
				buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
				connected.remove(file);
				assertThat(listeners.size()).isEqualTo(originalCount);
			} finally {
				while (connected.remove(file)) {
					buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
				}
				// Also clean up when run against the old implementation, which misses this lifecycle call.
				partitioner.disconnect();
			}
		}
	}

	@Test
	void changingLanguageKeepsUnsavedTextUndoAndSharedViews() throws Exception {
		final var originalText = "( ) [ ] { }\n";
		final var file = createFile("live.m", originalText);
		final var first = openEditor(file);
		final var document = first.getDocumentProvider().getDocument(first.getEditorInput());
		final var viewer = (ProjectionViewer) first.getAdapter(ITextOperationTarget.class);
		final var page = castNonNull(UI.getActivePage());
		// Force a second editor: both views must continue using the same document and token model.
		final var second = (ITextEditor) page.openEditor(new FileEditorInput(file), "org.eclipse.ui.genericeditor.GenericEditor",
				false, IWorkbenchPage.MATCH_NONE);
		try {
			assertThat(second.getDocumentProvider().getDocument(second.getEditorInput())).isSameAs(document);
			final var firstPresentation = castNonNull(TMPresentationReconciler.getTMPresentationReconciler(first));
			final var secondPresentation = castNonNull(TMPresentationReconciler.getTMPresentationReconciler(second));
			final var model = TMModelManager.INSTANCE.getConnectedModel(document);
			TestUtils.waitForAndAssertCondition(5_000, () -> TMPartitions.getPartition(document, 0) != null);
			castNonNull(viewer).getTextWidget().append("unsaved");
			viewer.setSelectedRange(3, 0);
			final var selection = viewer.getSelectedRange();
			assertThat(first.isDirty()).isTrue();
			assertThat(viewer.canDoOperation(ITextOperationTarget.UNDO)).isTrue();

			FileLanguageSelection.setLanguage(file, new Language(fileGrammar.getScope().getQualifiedName(), fileType.getId()));
			TestUtils.waitForAndAssertCondition(5_000, () -> firstPresentation.getGrammar() != null
					&& firstPresentation.getGrammar().getScopeName().equals(fileGrammar.getScope().getQualifiedName()));
			assertThat(secondPresentation.getGrammar()).isSameAs(firstPresentation.getGrammar());
			assertThat(TMModelManager.INSTANCE.getConnectedModel(document)).isSameAs(model);
			assertThat(document.get()).isEqualTo(originalText + "unsaved");
			assertThat(viewer.getSelectedRange()).isEqualTo(selection);
			assertThat(first.isDirty()).isTrue();
			TestUtils.waitForAndAssertCondition(5_000, () -> TMPartitions.getPartition(document, 0) != null
					&& TMPartitions.getPartition(document, 0).getType().contains("file-child-"));

			FileLanguageSelection.setLanguage(file, null);
			TestUtils.waitForAndAssertCondition(5_000, () -> firstPresentation.getGrammar() != null
					&& firstPresentation.getGrammar().getScopeName().equals(workspaceGrammar.getScope().getQualifiedName()));
			assertThat(secondPresentation.getGrammar()).isSameAs(firstPresentation.getGrammar());
			assertThat(first.getDocumentProvider().getDocument(first.getEditorInput())).isSameAs(document);
			assertThat(viewer.getSelectedRange()).isEqualTo(selection);
			viewer.doOperation(ITextOperationTarget.UNDO);
			assertThat(document.get()).isEqualTo(originalText);
			try (var contents = file.getContents()) {
				assertThat(new String(contents.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(originalText);
			}
		} finally {
			page.closeEditor(second, false);
		}
	}

	@Test
	void changingLanguageUsesItsThemeAssociation() throws Exception {
		final var themeManager = TMUIPlugin.getThemeManager();
		final var themes = themeManager.getThemes();
		assertThat(themes).hasSizeGreaterThanOrEqualTo(2);
		// Set both appearance variants so this test does not depend on the workbench's light/dark preference.
		final var associations = List.of(
				new ThemeAssociation(themes[0].getId(), workspaceGrammar.getScope().getQualifiedName(), false),
				new ThemeAssociation(themes[0].getId(), workspaceGrammar.getScope().getQualifiedName(), true),
				new ThemeAssociation(themes[1].getId(), fileGrammar.getScope().getQualifiedName(), false),
				new ThemeAssociation(themes[1].getId(), fileGrammar.getScope().getQualifiedName(), true));
		final var session = themeManager.newEditSession();
		associations.forEach(session::registerThemeAssociation);
		session.save();
		try {
			final var file = createFile("theme.m", "body\n");
			final var presentation = castNonNull(TMPresentationReconciler.getTMPresentationReconciler(openEditor(file)));
			assertThat(presentation.getTokenProvider()).isSameAs(themes[0]);
			FileLanguageSelection.setLanguage(file, new Language(fileGrammar.getScope().getQualifiedName(), fileType.getId()));
			assertThat(presentation.getTokenProvider()).isSameAs(themes[1]);
			FileLanguageSelection.setLanguage(file, null);
			assertThat(presentation.getTokenProvider()).isSameAs(themes[0]);
		} finally {
			final var cleanup = themeManager.newEditSession();
			associations.forEach(cleanup::unregisterThemeAssociation);
			cleanup.save();
		}
	}

	@Test
	void changingLanguageRefreshesExistingEditingCaches() throws Exception {
		final var file = createFile("cached.m", "( ) [ ] { }");
		final var document = connect(file);
		final var strategy = new LanguageConfigurationAutoEditStrategy();
		final var matcher = new LanguageConfigurationCharacterPairMatcher();
		final var command = new DocumentCommand() {
		};
		command.offset = document.getLength();
		command.text = "[";
		try {
			strategy.customizeDocumentCommand(document, command);
			assertThat(command.text).isEqualTo("[]");
			assertThat(matcher.match(document, 5)).isNotNull();
			FileLanguageSelection.setLanguage(file, new Language(fileGrammar.getScope().getQualifiedName(), fileType.getId()));
			command.text = "{";
			strategy.customizeDocumentCommand(document, command);
			assertThat(command.text).isEqualTo("{}");
			assertThat(matcher.match(document, 5)).isNull();
			assertThat(matcher.match(document, 9)).isNotNull();
			FileLanguageSelection.setLanguage(file, null);
			command.text = "[";
			strategy.customizeDocumentCommand(document, command);
			assertThat(command.text).isEqualTo("[]");
			assertThat(matcher.match(document, 9)).isNull();
			assertThat(matcher.match(document, 5)).isNotNull();
		} finally {
			matcher.dispose();
		}
	}

	@Test
	void changingLanguageAddsAndRemovesMarkerFoldingWithoutEditing() throws Exception {
		final var typeManager = Platform.getContentTypeManager();
		final var plainType = typeManager.addContentType("org.eclipse.tm4e.tests.live-plain." + id, "Live plain test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		types.add(plainType);
		plainType.addFileSpec("live-language-" + id, IContentType.FILE_EXTENSION_SPEC);
		final var file = createFile("folding.live-language-" + id, "BEGIN\nbody\nEND\n");
		final var openEditor = openEditor(file);
		final var viewer = (ProjectionViewer) openEditor.getAdapter(ITextOperationTarget.class);
		final var annotations = castNonNull(viewer).getProjectionAnnotationModel();
		assertThat(annotations.getAnnotationIterator().hasNext()).isFalse();
		FileLanguageSelection.setLanguage(file, new Language(fileGrammar.getScope().getQualifiedName(), null));
		TestUtils.waitForAndAssertCondition(5_000, () -> annotations.getAnnotationIterator().hasNext());
		FileLanguageSelection.setLanguage(file, null);
		TestUtils.waitForAndAssertCondition(5_000, () -> !annotations.getAnnotationIterator().hasNext());
		assertThat(viewer.getDocument().get()).isEqualTo("BEGIN\nbody\nEND\n");
	}

	@Test
	void savingAndResettingApplyImmediatelyAndPersistAcrossReopening() throws Exception {
		final var file = createFile("routine.m", "( ) [ ] { }");
		final var initialDocument = connect(file);
		assertLanguage(initialDocument, workspaceGrammar.getScope().getQualifiedName(), workspaceType);
		final var objectiveC = new Language("source.objc@org.eclipse.tm4e.language_pack", "org.eclipse.tm4e.language_pack.objective-c");
		assertThat(FileLanguageSelection.getAvailableLanguages()).contains(objectiveC);
		FileLanguageSelection.setLanguage(file, objectiveC);
		assertThat(FileLanguageSelection.getSavedLanguage(file)).isEqualTo(objectiveC);
		// Switching languages must update the existing document immediately, without reopening its editors.
		assertLanguage(initialDocument, objectiveC.scopeName(), castNonNull(Platform.getContentTypeManager()
				.getContentType(objectiveC.contentTypeId())));
		assertInsertion(initialDocument, "(", "()");
		final var selectedDocument = reopen(file);
		assertThat(selectedDocument).isNotSameAs(initialDocument);
		assertLanguage(selectedDocument, objectiveC.scopeName(), castNonNull(Platform.getContentTypeManager()
				.getContentType(objectiveC.contentTypeId())));
		assertInsertion(selectedDocument, "(", "()");
		FileLanguageSelection.setLanguage(file, null);
		assertThat(FileLanguageSelection.getSavedLanguage(file)).isNull();
		assertLanguage(selectedDocument, workspaceGrammar.getScope().getQualifiedName(), workspaceType);
		assertInsertion(selectedDocument, "(", "(");
		assertLanguage(reopen(file), workspaceGrammar.getScope().getQualifiedName(), workspaceType);
		try (var contents = file.getContents()) {
			assertThat(new String(contents.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("( ) [ ] { }");
		}
	}

	@Test
	void importedChoiceKeepsItsEditingRulesAfterItsWorkspaceBindingIsRemoved() throws Exception {
		final var file = createFile("routine.m", "( ) [ ] { }");
		chooseImportedLanguage(file);
		final var document = connect(file);
		assertLanguage(document, fileGrammar.getScope().getQualifiedName(), fileType);
		TestUtils.waitForAndAssertCondition(5_000, () -> TMPartitions.getPartition(document, 0) != null);
		assertThat(TMPartitions.getContentTypesForOffset(document, 0)).containsExactly(fileType);
		final var registry = LanguageConfigurationRegistryManager.getInstance();
		// A lookup by content type alone uses the parent binding. A saved file choice must override it, including for folding.
		// Each lookup parses a new configuration, so compare rules rather than object identity.
		assertThat(registry.getLanguageConfigurationFor(fileType)).extracting("folding.offSide").isEqualTo(true);
		assertThat(registry.getLanguageConfigurationForResolvedTypes(fileType)).extracting("folding.offSide").isEqualTo(false);
		// Use reflection to check folding and comment rules without exporting internal packages for this test.
		assertThat(FoldingSupport.getFoldingRules(document)).extracting("offSide").isEqualTo(false);
		assertThat(registry.getCommentSupport(fileType)).extracting("lineComment").isEqualTo("//file");
		final var matcher = new LanguageConfigurationCharacterPairMatcher();
		try {
			assertThat(matcher.match(document, 5)).isNull();
			assertThat(matcher.match(document, 9)).isNotNull();
		} finally {
			matcher.dispose();
		}
		assertInsertion(document, "[", "[");
		assertInsertion(document, "{", "{}");
	}

	@Test
	void fileChoiceEnablesItsCommentCommandInTheGenericEditor() throws Exception {
		final var session = LanguageConfigurationRegistryManager.getInstance().newEditSession();
		session.unregisterLanguageConfigurationDefinition(configurations.get(0));
		session.save();
		final var file = createFile("routine.m", "body\n");
		final var openEditor = openEditor(file);
		final var document = openEditor.getDocumentProvider().getDocument(openEditor.getEditorInput());
		TestUtils.waitForAndAssertCondition(5_000, () -> TMPartitions.getPartition(document, 0) != null);
		final var command = castNonNull(openEditor.getSite().getService(ICommandService.class))
				.getCommand(ToggleLineCommentHandler.TOGGLE_LINE_COMMENT_COMMAND_ID);
		assertThat(command.isEnabled()).isFalse();
		// Enable an existing editor's command without recreating its handlers or document.
		chooseImportedLanguage(file);
		openEditor.selectAndReveal(0, 0);
		castNonNull(openEditor.getSite().getService(IHandlerService.class))
				.executeCommand(ToggleLineCommentHandler.TOGGLE_LINE_COMMENT_COMMAND_ID, null);
		assertThat(document.get()).startsWith("//file");
		FileLanguageSelection.setLanguage(file, null);
		TestUtils.waitForAndAssertCondition(5_000, () -> !command.isEnabled());
	}

	@Test
	void grammarOnlyFileChoiceEnablesFoldingInTheGenericEditor() throws Exception {
		// Use a separate text type so this test does not inherit the workspace grammar's .txt association.
		// It has no grammar binding: only the file choice can supply folding rules.
		final var typeManager = Platform.getContentTypeManager();
		final var plainText = typeManager.addContentType("org.eclipse.tm4e.tests.file-plain." + id, "Plain file test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		types.add(plainText);
		plainText.addFileSpec("file-language-" + id, IContentType.FILE_EXTENSION_SPEC);
		final var file = createFile("folding.file-language-" + id, "BEGIN\nbody\nEND\n");
		assertThat(FoldingSupport.getFoldingRules(ContentTypeHelper.findContentTypesByFileName(file.getName()))).isNull();
		final var choice = new Language(fileGrammar.getScope().getQualifiedName(), null);
		assertThat(FileLanguageSelection.getAvailableLanguages()).contains(choice);
		FileLanguageSelection.setLanguage(file, choice);
		final var openEditor = openEditor(file);
		assertThat(TMPresentationReconciler.getTMPresentationReconciler(openEditor)).isNotNull();
		final var document = openEditor.getDocumentProvider().getDocument(openEditor.getEditorInput());
		assertThat(castNonNull(ContentTypeHelper.findContentTypes(document)).getContentTypes()).isEmpty();
		assertThat(FoldingSupport.getFoldingRules(document)).isNotNull();
		assertInsertion(document, "(", "(");
		final var viewer = (ProjectionViewer) openEditor.getAdapter(ITextOperationTarget.class);
		// Verify actual feature installation: resolving folding rules alone would miss the editor's enablement check.
		TestUtils.waitForAndAssertCondition(5_000, () -> castNonNull(viewer).getProjectionAnnotationModel()
				.getAnnotationIterator().hasNext());
	}

	@Test
	void removedImportFallsBackWithoutSelectingABundledGrammarWithTheSameScope() throws Exception {
		final var imported = registerGrammar("source.objc");
		final var file = createFile("routine.m", "body\n");
		bind(fileType, imported);
		final var choice = new Language(imported.getScope().getQualifiedName(), fileType.getId());
		assertThat(FileLanguageSelection.getAvailableLanguages()).contains(choice);
		FileLanguageSelection.setLanguage(file, choice);
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var session = registry.newEditSession();
		session.unregisterGrammarDefinition(imported);
		session.save();
		assertThat(Arrays.stream(registry.getDefinitions()).anyMatch(definition -> definition.getScope().getQualifiedName()
				.equals("source.objc@org.eclipse.tm4e.language_pack"))).isTrue();
		assertThat(FileLanguageSelection.getSavedLanguage(file)).isNull();
		assertLanguage(connect(file), workspaceGrammar.getScope().getQualifiedName(), workspaceType);
	}

	private void chooseImportedLanguage(final IFile file) throws Exception {
		bind(fileType, fileGrammar);
		final var choice = new Language(fileGrammar.getScope().getQualifiedName(), fileType.getId());
		assertThat(FileLanguageSelection.getAvailableLanguages()).contains(choice);
		FileLanguageSelection.setLanguage(file, choice);
		// Removing the workspace binding must preserve the file's own grammar/type pair.
		bind(fileType, null);
	}

	private GrammarDefinition registerGrammar(final String scope) throws Exception {
		// NOTE: RawGrammar reads foldingEndMarker instead of TextMate's foldingStopMarker. Supply both until that parser
		// issue is fixed; this test covers selecting and installing folding, not grammar format compatibility.
		final var file = createFile("grammar-" + grammars.size() + ".json", """
			{"scopeName":"%s","patterns":[],"foldingStartMarker":"^BEGIN$",
			 "foldingStopMarker":"^END$","foldingEndMarker":"^END$"}
			""".formatted(scope));
		final var definition = new GrammarDefinition(scope, castNonNull(file.getLocation()).toOSString());
		grammars.add(definition);
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		session.registerGrammarDefinition(definition);
		session.save();
		return definition;
	}

	private void registerConfiguration(final IContentType type, final String json) throws Exception {
		final var file = createFile("configuration-" + configurations.size() + ".json", json);
		final var definition = new LanguageConfigurationDefinition(type, castNonNull(file.getLocation()).toOSString());
		configurations.add(definition);
		final var session = LanguageConfigurationRegistryManager.getInstance().newEditSession();
		session.registerLanguageConfigurationDefinition(definition);
		session.save();
	}

	private void bind(final IContentType type, final @Nullable GrammarDefinition grammar) throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		session.setUserGrammarBinding(type, grammar);
		session.save();
	}

	private IFile createFile(final String name, final String contents) throws Exception {
		final var file = project.getFile(name);
		file.create(new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)), true, null);
		return file;
	}

	private IDocument connect(final IFile file) throws Exception {
		final var buffers = FileBuffers.getTextFileBufferManager();
		buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
		connected.add(file);
		return castNonNull(buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE)).getDocument();
	}

	private IDocument reopen(final IFile file) throws Exception {
		FileBuffers.getTextFileBufferManager().disconnect(file.getFullPath(), LocationKind.IFILE, null);
		connected.remove(file);
		return connect(file);
	}

	private ITextEditor openEditor(final IFile file) throws Exception {
		final var openEditor = (ITextEditor) IDE.openEditor(castNonNull(UI.getActivePage()), file,
				"org.eclipse.ui.genericeditor.GenericEditor");
		editor = openEditor;
		return openEditor;
	}

	private static void assertLanguage(final IDocument document, final String scope, final IContentType type) {
		assertThat(castNonNull(GrammarUtils.findGrammar(document)).getScopeName()).isEqualTo(scope);
		assertThat(castNonNull(ContentTypeHelper.findContentTypes(document)).getContentTypes()).containsExactly(type);
	}

	private static void assertInsertion(final IDocument document, final String typed, final String expected) {
		final var command = new DocumentCommand() {
		};
		// At EOF, existing text cannot suppress auto-closing through autoCloseBefore rules.
		command.offset = document.getLength();
		command.length = 0;
		command.text = typed;
		new LanguageConfigurationAutoEditStrategy().customizeDocumentCommand(document, command);
		assertThat(command.text).isEqualTo(expected);
	}
}
