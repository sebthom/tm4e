/**
 * Copyright (c) 2015, 2021 Angelo ZERR and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * - Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 * - Sebastian Thomschke (Vegard IT) - code cleanup, refactoring, simplification
 */
package org.eclipse.tm4e.registry.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.registry.GrammarContentType;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.ITMScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Edits imports, matching-file suggestions, bindings, and managed content types without changing the live registry until saved.
 * Merges with other saved sessions and rolls back platform changes if saving the registry fails.
 */
class WorkingCopyGrammarRegistryManager extends AbstractGrammarRegistryManager implements IGrammarRegistryManager.EditSession {

	private final GrammarRegistryManager manager;
	// The first import wins when several grammars share a scope, so saving must keep insertion order.
	private final Set<IGrammarDefinition> added = new LinkedHashSet<>();
	private final Set<IGrammarDefinition> removed = new HashSet<>();
	private final Set<String> changedBindingIds = new HashSet<>();
	private final Set<String> changedContentTypeSources = new LinkedHashSet<>();
	private final Set<String> changedFileAssociationSources = new LinkedHashSet<>();
	private final Map<String, List<String>> originalGrammarFileAssociations = new HashMap<>();
	private boolean isDirty = false;

	WorkingCopyGrammarRegistryManager(final GrammarRegistryManager manager) {
		this.manager = manager;
		reset();
	}

	@Override
	public void reset() {
		pluginDefinitions.copyFrom(manager.pluginDefinitions);
		userDefinitions.copyFrom(manager.userDefinitions);
		contentTypeToScopeBindings.clear();
		contentTypeToScopeBindings.putAll(manager.contentTypeToScopeBindings);
		userContentTypeToScopeBindings.clear();
		userContentTypeToScopeBindings.putAll(manager.userContentTypeToScopeBindings);
		grammarContentTypes.clear();
		grammarContentTypes.putAll(manager.grammarContentTypes);
		grammarFileAssociations.clear();
		grammarFileAssociations.putAll(manager.grammarFileAssociations);
		originalGrammarFileAssociations.clear();
		originalGrammarFileAssociations.putAll(manager.grammarFileAssociations);

		added.clear();
		removed.clear();
		changedBindingIds.clear();
		changedContentTypeSources.clear();
		changedFileAssociationSources.clear();
		isDirty = false;
	}

	@Override
	public IGrammarDefinition importGrammar(final IGrammarDefinition definition, final @Nullable String name,
			final List<String> fileAssociations) {
		if (definition.getPluginId() != null)
			throw new IllegalArgumentException("Only files can be imported.");
		final String source = sourceKey(definition);
		final Optional<IGrammarDefinition> existing = userDefinitions.stream().filter(item -> sourceKey(item).equals(source)).findFirst();
		final IGrammarDefinition imported = existing.orElse(definition);
		if (!imported.getScope().equals(definition.getScope()))
			throw new IllegalArgumentException("This file was imported with a different scope. Remove the old import first.");
		fileAssociations.forEach(GrammarContentType::fileSpecType);
		final List<String> savedFileAssociations = List.copyOf(fileAssociations);
		final GrammarContentType setup;
		if (name == null) {
			setup = null;
		} else {
			final GrammarContentType previous = grammarContentTypes.get(source);
			setup = new GrammarContentType(previous == null ? "org.eclipse.tm4e.registry.imported." + UUID.randomUUID() : previous.id(),
					previous == null ? name : previous.name(), fileAssociations);
			@SuppressWarnings("unused")
			final var change = new GrammarContentTypeChange(setup, manager.grammarContentTypes.get(source));
			GrammarContentTypeChange.checkConflicts(this, imported, setup, changedContentTypeSources);
		}
		// Validate everything before changing the session, so a failed Finish does not leave a partial import behind.
		if (existing.isEmpty())
			registerGrammarDefinition(imported);
		if (!savedFileAssociations.equals(grammarFileAssociations.get(source))) {
			grammarFileAssociations.put(source, savedFileAssociations);
			changedFileAssociationSources.add(source);
			isDirty = true;
		}
		if (setup != null) {
			grammarContentTypes.put(source, setup);
			changedContentTypeSources.add(source);
			userContentTypeToScopeBindings.put(setup.id(), imported.getScope().getName());
			changedBindingIds.add(setup.id());
			isDirty = true;
		}
		return imported;
	}

	@Override
	public void setUserGrammarBinding(final IContentType contentType, final @Nullable IGrammarDefinition definition) {
		final String contentTypeId = contentType.getId();
		if (definition == null) {
			userContentTypeToScopeBindings.remove(contentTypeId);
		} else {
			final IGrammarDefinition selected = userDefinitions.getBestForScope(definition.getScope().getName());
			if (definition.getPluginId() != null || selected == null)
				throw new IllegalArgumentException("The binding must refer to a registered imported grammar.");
			// Bindings store only the scope name, so choosing another file with the same scope would still load the first import.
			// Compare source URIs so importing the same file twice does not create a false conflict.
			if (!selected.getURI().equals(definition.getURI()))
				throw new IllegalArgumentException("Remove the other imported grammars with scope '" + definition.getScope().getName()
						+ "' before binding this grammar.");
			userContentTypeToScopeBindings.put(contentTypeId, definition.getScope().getName());
		}
		changedBindingIds.add(contentTypeId);
		isDirty = true;
	}

	@Override
	public @Nullable Collection<String> getInjections(final String scopeName) {
		return manager.getInjections(scopeName);
	}

	@Override
	public @Nullable Collection<String> getInjections(final ITMScope scope) {
		return manager.getInjections(scope);
	}

	@Override
	public void registerGrammarDefinition(final IGrammarDefinition definition) {
		if (definition.getPluginId() == null) {
			final var existing = userDefinitions.stream().filter(item -> sourceKey(item).equals(sourceKey(definition))).findFirst();
			if (existing.isPresent()) {
				// Reimporting must keep the original entry and its priority among grammars sharing a scope.
				if (!existing.get().getScope().equals(definition.getScope()))
					throw new IllegalArgumentException("This file was imported with a different scope. Remove the old import first.");
				return;
			}
		}
		super.registerGrammarDefinition(definition);
		removed.remove(definition);
		added.add(definition);
		isDirty = true;
	}

	@Override
	public void unregisterGrammarDefinition(final IGrammarDefinition definition) {
		super.unregisterGrammarDefinition(definition);
		added.remove(definition);
		removed.add(definition);
		isDirty = true;
	}

	@Override
	public void save() throws BackingStoreException {
		if (!isDirty)
			return;

		// Merge into a fresh snapshot so failures cannot publish half a registry update or erase another session's edits.
		final var candidate = new WorkingCopyGrammarRegistryManager(manager);
		final var applied = new ArrayList<GrammarContentTypeChange>();
		try {
			// Remove entries before deduplicating additions: Remove followed by Add creates a new instance for the same URI.
			// Defer binding cleanup until replacements are present, so a scope supplied by a replacement keeps its bindings.
			for (final IGrammarDefinition definition : removed) {
				(definition.getPluginId() == null ? candidate.userDefinitions : candidate.pluginDefinitions).remove(definition);
			}
			// Recheck source conflicts in this snapshot because another session may have saved the same file first.
			added.forEach(candidate::registerGrammarDefinition);
			candidate.userContentTypeToScopeBindings.values().removeIf(scope -> candidate.userDefinitions.getBestForScope(scope) == null);
			// Apply only this session's edits so saving an older preferences page does not erase other saved bindings.
			for (final String contentTypeId : changedBindingIds) {
				final String scope = userContentTypeToScopeBindings.get(contentTypeId);
				if (scope == null) {
					candidate.userContentTypeToScopeBindings.remove(contentTypeId);
				} else {
					final IGrammarDefinition selected = userDefinitions.getBestForScope(scope);
					final IGrammarDefinition merged = candidate.userDefinitions.getBestForScope(scope);
					// A new binding must still select the file this session chose after other imports are merged.
					// Unchanged bindings can follow intentional replacements, so only validate this session's binding edits.
					if (selected == null || merged == null || !sourceKey(selected).equals(sourceKey(merged)))
						throw new IllegalArgumentException("The imported grammar for scope '" + scope
								+ "' changed in another dialog. Reopen the preferences to select its binding again.");
					candidate.userContentTypeToScopeBindings.put(contentTypeId, scope);
				}
			}
			for (final String source : changedContentTypeSources) {
				final GrammarContentType setup = grammarContentTypes.get(source);
				if (setup != null)
					candidate.grammarContentTypes.put(source, setup);
			}
			final List<String> activeSources = candidate.userDefinitions.stream().map(AbstractGrammarRegistryManager::sourceKey).toList();
			for (final String source : changedFileAssociationSources) {
				// A stale dialog must not silently overwrite suggestions saved by another dialog for the same grammar.
				if (!Objects.equals(manager.grammarFileAssociations.get(source), originalGrammarFileAssociations.get(source)))
					throw new IllegalArgumentException(
							"The matching files for this grammar changed in another dialog. Reopen the import to edit them again.");
				if (!activeSources.contains(source)) {
					final boolean removedHere = removed.stream().map(AbstractGrammarRegistryManager::sourceKey)
							.anyMatch(source::equals);
					if (removedHere)
						continue;
					throw new IllegalArgumentException(
							"This grammar was removed in another dialog. Reopen the import before editing its matching files.");
				}
				candidate.grammarFileAssociations.put(source, Objects.requireNonNull(grammarFileAssociations.get(source)));
			}
			// Published types can be referenced by per-file choices, editing rules, or other plugins. There is no complete
			// reference query, so removing an import removes its binding/ownership metadata and retains the platform type.
			candidate.grammarContentTypes.keySet().retainAll(activeSources);
			candidate.grammarFileAssociations.keySet().retainAll(activeSources);
			for (final String source : changedContentTypeSources) {
				final GrammarContentType setup = candidate.grammarContentTypes.get(source);
				if (setup == null)
					continue;
				final IGrammarDefinition definition = candidate.userDefinitions.stream().filter(item -> sourceKey(item).equals(source))
						.findFirst()
						.orElseThrow();
				GrammarContentTypeChange.checkConflicts(candidate, definition, setup, changedContentTypeSources);
				final var change = new GrammarContentTypeChange(setup, manager.grammarContentTypes.get(source));
				// Record before applying: Eclipse can throw after it has already changed its in-memory catalog.
				applied.add(change);
				change.apply();
			}
			PreferenceHelper.saveGrammars(candidate.userDefinitions.stream().toList(), candidate.userContentTypeToScopeBindings,
					candidate.grammarContentTypes, candidate.grammarFileAssociations);
		} catch (final CoreException | BackingStoreException | RuntimeException ex) {
			for (final GrammarContentTypeChange change : applied.reversed()) {
				try {
					change.rollback();
				} catch (final CoreException | RuntimeException rollbackFailure) {
					ex.addSuppressed(rollbackFailure);
				}
			}
			if (ex instanceof final BackingStoreException failure)
				throw failure;
			final var failure = new BackingStoreException("Cannot save grammar setup: " + ex.getMessage());
			failure.initCause(ex);
			throw failure;
		}
		manager.userDefinitions.copyFrom(candidate.userDefinitions);
		manager.pluginDefinitions.copyFrom(candidate.pluginDefinitions);
		manager.userContentTypeToScopeBindings.clear();
		manager.userContentTypeToScopeBindings.putAll(candidate.userContentTypeToScopeBindings);
		manager.grammarContentTypes.clear();
		manager.grammarContentTypes.putAll(candidate.grammarContentTypes);
		manager.grammarFileAssociations.clear();
		manager.grammarFileAssociations.putAll(candidate.grammarFileAssociations);
		reset();
	}
}
