/**
 * Copyright (c) 2018 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Lucas Bullen (Red Hat Inc.) - initial API and implementation
 */
package org.eclipse.tm4e.languageconfiguration.internal;

import static org.eclipse.core.runtime.Platform.getContentTypeManager;
import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNullable;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeInfo;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

public final class HasLanguageConfigurationPropertyTester extends PropertyTester {

	private final LanguageConfigurationRegistryManager registry = LanguageConfigurationRegistryManager.getInstance();

	@Override
	public boolean test(
			final @Nullable Object receiver,
			final @Nullable String property,
			final Object @Nullable [] args,
			final @Nullable Object expectedValue) {

		if (receiver instanceof IFileEditorInput fileInput)
			return hasLanguageConfiguration(getContentTypeManager().findContentTypesFor(fileInput.getFile().getName()));

		if (receiver instanceof IFile file)
			return hasLanguageConfiguration(getContentTypeManager().findContentTypesFor(file.getName()));

		final ITextEditor editor = castNullable(Adapters.adapt(receiver, ITextEditor.class));
		if (editor == null)
			return false;

		final IEditorInput input = editor.getEditorInput();
		final IDocumentProvider docProvider = editor.getDocumentProvider();
		if (docProvider == null || input == null)
			return false;

		return hasLanguageConfiguration(docProvider.getDocument(input));
	}

	private boolean hasLanguageConfiguration(final @Nullable IDocument document) {
		if (document == null)
			return false;

		final ContentTypeInfo info = ContentTypeHelper.findContentTypes(document);
		if (info == null)
			return false;

		return hasLanguageConfiguration(info.getContentTypes());
	}

	private boolean hasLanguageConfiguration(final IContentType... contentTypes) {
		return registry.getLanguageConfigurationFor(contentTypes) != null;
	}
}
