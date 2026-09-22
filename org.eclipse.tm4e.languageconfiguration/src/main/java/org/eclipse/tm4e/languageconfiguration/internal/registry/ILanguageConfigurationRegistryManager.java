/**
 * Copyright (c) 2018 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * - Lucas Bullen (Red Hat Inc.) - initial API and implementation
 * - Sebastian Thomschke (Vegard IT) - add concept of EditSession
 */
package org.eclipse.tm4e.languageconfiguration.internal.registry;

import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Looks up language configurations and provides edit sessions for workspace configuration changes.
 */
public interface ILanguageConfigurationRegistryManager {

	interface EditSession extends ILanguageConfigurationRegistryManager {
		/**
		 * resets this session to the current state of the singleton language configuration manager
		 */
		void reset();

		/**
		 * Add language configuration definition to the registry.
		 *
		 * NOTE: you must call save() method if you wish to save in the preferences.
		 */
		void registerLanguageConfigurationDefinition(ILanguageConfigurationDefinition definition);

		/**
		 * Remove language configuration definition from the registry.
		 *
		 * NOTE: you must call save() method if you wish to save in the preferences.
		 */
		void unregisterLanguageConfigurationDefinition(ILanguageConfigurationDefinition definition);

		/**
		 * Applies changes to the singleton language configuration manager and persists them to disk
		 */
		void save() throws BackingStoreException;

		/**
		 * @throws UnsupportedOperationException
		 */
		@Override
		default ILanguageConfigurationRegistryManager.EditSession newEditSession() {
			throw new UnsupportedOperationException();
		}
	}

	ILanguageConfigurationRegistryManager.EditSession newEditSession();

	/**
	 * Returns the list of registered language configuration definitions.
	 *
	 * @return the list of registered language configuration definitions.
	 */
	ILanguageConfigurationDefinition[] getDefinitions();

	/**
	 * Looks up editing rules after using workspace grammar bindings to select a language from the given content types.
	 * A matching binding selects its content type instead of the other matching types. This can be a parent of a supplied type.
	 * The selected types are passed to {@link #getLanguageConfigurationForResolvedTypes(IContentType...)}.
	 * <p>
	 * Use this method when workspace bindings should determine the language.
	 * Use {@code getLanguageConfigurationForResolvedTypes} when the caller has already selected the language,
	 * for example through a file-specific choice that must take priority over workspace bindings.
	 *
	 * @param contentTypes ordered content type candidates, before applying workspace bindings
	 *
	 * @return the configuration for the first matching selected type, or {@code null} if none is available
	 */
	@Nullable
	LanguageConfiguration getLanguageConfigurationFor(IContentType... contentTypes);

	/**
	 * Looks up editing rules for the given content types without applying workspace grammar bindings.
	 * Types are checked in the supplied order. For each exact type, a user configuration takes priority over
	 * a plugin configuration. This lookup does not search parent types.
	 * <p>
	 * Use this method when the caller has already selected the language, for example through a file-specific choice.
	 * Skipping workspace bindings preserves that choice even if another matching type or a parent type has a binding.
	 * Use {@link #getLanguageConfigurationFor(IContentType...)} when workspace bindings should still be applied.
	 *
	 * @param contentTypes ordered content types that already represent the selected language
	 *
	 * @return the configuration for the first matching supplied type, or {@code null} if none is available
	 */
	@Nullable
	LanguageConfiguration getLanguageConfigurationForResolvedTypes(IContentType... contentTypes);
}
