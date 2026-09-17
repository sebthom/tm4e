/**
 * Copyright (c) 2025 Vegard IT GmbH and others.
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
package org.eclipse.tm4e.languageconfiguration.internal.folding;

import java.util.function.Supplier;

import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.core.TMException;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.languageconfiguration.LanguageConfigurationPlugin;
import org.eclipse.tm4e.languageconfiguration.internal.model.FoldingRules;
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration;
import org.eclipse.tm4e.languageconfiguration.internal.model.RegExPattern;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeInfo;
import org.eclipse.tm4e.ui.internal.utils.GrammarUtils;

/**
 * Resolves folding rules from the selected language configuration, falling back to the same language's grammar markers.
 */
public final class FoldingSupport {

	public static @Nullable FoldingRules getFoldingRules(final IDocument doc) {
		final ContentTypeInfo info = ContentTypeHelper.findContentTypes(doc);
		return info == null ? null : getFoldingRules(info);
	}

	public static @Nullable FoldingRules getFoldingRules(final ContentTypeInfo info) {
		final var registry = LanguageConfigurationRegistryManager.getInstance();
		// Reapplying a parent type's workspace binding here would undo a file's explicit language choice.
		final var configuration = info.getExplicitGrammar() == null
				? registry.getLanguageConfigurationFor(info.getContentTypes())
				: registry.getLanguageConfigurationForResolvedTypes(info.getContentTypes());
		return computeFoldingRules(configuration, () -> GrammarUtils.findGrammar(info));
	}

	public static @Nullable FoldingRules getFoldingRules(final IContentType... types) {
		return computeFoldingRules(LanguageConfigurationRegistryManager.getInstance().getLanguageConfigurationFor(types),
				() -> TMEclipseRegistryPlugin.getGrammarRegistryManager().getGrammarFor(types));
	}

	private static @Nullable FoldingRules computeFoldingRules(final @Nullable LanguageConfiguration langCfg,
			final Supplier<@Nullable IGrammar> grammarProvider) {
		/*
		 * 1) try language-configuration folding
		 */
		if (langCfg != null && langCfg.getFolding() != null)
			return langCfg.getFolding();

		/*
		 * 2) fallback to TextMate grammar folding
		 * A grammar-only file choice can provide folding markers even without an associated content type.
		 */
		final IGrammar grammar = grammarProvider.get();
		if (grammar == null)
			return null;
		try {
			final var start = grammar.getFoldingStartMarker();
			if (start == null || start.isBlank())
				return null;
			final var end = grammar.getFoldingEndMarker();
			if (end == null || end.isBlank())
				return null;
			return new FoldingRules(false, RegExPattern.of(start),
					RegExPattern.of(end));
		} catch (final TMException ex) {
			LanguageConfigurationPlugin.logError(ex);
		}
		return null;
	}

	private FoldingSupport() {
	}
}
