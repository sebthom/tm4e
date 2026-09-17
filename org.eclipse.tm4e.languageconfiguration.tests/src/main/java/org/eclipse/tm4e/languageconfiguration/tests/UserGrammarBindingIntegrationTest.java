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
import java.util.UUID;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.languageconfiguration.internal.LanguageConfigurationAutoEditStrategy;
import org.eclipse.tm4e.languageconfiguration.internal.LanguageConfigurationCharacterPairMatcher;
import org.eclipse.tm4e.languageconfiguration.internal.folding.FoldingSupport;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationDefinition;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeInfo;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection.Language;
import org.eclipse.tm4e.ui.internal.utils.GrammarUtils;
import org.eclipse.tm4e.ui.tests.support.TestUtils;
import org.eclipse.tm4e.ui.text.TMPartitions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checks document language selection, including explicit grammar choices and partition-aware editing rules.
 * Uses real Eclipse file buffers to cover ambiguous extensions, embedded languages, and cached language selection.
 */
class UserGrammarBindingIntegrationTest {

	@Test
	void repeatedEditsReuseDetectedContentTypes() throws Exception {
		final var project = ResourcesPlugin.getWorkspace().getRoot().getProject("content-detection-" + UUID.randomUUID());
		final var buffers = FileBuffers.getTextFileBufferManager();
		@Nullable
		IFile connectedFile = null;
		try {
			project.create(null);
			project.open(null);
			final var file = createFile(project, "example.lc-counted", "let n = 1;\n");
			buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
			connectedFile = file;
			final var buffer = castNonNull(buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE));
			final var document = buffer.getDocument();
			// Unsaved changes force detection to use the document instead of a cached file description.
			document.replace(0, 0, " ");
			assertThat(buffer.isDirty()).isTrue();
			TestUtils.waitForAndAssertCondition(5_000, () -> TMPartitions.getPartition(document, document.getLength()) != null);
			final var strategy = new LanguageConfigurationAutoEditStrategy();
			final var command = new DocumentCommand() {
			};
			command.offset = document.getLength();
			command.length = 0;
			command.text = "x";
			CountingContentDescriber.CALLS.set(0);
			strategy.customizeDocumentCommand(document, command);
			final int initialCalls = CountingContentDescriber.CALLS.get();
			assertThat(initialCalls).as("The fixture must exercise content detection").isPositive();
			// The first command initializes the cache. Avoid UI dispatch here so background editor work cannot affect the count.
			for (int index = 0; index < 3; index++) {
				strategy.customizeDocumentCommand(document, command);
			}
			assertThat(CountingContentDescriber.CALLS.get()).isEqualTo(initialCalls);
		} finally {
			if (connectedFile != null) {
				buffers.disconnect(connectedFile.getFullPath(), LocationKind.IFILE, null);
			}
			if (project.exists()) {
				project.delete(true, true, null);
			}
		}
	}

	@Test
	void mumpsChoiceKeepsHighlightingAndEditingRulesTogether() throws Exception {
		final var typeManager = Platform.getContentTypeManager();
		final var objectiveC = castNonNull(typeManager.getContentType("org.eclipse.tm4e.language_pack.objective-c"));
		final var mumps = typeManager.addContentType("org.eclipse.tm4e.tests.mumps." + UUID.randomUUID(), "Mumps test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		final var otherType = typeManager.addContentType("org.eclipse.tm4e.tests.other." + UUID.randomUUID(), "Other test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		final var project = ResourcesPlugin.getWorkspace().getRoot().getProject("mumps-binding-" + UUID.randomUUID());
		final var buffers = FileBuffers.getTextFileBufferManager();
		final var grammarRegistry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var configurations = LanguageConfigurationRegistryManager.getInstance();
		@Nullable
		GrammarDefinition grammar = null;
		@Nullable
		LanguageConfigurationDefinition configuration = null;
		@Nullable
		IFile connectedFile = null;
		try {
			mumps.addFileSpec("m", IContentType.FILE_EXTENSION_SPEC);
			project.create(null);
			project.open(null);
			final String scope = "source.mumps-test-" + UUID.randomUUID();
			final var grammarFile = createFile(project, "Mumps.tmLanguage.json",
					"{\"scopeName\":\"" + scope + "\",\"fileTypes\":[\"m\"],\"patterns\":[]}");
			grammar = new GrammarDefinition(scope, castNonNull(grammarFile.getLocation()).toOSString());
			var session = grammarRegistry.newEditSession();
			session.registerGrammarDefinition(grammar);
			session.save();
			final var file = createFile(project, "routine.m", "( ) [ ]");
			buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
			connectedFile = file;
			final var initialDocument = castNonNull(buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE)).getDocument();
			// The imported grammar alone leaves the bundled Objective-C binding in control, as in issue #1042.
			assertThat(GrammarUtils.findGrammar(initialDocument)).isSameAs(grammarRegistry.getGrammarFor(objectiveC));

			session = grammarRegistry.newEditSession();
			session.setUserGrammarBinding(mumps, grammar);
			// The same grammar may also serve a type that does not match this file; its rules must not leak into .m files.
			session.setUserGrammarBinding(otherType, grammar);
			session.save();
			// Disconnect and reconnect to simulate reopening an editor and clear its cached grammar and editing-rule selections.
			buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
			connectedFile = null;
			buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
			connectedFile = file;
			final var document = castNonNull(buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE)).getDocument();
			assertThat(GrammarUtils.findGrammar(document)).isSameAs(grammarRegistry.getGrammarForScope(grammar.getScope()));
			assertThat(castNonNull(ContentTypeHelper.findContentTypes(document)).getContentTypes()).containsExactly(mumps);
			TestUtils.waitForAndAssertCondition(5_000, () -> TMPartitions.getContentTypesForOffset(document, 0).length > 0);
			assertThat(TMPartitions.getContentTypesForOffset(document, 0)).containsExactly(mumps);
			// With no Mumps configuration, unrelated Objective-C rules must not be used as a fallback.
			assertThat(configurations.getLanguageConfigurationFor(objectiveC, mumps)).isNull();
			assertThat(FoldingSupport.getFoldingRules(document)).isNull();
			var matcher = new LanguageConfigurationCharacterPairMatcher();
			try {
				assertThat(matcher.match(document, 1)).isNull();
			} finally {
				matcher.dispose();
			}

			final var configFile = createFile(project, "mumps.language-configuration.json", """
				{
				  "comments": {"lineComment": ";"},
				  "brackets": [["[", "]"]],
				  "autoClosingPairs": [["[", "]"]],
				  "surroundingPairs": [["[", "]"]],
				  "folding": {"offSide": true, "markers": {"start": "^;begin", "end": "^;end"}}
				}
				""");
			configuration = new LanguageConfigurationDefinition(mumps, castNonNull(configFile.getLocation()).toOSString());
			final var configSession = configurations.newEditSession();
			configSession.registerLanguageConfigurationDefinition(configuration);
			configSession.save();
			// Inspect the result without exporting the private folding model package just for this integration test.
			assertThat(FoldingSupport.getFoldingRules(document)).extracting("offSide").isEqualTo(true);
			// Folding can also receive content types before language selection. It must make the same choice as the document helper.
			assertThat(FoldingSupport.getFoldingRules(new ContentTypeInfo("routine.m",
					new IContentType[] { objectiveC, mumps }))).extracting("offSide").isEqualTo(true);
			matcher = new LanguageConfigurationCharacterPairMatcher();
			try {
				assertThat(matcher.match(document, 1)).isNull();
				assertThat(matcher.match(document, 5)).isNotNull();
			} finally {
				matcher.dispose();
			}
			assertInsertion(document, "(", "(");
			assertInsertion(document, "[", "[]");
		} finally {
			if (connectedFile != null) {
				buffers.disconnect(connectedFile.getFullPath(), LocationKind.IFILE, null);
			}
			if (configuration != null) {
				final var session = configurations.newEditSession();
				session.unregisterLanguageConfigurationDefinition(configuration);
				session.save();
			}
			if (grammar != null) {
				final var session = grammarRegistry.newEditSession();
				session.unregisterGrammarDefinition(grammar);
				session.save();
			}
			typeManager.removeContentType(mumps.getId());
			typeManager.removeContentType(otherType.getId());
			if (project.exists()) {
				project.delete(true, true, null);
			}
		}
	}

	@ParameterizedTest
	@CsvSource({
		"js, source.js, false",
		"html, source.js, false",
		"foo, source.js, false",
		"js, source.js.custom, false",
		"html, source.js.custom, false",
		"foo, source.js.custom, false",
		"foo, source.js.custom, true"
	})
	void languageChoiceAppliesOnlyToItsDocument(final String extension, final String scope, final boolean fileChoice) throws Exception {
		final var typeManager = Platform.getContentTypeManager();
		final var javascript = castNonNull(typeManager.getContentType("org.eclipse.tm4e.language_pack.javascript"));
		final var customType = typeManager.addContentType("org.eclipse.tm4e.tests.foo." + UUID.randomUUID(), "Custom JavaScript test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		final var project = ResourcesPlugin.getWorkspace().getRoot().getProject("partition-binding-" + UUID.randomUUID());
		final var buffers = FileBuffers.getTextFileBufferManager();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		@Nullable
		GrammarDefinition grammar = null;
		@Nullable
		IFile connectedFile = null;
		try {
			customType.addFileSpec("foo", IContentType.FILE_EXTENSION_SPEC);
			project.create(null);
			project.open(null);
			// Both scopes produce source.js root partitions. Only .foo should use the selected type's editing rules.
			final var grammarFile = createFile(project, "custom.tmLanguage.json",
					"{\"scopeName\":\"" + scope + "\",\"fileTypes\":[\"foo\"],\"patterns\":[]}");
			grammar = new GrammarDefinition(scope, castNonNull(grammarFile.getLocation()).toOSString());
			final var session = registry.newEditSession();
			session.registerGrammarDefinition(grammar);
			// For file-choice cases, omit the workspace binding so it cannot hide a broken file-choice lookup.
			if (!fileChoice) {
				session.setUserGrammarBinding(customType, grammar);
			}
			session.save();
			final var contents = extension.equals("html") ? "<script>\nlet n = 1;\n</script>" : "let n = 1;\n";
			final var file = createFile(project, "example." + extension, contents);
			if (fileChoice) {
				FileLanguageSelection.setLanguage(file, new Language(scope, customType.getId()));
			}
			buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
			connectedFile = file;
			final var document = castNonNull(buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE)).getDocument();
			final var expectedType = extension.equals("foo") ? customType : javascript;
			if (!extension.equals("html")) {
				assertThat(GrammarUtils.findGrammar(document)).isSameAs(extension.equals("foo")
						? registry.getGrammarForScope(grammar.getScope())
						: registry.getGrammarFor(expectedType));
			}
			// In HTML, inspect the JavaScript region rather than the document's outer language.
			final int offset = contents.indexOf(';') + 1;
			TestUtils.waitForAndAssertCondition(5_000, () -> {
				final var partition = TMPartitions.getPartition(document, offset);
				return partition != null && partition.getGrammarScope().equals("source.js");
			});
			assertThat(TMPartitions.getContentTypesForOffset(document, offset)).containsExactly(expectedType);
			assertInsertion(document, offset, "(", extension.equals("foo") ? "(" : "()");
		} finally {
			if (connectedFile != null) {
				buffers.disconnect(connectedFile.getFullPath(), LocationKind.IFILE, null);
			}
			if (grammar != null) {
				final var session = registry.newEditSession();
				session.unregisterGrammarDefinition(grammar);
				session.save();
			}
			typeManager.removeContentType(customType.getId());
			if (project.exists()) {
				project.delete(true, true, null);
			}
		}
	}

	private static IFile createFile(final IProject project, final String name, final String contents) throws Exception {
		final var file = project.getFile(name);
		file.create(new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)), true, null);
		return file;
	}

	private static void assertInsertion(final IDocument document, final String typed, final String expected) {
		// Type at EOF: the normal autoCloseBefore rule deliberately suppresses closing before the existing '('.
		assertInsertion(document, document.getLength(), typed, expected);
	}

	private static void assertInsertion(final IDocument document, final int offset, final String typed, final String expected) {
		final var command = new DocumentCommand() {
		};
		command.offset = offset;
		command.length = 0;
		command.text = typed;
		new LanguageConfigurationAutoEditStrategy().customizeDocumentCommand(document, command);
		assertThat(command.text).isEqualTo(expected);
	}
}
