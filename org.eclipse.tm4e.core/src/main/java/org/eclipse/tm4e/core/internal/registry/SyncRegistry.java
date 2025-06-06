/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Initial code from https://github.com/microsoft/vscode-textmate/
 * Initial copyright Copyright (C) Microsoft Corporation. All rights reserved.
 * Initial license: MIT
 *
 * Contributors:
 * - Microsoft Corporation: Initial code, written in TypeScript, licensed under MIT license
 * - Angelo Zerr <angelo.zerr@gmail.com> - translation and adaptation to Java
 */
package org.eclipse.tm4e.core.internal.registry;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.internal.grammar.BalancedBracketSelectors;
import org.eclipse.tm4e.core.internal.grammar.Grammar;
import org.eclipse.tm4e.core.internal.grammar.ScopeStack;
import org.eclipse.tm4e.core.internal.grammar.raw.IRawGrammar;
import org.eclipse.tm4e.core.internal.theme.StyleAttributes;
import org.eclipse.tm4e.core.internal.theme.Theme;
import org.eclipse.tm4e.core.internal.utils.ScopeNames;

/**
 * @see <a href=
 *      "https://github.com/microsoft/vscode-textmate/blob/167bbbd509356cc4617f250c0d754aef670ab14a/src/registry.ts#L11">
 *      github.com/microsoft/vscode-textmate/blob/main/src/registry.ts</a>
 */
public class SyncRegistry implements IGrammarRepository, IThemeProvider {

	private final Map<String, Grammar> _grammars = new HashMap<>();
	private final Map<String, @Nullable IRawGrammar> _rawGrammars = new HashMap<>();
	private final Map<String, Collection<String>> _injectionGrammars = new HashMap<>();
	private Theme _theme;

	public SyncRegistry(final Theme theme) {
		this._theme = theme;
	}

	public void setTheme(final Theme theme) {
		this._theme = theme;
	}

	public List<String> getColorMap() {
		return this._theme.getColorMap();
	}

	/**
	 * Add `grammar` to registry and return a list of referenced scope names
	 */
	public void addGrammar(final IRawGrammar grammar, final @Nullable Collection<String> injectionScopeNames) {
		this._rawGrammars.put(grammar.getScopeName(), grammar);
		
		// custom tm4e code, not from upstream:
      // If an IRawGrammar is re-registered under the same scope name,
		// clear any cached Grammar so it will be rebuilt with the new definition
		this._grammars.remove(grammar.getScopeName());

		if (injectionScopeNames != null) {
			this._injectionGrammars.put(grammar.getScopeName(), injectionScopeNames);
		}
	}

	@Override
	public @Nullable IRawGrammar lookup(final String scopeName) {
		IRawGrammar grammar = this._rawGrammars.get(scopeName);
		if (grammar == null) {
			// this code is specific to the tm4e project and not from upstream:
			// if no grammar was found for the given scopeName, check if the scopeName is qualified, e.g. "source.mylang@com.example.mylang.plugin"
			// and if so try the unqualified scopeName "source.mylang" as fallback. See org.eclipse.tm4e.registry.internal.TMScope and
			// for more details org.eclipse.tm4e.registry.internal.AbstractGrammarRegistryManager.getGrammarFor(IContentType...)
			final var scopeNameWithoutContributor = ScopeNames.withoutContributor(scopeName);
			if (!scopeNameWithoutContributor.equals(scopeName))
				grammar = this._rawGrammars.get(scopeNameWithoutContributor);
		}
		return grammar;
	}

	@Override
	public @Nullable Collection<String> injections(final String targetScope) {
		return this._injectionGrammars.get(targetScope);
	}

	/**
	 * Get the default theme settings
	 */
	@Override
	public StyleAttributes getDefaults() {
		return this._theme.getDefaults();
	}

	/**
	 * Match a scope in the theme.
	 */
	@Override
	public @Nullable StyleAttributes themeMatch(final ScopeStack scopePath) {
		return this._theme.match(scopePath);
	}

	/**
	 * Lookup a grammar.
	 */
	public @Nullable IGrammar grammarForScopeName(
			final String scopeName,
			final int initialLanguage,
			final @Nullable Map<String, Integer> embeddedLanguages,
			final @Nullable Map<String, Integer> tokenTypes,
			final @Nullable BalancedBracketSelectors balancedBracketSelectors) {

		return this._grammars.computeIfAbsent(scopeName, scopeName_ -> {
			final var rawGrammar = lookup(scopeName_);
			if (rawGrammar == null) {
				return null;
			}

			return new Grammar(
					scopeName_,
					rawGrammar,
					initialLanguage,
					embeddedLanguages,
					tokenTypes,
					balancedBracketSelectors,
					this,
					this);
		});
	}
}
