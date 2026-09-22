/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.tm4e.registry.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.registry.GrammarContentType;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.osgi.service.prefs.BackingStoreException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.reflect.TypeToken;

/**
 * Loads and saves imported grammars, matching-file suggestions, and explicit content-type bindings as workspace JSON preferences.
 */
final class PreferenceHelper {

	private static final String GRAMMARS = "org.eclipse.tm4e.registry.grammars";
	private static final String USER_BINDINGS = "org.eclipse.tm4e.registry.grammarBindings";
	private static final String CONTENT_TYPES = "org.eclipse.tm4e.registry.grammarContentTypes";
	private static final String FILE_ASSOCIATIONS = "org.eclipse.tm4e.registry.grammarFileAssociations";

	private static final Gson DEFAULT_GSON = new GsonBuilder()
			.registerTypeAdapter(IGrammarDefinition.class,
					(InstanceCreator<GrammarDefinition>) type -> new GrammarDefinition())
			.create();

	static @Nullable List<IGrammarDefinition> loadGrammars() {
		final String json = TMEclipseRegistryPlugin.getPreference(GRAMMARS, null);
		if (json == null)
			return null;
		return DEFAULT_GSON.fromJson(json, new TypeToken<ArrayList<GrammarDefinition>>() {
		}.getType());
	}

	static Map<String, String> loadUserBindings() {
		final String json = TMEclipseRegistryPlugin.getPreference(USER_BINDINGS, null);
		if (json == null)
			return Map.of();
		final Map<String, String> bindings = DEFAULT_GSON.fromJson(json, new TypeToken<Map<String, String>>() {
		}.getType());
		return bindings == null ? Map.of() : bindings;
	}

	static Map<String, GrammarContentType> loadGrammarContentTypes() {
		// Missing metadata means unmanaged. Loading old imports must never create types or claim manual ones.
		final String json = TMEclipseRegistryPlugin.getPreference(CONTENT_TYPES, null);
		if (json == null)
			return Map.of();
		final Map<String, GrammarContentType> types = DEFAULT_GSON.fromJson(json, new TypeToken<Map<String, GrammarContentType>>() {
		}.getType());
		return types == null ? Map.of() : types;
	}

	static Map<String, List<String>> loadGrammarFileAssociations() {
		final String json = TMEclipseRegistryPlugin.getPreference(FILE_ASSOCIATIONS, null);
		if (json == null)
			return Map.of();
		final Map<String, List<String>> associations = DEFAULT_GSON.fromJson(json, new TypeToken<Map<String, List<String>>>() {
		}.getType());
		if (associations == null)
			return Map.of();
		final var result = new HashMap<String, List<String>>();
		associations.forEach((source, patterns) -> result.put(source, List.copyOf(patterns)));
		return result;
	}

	static void saveGrammars(final Collection<IGrammarDefinition> definitions, final Map<String, String> userBindings,
			final Map<String, GrammarContentType> contentTypes, final Map<String, List<String>> fileAssociations)
			throws BackingStoreException {
		// Save grammar definitions in the
		// "${workspace_loc}/.metadata/.plugins/org.eclipse.core.runtime/.settings/org.eclipse.tm4e.registry.prefs"
		final String json = DEFAULT_GSON.toJson(definitions);
		final IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(TMEclipseRegistryPlugin.PLUGIN_ID);
		final var previous = new HashMap<String, @Nullable String>();
		for (final String key : List.of(GRAMMARS, USER_BINDINGS, CONTENT_TYPES, FILE_ASSOCIATIONS)) {
			previous.put(key, prefs.get(key, null));
		}
		prefs.put(GRAMMARS, json);
		// Store IDs, not Eclipse content-type instances; user-created types are restored by the platform on restart.
		prefs.put(USER_BINDINGS, DEFAULT_GSON.toJson(userBindings));
		prefs.put(CONTENT_TYPES, DEFAULT_GSON.toJson(contentTypes));
		prefs.put(FILE_ASSOCIATIONS, DEFAULT_GSON.toJson(fileAssociations));
		try {
			prefs.flush();
		} catch (final BackingStoreException ex) {
			// A failed flush still changes the in-memory preference node. Restore it before rolling back platform setup.
			previous.forEach((key, value) -> {
				if (value == null)
					prefs.remove(key);
				else
					prefs.put(key, value);
			});
			try {
				prefs.flush();
			} catch (final BackingStoreException rollbackFailure) {
				ex.addSuppressed(rollbackFailure);
			}
			throw ex;
		}
	}

	private PreferenceHelper() {
	}
}
