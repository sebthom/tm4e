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
package org.eclipse.tm4e.ui.internal.utils;

import static org.eclipse.core.runtime.Platform.getContentTypeManager;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IStorage;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jdt.annotation.Owning;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IStorageEditorInput;

/**
 * Resolves document content types and applies explicit grammar choices consistently across editor features.
 */
public final class ContentTypeHelper {

	/**
	 * Returns all content types that are associated to the given file name
	 */
	public static IContentType[] findContentTypesByFileName(final String fileName) {
		return getContentTypeManager().findContentTypesFor(fileName);
	}

	/**
	 * Find the content types from the given {@link IDocument} and null otherwise.
	 *
	 * @param document
	 *
	 * @return the content types from the given {@link IDocument} and null otherwise.
	 */
	public static @Nullable ContentTypeInfo findContentTypes(final IDocument document) {
		// A file choice wins even when its name or contents match a different language.
		final var fileSelection = FileLanguageSelection.getForDocument(document);
		if (fileSelection != null)
			return fileSelection;

		// Find content types from FileBuffers
		ContentTypeInfo info = findContentTypesFromFileBuffers(document);
		if (info == null) {
			info = findContentTypesFromEditorInput(document);
		}
		if (info == null)
			return null;
		// Apply explicit language choices before any feature caches document types. Reordering alone would still
		// let bracket matching and auto-edit merge rules from other languages sharing the file extension.
		return new ContentTypeInfo(info.getFileName(), TMEclipseRegistryPlugin.getGrammarRegistryManager()
				.getEffectiveContentTypes(info.getContentTypes()));
	}

	/**
	 * Finds a workspace file's language so Eclipse can decide which features to enable, even before the editor exists.
	 * Uses the open document's selection when available; otherwise checks the saved choice before filename-based defaults.
	 */
	public static ContentTypeInfo findContentTypes(final IFile file) {
		final var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
		if (buffer != null) {
			// Use the document's selection so available features and editing rules follow the same language.
			final var info = findContentTypes(buffer.getDocument());
			if (info != null)
				return info;
		}
		final var selection = FileLanguageSelection.resolve(file);
		if (selection != null)
			return selection;
		return new ContentTypeInfo(file.getName(), TMEclipseRegistryPlugin.getGrammarRegistryManager()
				.getEffectiveContentTypes(findContentTypesByFileName(file.getName())));
	}

	static @Nullable IFile getWorkspaceFile(final IDocument document) {
		final var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(document);
		if (buffer != null) {
			final var location = buffer.getLocation();
			if (location != null) {
				final var file = FileBuffers.getWorkspaceFileAtLocation(location, true);
				if (file != null)
					return file;
			}
		}
		final var input = getEditorInput(document);
		return input == null ? null : input.getAdapter(IFile.class);
	}

	/**
	 * Find the content type with the given contentTypeId
	 *
	 * @param contentTypeId
	 *
	 * @return matching content type or null
	 */
	public static @Nullable IContentType getContentTypeById(final String contentTypeId) {
		return getContentTypeManager().getContentType(contentTypeId);
	}

	// ------------------------- Find content types from FileBuffers

	/**
	 * Find the content types from the given {@link IDocument} by using
	 * {@link ITextFileBufferManager} and null otherwise.
	 *
	 * @param document
	 *
	 * @return the content types from the given {@link IDocument} by using
	 *         {@link ITextFileBufferManager} and null otherwise.
	 */
	private static @Nullable ContentTypeInfo findContentTypesFromFileBuffers(final IDocument document) {
		final ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		final ITextFileBuffer buffer = bufferManager.getTextFileBuffer(document);
		if (buffer != null) {
			return getContentTypes(buffer);
		}
		return null;
	}

	/**
	 * Determines the content types associated with the given {@link ITextFileBuffer}.
	 *
	 * @return a {@link ContentTypeInfo} object containing the file name and associated content types,
	 *         or {@code null} if content types could not be determined
	 */
	private static @Nullable ContentTypeInfo getContentTypes(final ITextFileBuffer buffer) {
		try {
			final String fileName = buffer.getFileStore().getName();
			final var contentTypes = new LinkedHashSet<IContentType>();
			if (buffer.isDirty() && buffer.getDocument() != null) {
				// Buffer is dirty, i.e. content of the filesystem is not in sync with the editor content -> use IDocument content
				try (var contents = new DocumentInputStream(buffer.getDocument())) {
					contentTypes.addAll(List.of(getContentTypeManager().findContentTypesFor(contents, fileName)));
				}
			} else {
				// Buffer is synchronized with filesystem content
				try (InputStream contents = getContents(buffer)) {
					contentTypes.addAll(List.of(getContentTypeManager().findContentTypesFor(contents, fileName)));
				}
			}

			// Append the buffer's content type last to preserve the priority order of content types resolved by getContentTypeManager,
			// since buffer.getContentType() may return a more generic, less specific type.
			final IContentType bufferContentType = buffer.getContentType();
			if (bufferContentType != null) {
				contentTypes.add(bufferContentType);
			}
			return new ContentTypeInfo(fileName, contentTypes.toArray(IContentType[]::new));
		} catch (final Exception ex) {
			TMUIPlugin.logTrace(ex);
		}
		return null;
	}

	/**
	 * Returns the content of the given buffer.
	 *
	 * @param buffer
	 *
	 * @return the content of the given buffer.
	 *
	 * @throws CoreException
	 */
	private static @Owning InputStream getContents(final ITextFileBuffer buffer) throws CoreException {
		final IPath path = buffer.getLocation();
		if (path != null) {
			if (path.isAbsolute()) {
				final var file = path.toFile();
				if (file.exists()) {
					try {
						return new BufferedInputStream(new FileInputStream(file));
					} catch (final FileNotFoundException ex) {
						TMUIPlugin.logError(ex);
					}
				}
			} else {
				final IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
				final IFile file = workspaceRoot.getFile(path);
				if (file.exists() && buffer.isSynchronized()) {
					return file.getContents();
				}
			}
		}
		return buffer.getFileStore().openInputStream(EFS.NONE, null);
	}

	// ------------------------- Find content types from FileBuffers

	/**
	 * Find the content types from the given {@link IDocument} by using
	 * {@link IEditorInput} and null otherwise.
	 *
	 * @param document
	 *
	 * @return the content types from the given {@link IDocument} by using
	 *         {@link IEditorInput} and null otherwise.
	 */
	private static @Nullable ContentTypeInfo findContentTypesFromEditorInput(final IDocument document) {
		final IEditorInput editorInput = getEditorInput(document);
		if (editorInput != null) {
			if (editorInput instanceof final IStorageEditorInput storageInput) {
				try {
					final IStorage storage = storageInput.getStorage();
					final String fileName = storage.getName();
					try (InputStream input = storage.getContents()) {
						return new ContentTypeInfo(fileName, getContentTypeManager().findContentTypesFor(input, fileName));
					}
				} catch (final Exception ex) {
					TMUIPlugin.logTrace(ex);
				}
			} /*else {
				// TODO: manage other type of IEditorInput
				}*/
		}
		return null;
	}

	/**
	 * Returns the {@link IEditorInput} from the given document and null otherwise.
	 *
	 * @param document
	 *
	 * @return the {@link IEditorInput} from the given document and null otherwise.
	 */
	private static @Nullable IEditorInput getEditorInput(final IDocument document) {
		try {
			// This utilities class is very ugly, I have not found a clean mean
			// to retrieve the IEditorInput linked to a document.
			// Here the strategy to retrieve the IEditorInput:

			// AbstractDocumentProvider#ElementInfo add a IDocumentListener to
			// the document.
			// ElementInfo contains a fElement which is the IEditorInput (like
			// ISorageEditorInput, see StorageDocumentProvider)

			// get list of IDocumentListener
			final ListenerList<?> listeners = ClassHelper.getFieldValue(document, "fDocumentListeners");
			if (listeners != null) {
				// Get AbstractDocumentProvider#ElementInfo
				final Object[] l = listeners.getListeners();
				for (final Object /* AbstractDocumentProvider#ElementInfo */ info : l) {
					try {
						/* The element for which the info is stored */
						final Object input = ClassHelper.getFieldValue(info, "fElement");
						if (input instanceof final IEditorInput editorInput) {
							return editorInput;
						}
					} catch (final RuntimeException ex) {
						TMUIPlugin.logTrace(ex);
					}
				}
			}
		} catch (final RuntimeException ex) {
			TMUIPlugin.logTrace(ex);
		}
		return null;
	}

	private ContentTypeHelper() {
	}
}
