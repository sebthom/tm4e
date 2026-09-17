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
import java.util.UUID;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;
import org.eclipse.tm4e.ui.internal.utils.GrammarUtils;
import org.junit.jupiter.api.Test;

/**
 * Tests saved file language choices with real file buffers, independently of workspace file-extension bindings.
 */
class FileLanguageSelectionTest {

	@Test
	void fileChoiceOverridesWorkspaceDefaultForOnlyOneMFile() throws Exception {
		final var typeManager = Platform.getContentTypeManager();
		final var objectiveC = castNonNull(typeManager.getContentType("org.eclipse.tm4e.language_pack.objective-c"));
		final var mumps = typeManager.addContentType("org.eclipse.tm4e.tests.file-mumps." + UUID.randomUUID(), "Mumps file test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		final var project = ResourcesPlugin.getWorkspace().getRoot().getProject("file-language-" + UUID.randomUUID());
		final var buffers = FileBuffers.getTextFileBufferManager();
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var connected = new ArrayList<IFile>();
		@Nullable
		GrammarDefinition imported = null;
		try {
			mumps.addFileSpec("m", IContentType.FILE_EXTENSION_SPEC);
			project.create(null);
			project.open(null);
			final var objectiveCDefinition = Arrays.stream(registry.getDefinitions())
					.filter(definition -> definition.getPluginId() != null
							&& castNonNull(registry.getContentTypesForScope(definition.getScope())).contains(objectiveC))
					.findFirst().orElseThrow();
			final var objectiveCGrammar = registry.getGrammarForScope(objectiveCDefinition.getScope());
			final String scope = "source.file-mumps-" + UUID.randomUUID();
			final var grammarFile = createFile(project, "mumps.tmLanguage.json",
					"{\"scopeName\":\"" + scope + "\",\"patterns\":[]}");
			imported = new GrammarDefinition(scope, castNonNull(grammarFile.getLocation()).toOSString());
			final var session = registry.newEditSession();
			session.registerGrammarDefinition(imported);
			session.setUserGrammarBinding(mumps, imported);
			session.save();

			final var objectiveCFile = createFile(project, "AppDelegate.m", "( ) [ ]");
			final var mumpsFile = createFile(project, "routine.m", "( ) [ ]");
			// Write the saved property directly so the test does not depend on the language selection UI.
			objectiveCFile.setPersistentProperty(new QualifiedName(TMUIPlugin.PLUGIN_ID, "fileLanguage"),
					"{\"scopeName\":\"" + objectiveCDefinition.getScope().getQualifiedName()
							+ "\",\"contentTypeId\":\"" + objectiveC.getId() + "\"}");
			for (final var file : new IFile[] { objectiveCFile, mumpsFile }) {
				buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
				connected.add(file);
			}
			final var objectiveCDocument = castNonNull(buffers.getTextFileBuffer(objectiveCFile.getFullPath(), LocationKind.IFILE))
					.getDocument();
			final var mumpsDocument = castNonNull(buffers.getTextFileBuffer(mumpsFile.getFullPath(), LocationKind.IFILE)).getDocument();
			assertThat(GrammarUtils.findGrammar(objectiveCDocument)).isSameAs(objectiveCGrammar);
			assertThat(castNonNull(ContentTypeHelper.findContentTypes(objectiveCDocument)).getContentTypes()).containsExactly(objectiveC);
			assertThat(GrammarUtils.findGrammar(mumpsDocument)).isSameAs(registry.getGrammarForScope(imported.getScope()));
			assertThat(castNonNull(ContentTypeHelper.findContentTypes(mumpsDocument)).getContentTypes()).containsExactly(mumps);
		} finally {
			for (final var file : connected) {
				buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
			}
			if (imported != null) {
				final var session = registry.newEditSession();
				session.unregisterGrammarDefinition(imported);
				session.save();
			}
			typeManager.removeContentType(mumps.getId());
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
}
