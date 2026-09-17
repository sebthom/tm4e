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

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.ITMScope;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Edits grammar imports and content-type bindings without changing the live registry until the session is saved.
 */
class WorkingCopyGrammarRegistryManager extends AbstractGrammarRegistryManager implements IGrammarRegistryManager.EditSession {

	private final GrammarRegistryManager manager;
	// The first import wins when several grammars share a scope, so saving must keep insertion order.
	private final Set<IGrammarDefinition> added = new LinkedHashSet<>();
	private final Set<IGrammarDefinition> removed = new HashSet<>();
	private final Set<String> changedBindingIds = new HashSet<>();
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

		added.clear();
		removed.clear();
		changedBindingIds.clear();
		isDirty = false;
	}

	@Override
	public void setUserGrammarBinding(final IContentType contentType, final @Nullable IGrammarDefinition definition) {
		final String contentTypeId = contentType.getId();
		if (definition == null) {
			userContentTypeToScopeBindings.remove(contentTypeId);
		} else {
			final var selected = userDefinitions.getBestForScope(definition.getScope().getName());
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

		// Register replacements first: removing the last import for a scope also removes its saved bindings.
		added.forEach(manager::registerGrammarDefinition);
		removed.forEach(manager::unregisterGrammarDefinition);
		// Apply only this session's edits so saving an older preferences page does not erase other saved bindings.
		for (final String contentTypeId : changedBindingIds) {
			final String scope = userContentTypeToScopeBindings.get(contentTypeId);
			if (scope == null) {
				manager.userContentTypeToScopeBindings.remove(contentTypeId);
			} else {
				manager.userContentTypeToScopeBindings.put(contentTypeId, scope);
			}
		}

		manager.save();
		reset();
	}
}
