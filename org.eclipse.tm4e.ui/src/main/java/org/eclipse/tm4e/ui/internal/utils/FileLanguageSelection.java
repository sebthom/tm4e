/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.ui.internal.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.core.TMException;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.model.TMModelManager;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

/**
 * Saves language choices for workspace files and updates the editor features that use each open document.
 */
public final class FileLanguageSelection {

	/**
	 * Identifies the grammar separately from the content type that supplies editing rules.
	 * Qualified scopes include the plugin ID, so bundled grammars stay distinct from imports with the same scope.
	 * A null content-type ID means syntax highlighting only.
	 */
	public record Language(String scopeName, @Nullable String contentTypeId) {
	}

	private static final QualifiedName PROPERTY = new QualifiedName(TMUIPlugin.PLUGIN_ID, "fileLanguage");
	private static final Gson GSON = new Gson();
	// Cache the absence of a file choice too, so editing features can detect a language change
	// without reading resource metadata on every keystroke.
	// Values do not refer back to their documents, so the cache does not prevent garbage collection.
	private static final Map<IDocument, Optional<ContentTypeInfo>> DOCUMENT_SELECTIONS = new WeakHashMap<>();
	private static final ListenerList<Consumer<IDocument>> CHANGE_LISTENERS = new ListenerList<>();

	/**
	 * Registers a callback that runs on the UI thread when a file's language changes.
	 * Callers must remove it when they disconnect or uninstall.
	 * The document selection is updated before callbacks run; the shared token model changes after all callbacks finish.
	 */
	public static void addChangeListener(final Consumer<IDocument> listener) {
		CHANGE_LISTENERS.add(listener);
	}

	public static void removeChangeListener(final Consumer<IDocument> listener) {
		CHANGE_LISTENERS.remove(listener);
	}

	public static List<Language> getAvailableLanguages() {
		final var registry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		final var languages = new ArrayList<Language>();
		final var scopes = new HashSet<String>();
		for (final var definition : registry.getDefinitions()) {
			final var scope = definition.getScope();
			// The registry loads the first import for a scope. Duplicate imports are not independent language choices.
			if (!scopes.add(scope.getQualifiedName()))
				continue;
			final var types = registry.getContentTypesForScope(scope);
			if (types == null || types.isEmpty()) {
				languages.add(new Language(scope.getQualifiedName(), null));
			} else {
				for (final var type : types) {
					languages.add(new Language(scope.getQualifiedName(), type.getId()));
				}
			}
		}
		return languages;
	}

	/**
	 * Returns whether this file has a saved choice, including one whose grammar or content type is no longer installed.
	 * Unlike {@link #getSavedLanguage(IFile)}, this lets the UI offer to clear a choice that cannot currently be used.
	 */
	public static boolean hasSavedLanguage(final IFile file) throws CoreException {
		return file.getPersistentProperty(PROPERTY) != null;
	}

	/**
	 * Returns the saved choice, or null for automatic selection or when its grammar or content type is no longer installed.
	 */
	public static @Nullable Language getSavedLanguage(final IFile file) throws CoreException {
		final var json = file.getPersistentProperty(PROPERTY);
		if (json == null)
			return null;
		try {
			final var language = GSON.fromJson(json, Language.class);
			// Check that the grammar and content type still exist. Workspace binding changes must not invalidate a file's saved choice.
			return language != null && isAvailable(language) ? language : null;
		} catch (final JsonParseException ex) {
			TMUIPlugin.logError(ex);
			return null;
		}
	}

	/**
	 * Saves a choice in Eclipse resource metadata and applies it to open documents; null restores automatic selection.
	 * The document and its undo history are preserved. Editor notifications run on the UI thread before this method returns.
	 */
	public static void setLanguage(final IFile file, final @Nullable Language language) throws CoreException {
		if (language != null && !isAvailable(language))
			throw new IllegalArgumentException("The file language must refer to an installed grammar and content type.");
		file.setPersistentProperty(PROPERTY, language == null ? null : GSON.toJson(language));
		UI.runSync(() -> {
			final List<IDocument> documents;
			synchronized (DOCUMENT_SELECTIONS) {
				documents = new ArrayList<>(DOCUMENT_SELECTIONS.keySet());
			}
			// Include a buffer whose editor has not requested its selection yet.
			final var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
			if (buffer != null && !documents.contains(buffer.getDocument()))
				documents.add(buffer.getDocument());
			for (final var document : documents) {
				if (!file.equals(ContentTypeHelper.getWorkspaceFile(document)))
					continue;
				final var selection = Optional.ofNullable(resolve(file));
				synchronized (DOCUMENT_SELECTIONS) {
					DOCUMENT_SELECTIONS.put(document, selection);
				}
				for (final var listener : CHANGE_LISTENERS) {
					// Log listener failures and continue updating the other views.
					SafeRunner.run(() -> listener.accept(document));
				}
				// Notify all editor features before retokenizing. Keep the shared model because other views and partitioners
				// still use it. Replacing the editor would also discard its undo history.
				final var model = TMModelManager.INSTANCE.getConnectedModel(document);
				if (model != null)
					model.setGrammar(GrammarUtils.findGrammar(document));
			}
			return null;
		});
	}

	public static @Nullable ContentTypeInfo getForDocument(final IDocument document) {
		synchronized (DOCUMENT_SELECTIONS) {
			final var cached = DOCUMENT_SELECTIONS.get(document);
			if (cached != null)
				return cached.orElse(null);
		}
		// Look up the file and grammar outside the cache lock: file buffers and the registry take their own locks.
		final var file = ContentTypeHelper.getWorkspaceFile(document);
		// A file buffer can request a selection before its document is registered. Do not cache that temporary absence.
		if (file == null || !file.isAccessible())
			return null;
		final var selection = Optional.ofNullable(resolve(file));
		synchronized (DOCUMENT_SELECTIONS) {
			// Keep the first cached selection so concurrent readers agree, even if the saved choice changes during this lookup.
			final var existing = DOCUMENT_SELECTIONS.putIfAbsent(document, selection);
			return (existing == null ? selection : existing).orElse(null);
		}
	}

	// Shared with file-based feature checks that can run before an editor document exists.
	static @Nullable ContentTypeInfo resolve(final IFile file) {
		try {
			final var language = getSavedLanguage(file);
			if (language == null)
				return null;
			final var definition = findDefinition(language.scopeName());
			if (definition == null)
				return null;
			final var grammar = TMEclipseRegistryPlugin.getGrammarRegistryManager().getGrammarForScope(definition.getScope());
			if (grammar == null)
				return null;
			final var typeId = language.contentTypeId();
			final var type = typeId == null ? null : Platform.getContentTypeManager().getContentType(typeId);
			if (typeId != null && type == null)
				return null;
			return new ContentTypeInfo(file.getName(), type == null ? new IContentType[0] : new IContentType[] { type }, grammar);
		} catch (final CoreException | TMException ex) {
			// If the grammar cannot load, fall back for both highlighting and editing rules to avoid mixing languages.
			TMUIPlugin.logError(ex);
			return null;
		}
	}

	private static boolean isAvailable(final Language language) {
		final var typeId = language.contentTypeId();
		return findDefinition(language.scopeName()) != null
				&& (typeId == null || Platform.getContentTypeManager().getContentType(typeId) != null);
	}

	private static @Nullable IGrammarDefinition findDefinition(final String scopeName) {
		for (final var definition : TMEclipseRegistryPlugin.getGrammarRegistryManager().getDefinitions()) {
			// After an imported grammar is removed, a plugin grammar with the same scope must not take its place.
			if (definition.getScope().getQualifiedName().equals(scopeName))
				return definition;
		}
		return null;
	}

	private FileLanguageSelection() {
	}
}
