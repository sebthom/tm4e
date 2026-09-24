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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.utils.CodeTemplateContextTypeUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Exercises explicit grammar choices, inherited file associations, and template context lookup.
 * Each test owns its temporary content types and grammar files, leaving other workspace preferences intact.
 */
class UserGrammarBindingTest {

	private final List<IContentType> contentTypes = new ArrayList<>();
	private final List<IGrammarDefinition> grammars = new ArrayList<>();
	private final List<Path> files = new ArrayList<>();

	@AfterEach
	void cleanUp() throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		for (final var grammar : grammars) {
			session.unregisterGrammarDefinition(grammar);
		}
		session.save();
		for (final var contentType : contentTypes.reversed()) {
			Platform.getContentTypeManager().removeContentType(contentType.getId());
		}
		for (final var file : files) {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void userBindingWinsRegardlessOfBundledCandidateOrder() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var session = registry.newEditSession();
		final var bundledType = bundledType();
		final var customType = createContentType(null);
		final var grammar = createGrammar();
		session.registerGrammarDefinition(grammar);
		// Before an explicit choice, importing a grammar must not silently replace an existing language.
		// Edit sessions own separate grammar caches, so compare the selected scopes rather than object identity.
		assertThat(castNonNull(session.getGrammarFor(bundledType, customType)).getScopeName())
				.isEqualTo(castNonNull(registry.getGrammarFor(bundledType)).getScopeName());
		session.setUserGrammarBinding(customType, grammar);
		final var expected = session.getGrammarForScope(grammar.getScope());
		assertThat(expected).isNotNull();
		assertThat(session.getGrammarFor(bundledType, customType)).isSameAs(expected);
		assertThat(session.getGrammarFor(customType, bundledType)).isSameAs(expected);
		assertThat(session.getGrammarForFileExtension("ts")).isSameAs(expected);
		assertThat(session.getEffectiveContentTypes(bundledType, customType)).containsExactly(customType);
		assertThat(session.getContentTypesForScope(grammar.getScope())).containsExactly(customType);
		assertThat(registry.getUserGrammarBinding(customType)).isNull();
	}

	@Test
	void extensionLookupIncludesInheritedFileAssociations() throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		// NOTE: The oldest supported Eclipse caches child lists across runtime changes. A fresh parent avoids stale test state.
		final var parent = createContentType(null);
		final var child = createContentType(parent);
		// The fixture normally declares "ts" itself. Remove it so only the parent's association can match.
		child.removeFileSpec("ts", IContentType.FILE_EXTENSION_SPEC);
		assertThat(Platform.getContentTypeManager().findContentTypesFor("inherited.ts")).contains(child);
		final var grammar = createGrammar();
		session.registerGrammarDefinition(grammar);
		session.setUserGrammarBinding(child, grammar);
		assertThat(session.getGrammarForFileExtension("ts")).isSameAs(session.getGrammarFor(child));
	}

	@Test
	void templateContextStillResolvesAfterBindingChangesItsDisplayName() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var grammar = createGrammar();
		final var session = registry.newEditSession();
		session.registerGrammarDefinition(grammar);
		session.save();
		final var plugin = castNonNull(TMUIPlugin.getDefault());
		// Start the template cache after this import regardless of test order, then restore the previous cache.
		final var cacheField = TMUIPlugin.class.getDeclaredField("contextTypeRegistry");
		cacheField.setAccessible(true);
		final var previousCache = cacheField.get(plugin);
		cacheField.set(plugin, null);
		try {
			final var context = castNonNull(plugin.getTemplateContextRegistry()
					.getContextType(CodeTemplateContextTypeUtils.toContextTypeId(grammar.getScope())));
			final String cachedName = context.getName();
			final var expected = registry.getGrammarForScope(grammar.getScope());
			assertThat(expected).isNotNull();
			assertThat(CodeTemplateContextTypeUtils.toGrammar(cachedName)).isSameAs(expected);
			session.setUserGrammarBinding(createContentType(null), grammar);
			session.save();
			assertThat(CodeTemplateContextTypeUtils.toContextTypeName(grammar.getScope())).isNotEqualTo(cachedName);
			assertThat(CodeTemplateContextTypeUtils.toGrammar(cachedName)).isSameAs(expected);
		} finally {
			cacheField.set(plugin, previousCache);
		}
	}

	@Test
	void inheritedBindingSelectsItsOwnLanguageConfigurationType() throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		final var parent = createContentType(null);
		final var child = createContentType(parent);
		final var grammar = createGrammar();
		session.registerGrammarDefinition(grammar);
		session.setUserGrammarBinding(parent, grammar);
		assertThat(session.getGrammarFor(bundledType(), child)).isSameAs(session.getGrammarForScope(grammar.getScope()));
		assertThat(session.getEffectiveContentTypes(bundledType(), child)).containsExactly(parent);
		final var childGrammar = createGrammar();
		session.registerGrammarDefinition(childGrammar);
		session.setUserGrammarBinding(child, childGrammar);
		assertThat(session.getGrammarFor(child)).isSameAs(session.getGrammarForScope(childGrammar.getScope()));
	}

	@Test
	void removingOrResettingBindingRestoresAutomaticSelection() throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		final var customType = createContentType(null);
		final var grammar = createGrammar();
		session.registerGrammarDefinition(grammar);
		session.setUserGrammarBinding(customType, grammar);
		session.setUserGrammarBinding(customType, null);
		assertThat(session.getEffectiveContentTypes(bundledType(), customType)).containsExactly(bundledType(), customType);
		session.setUserGrammarBinding(customType, grammar);
		session.unregisterGrammarDefinition(grammar);
		assertThat(session.getUserGrammarBinding(customType)).isNull();
		assertThat(session.getGrammarFor(bundledType(), customType)).isNotNull();
		session.reset();
		assertThat(session.getContentTypesForScope(grammar.getScope())).isEmpty();
	}

	@Test
	void savedBindingsSurviveReloadAndGrammarRemoval() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var customType = createContentType(null);
		final var grammar = createGrammar();
		var session = registry.newEditSession();
		session.registerGrammarDefinition(grammar);
		session.setUserGrammarBinding(customType, grammar);
		session.save();
		final var restored = reloadRegistry();
		assertThat(castNonNull(restored.getUserGrammarBinding(customType)).getPath()).isEqualTo(grammar.getPath());
		assertThat(restored.getGrammarFor(bundledType(), customType)).isSameAs(restored.getGrammarForScope(grammar.getScope()));
		session = registry.newEditSession();
		session.unregisterGrammarDefinition(grammar);
		session.save();
		assertThat(reloadRegistry().getUserGrammarBinding(customType)).isNull();
	}

	@Test
	void resetAndIndependentSessionsDoNotLeakChanges() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var firstType = createContentType(null);
		final var secondType = createContentType(null);
		final var grammar = createGrammar();
		final var initial = registry.newEditSession();
		initial.registerGrammarDefinition(grammar);
		initial.save();
		final var cancelled = registry.newEditSession();
		// Importing the same scope exercises the mutable definition list that used to be shared with the live registry.
		cancelled.registerGrammarDefinition(grammar);
		cancelled.setUserGrammarBinding(firstType, grammar);
		assertThat(registry.getDefinitions()).filteredOn(def -> def.getScope().equals(grammar.getScope())).hasSize(1);
		cancelled.reset();
		assertThat(cancelled.getUserGrammarBinding(firstType)).isNull();
		assertThat(cancelled.getDefinitions()).filteredOn(def -> def.getScope().equals(grammar.getScope())).hasSize(1);

		final var first = registry.newEditSession();
		final var second = registry.newEditSession();
		first.setUserGrammarBinding(firstType, grammar);
		second.setUserGrammarBinding(secondType, grammar);
		first.save();
		second.save();
		assertThat(registry.getUserGrammarBinding(firstType)).isSameAs(grammar);
		assertThat(registry.getUserGrammarBinding(secondType)).isSameAs(grammar);
		assertThat(registry.getEffectiveContentTypes(secondType, firstType)).containsExactly(secondType);
		assertThat(registry.getEffectiveContentTypes(firstType, secondType)).containsExactly(firstType);
	}

	@Test
	void replacingUserBindingKeepsContributedScopeMapping() throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		final var contributedGrammar = castNonNull(session.getGrammarFor(bundledType()));
		final var first = createGrammar();
		final var second = createGrammar();
		session.registerGrammarDefinition(first);
		session.registerGrammarDefinition(second);
		session.setUserGrammarBinding(bundledType(), first);
		session.setUserGrammarBinding(bundledType(), second);
		assertThat(session.getUserGrammarBinding(bundledType())).isSameAs(second);
		assertThat(session.getContentTypesForScope(first.getScope())).isEmpty();
		assertThat(session.getContentTypesForScope(ITMScope.parse(contributedGrammar.getScopeName()))).contains(bundledType());
		session.setUserGrammarBinding(bundledType(), null);
		assertThat(session.getGrammarFor(bundledType())).isSameAs(contributedGrammar);
	}

	@Test
	void bindingCannotSilentlySelectAnotherImportWithTheSameScope() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var type = createContentType(null);
		final var first = createGrammar();
		var session = registry.newEditSession();
		session.registerGrammarDefinition(first);
		session.setUserGrammarBinding(type, first);
		session.save();
		final var second = createGrammar(first.getScope().getName());
		session = registry.newEditSession();
		session.registerGrammarDefinition(second);
		final var edit = session;
		// Scope-based lookup can only select the first import. Reject the other selection instead of binding the wrong file.
		assertThatIllegalArgumentException().isThrownBy(() -> edit.setUserGrammarBinding(type, second));
		assertThat(session.getUserGrammarBinding(type)).isSameAs(first);
		session.save();
		assertThat(castNonNull(reloadRegistry().getUserGrammarBinding(type)).getURI()).isEqualTo(first.getURI());
	}

	@Test
	void savingBindingRechecksTheSourceAfterMergingImports() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var type = createContentType(null);
		final var first = createGrammar();
		final var selected = createGrammar(first.getScope().getName());
		final var pending = registry.newEditSession();
		pending.registerGrammarDefinition(selected);
		pending.setUserGrammarBinding(type, selected);
		final var savedFirst = registry.newEditSession();
		savedFirst.registerGrammarDefinition(first);
		savedFirst.save();

		// An ordinary binding has no managed content-type setup to perform this conflict check for it.
		assertThatThrownBy(pending::save).isInstanceOf(BackingStoreException.class);
		assertThat(registry.getDefinitions()).contains(first).doesNotContain(selected);
		assertThat(registry.getUserGrammarBinding(type)).isNull();
		assertThat(reloadRegistry().getUserGrammarBinding(type)).isNull();
		assertThat(pending.getUserGrammarBinding(type)).isSameAs(selected);

		// Keep the edit usable after failure: the user can explicitly remove the competing import and retry.
		pending.unregisterGrammarDefinition(first);
		pending.save();
		assertThat(registry.getUserGrammarBinding(type)).isSameAs(selected);
	}

	@Test
	void savingBindingRejectsAnImportRemovedByAnotherSession() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var type = createContentType(null);
		final var grammar = createGrammar();
		final var initial = registry.newEditSession();
		initial.registerGrammarDefinition(grammar);
		initial.save();
		final var pending = registry.newEditSession();
		pending.setUserGrammarBinding(type, grammar);
		final var removal = registry.newEditSession();
		removal.unregisterGrammarDefinition(grammar);
		removal.save();

		// Saving a dangling scope would silently revive the binding if an unrelated file later reused that scope.
		assertThatThrownBy(pending::save).isInstanceOf(BackingStoreException.class);
		assertThat(registry.getDefinitions()).doesNotContain(grammar);
		assertThat(registry.getUserGrammarBinding(type)).isNull();
		assertThat(pending.getUserGrammarBinding(type)).isSameAs(grammar);
	}

	@Test
	void removingEarlierImportAllowsBindingItsReplacement() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var type = createContentType(null);
		final var first = createGrammar();
		var session = registry.newEditSession();
		session.registerGrammarDefinition(first);
		session.setUserGrammarBinding(type, first);
		session.save();
		final var originalGrammar = castNonNull(registry.getGrammarFor(type));
		final var replacement = createGrammar(first.getScope().getName());
		// A different file can be older. Changing the selected source must not depend on timestamps or the reload interval.
		Files.setLastModifiedTime(Path.of(replacement.getPath()), FileTime.fromMillis(first.getLastModified() - 60_000));
		session = registry.newEditSession();
		session.registerGrammarDefinition(replacement);
		session.unregisterGrammarDefinition(first);
		session.setUserGrammarBinding(type, replacement);
		session.save();
		assertThat(registry.getUserGrammarBinding(type)).isSameAs(replacement);
		assertThat(castNonNull(registry.getGrammarFor(type)).getName()).isNotEqualTo(originalGrammar.getName());
		assertThat(castNonNull(reloadRegistry().getUserGrammarBinding(type)).getURI()).isEqualTo(replacement.getURI());
	}

	@Test
	void replacingImportPreservesAnUnchangedBinding() throws Exception {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var type = createContentType(null);
		final var first = createGrammar();
		var session = registry.newEditSession();
		session.registerGrammarDefinition(first);
		session.setUserGrammarBinding(type, first);
		session.save();

		final var replacement = createGrammar(first.getScope().getName());
		session = registry.newEditSession();
		session.registerGrammarDefinition(replacement);
		session.unregisterGrammarDefinition(first);
		assertThat(session.getUserGrammarBinding(type)).isSameAs(replacement);
		// The user only replaces the import. Setting the binding again would hide its loss during save.
		session.save();
		assertThat(registry.getUserGrammarBinding(type)).isSameAs(replacement);
		assertThat(castNonNull(reloadRegistry().getUserGrammarBinding(type)).getURI()).isEqualTo(replacement.getURI());
	}

	@Test
	void unavailableImportDoesNotOverrideContentTypes() throws Exception {
		final var session = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		final var type = createContentType(null);
		final var grammar = createGrammar();
		Files.delete(Path.of(grammar.getPath()));
		session.registerGrammarDefinition(grammar);
		session.setUserGrammarBinding(type, grammar);
		assertThat(session.getEffectiveContentTypes(bundledType(), type)).containsExactly(bundledType(), type);
		assertThat(session.getGrammarFor(bundledType(), type)).isNotNull();
	}

	private IGrammarRegistryManager reloadRegistry() throws Exception {
		// Exercise the real preference loader without replacing the singleton used by other tests and editor services.
		final var implementation = TMEclipseRegistryPlugin.getGrammarRegistryManager().getClass();
		final var constructor = implementation.getDeclaredConstructor();
		constructor.setAccessible(true);
		final var restored = constructor.newInstance();
		final var load = implementation.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(restored);
		return restored;
	}

	private IContentType bundledType() {
		return castNonNull(Platform.getContentTypeManager().getContentType("org.eclipse.tm4e.ui.tests.testContentType"));
	}

	private IContentType createContentType(final @Nullable IContentType base) throws Exception {
		final var manager = Platform.getContentTypeManager();
		final var type = manager.addContentType("org.eclipse.tm4e.tests." + UUID.randomUUID(), "Grammar binding test",
				base == null ? manager.getContentType("org.eclipse.core.runtime.text") : base);
		contentTypes.add(type);
		type.addFileSpec("ts", IContentType.FILE_EXTENSION_SPEC);
		return type;
	}

	private IGrammarDefinition createGrammar() throws Exception {
		return createGrammar("source.tm4e-test-" + UUID.randomUUID());
	}

	private IGrammarDefinition createGrammar(final String scope) throws Exception {
		final var file = Files.createTempFile("tm4e-grammar-binding-", ".json");
		files.add(file);
		Files.writeString(file, "{\"scopeName\":\"" + scope + "\",\"name\":\"" + file.getFileName()
				+ "\",\"fileTypes\":[\"ts\"],\"patterns\":[]}");
		final var grammar = new GrammarDefinition(scope, file.toString());
		grammars.add(grammar);
		return grammar;
	}
}
