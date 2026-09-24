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

import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.core.grammar.IGrammar;

/**
 * Holds a document's content types and, when the file has a saved language choice, its selected grammar.
 */
public final class ContentTypeInfo {

	private final String fileName;
	private final IContentType[] contentTypes;
	private final @Nullable IGrammar explicitGrammar;

	public ContentTypeInfo(final String fileName, final IContentType[] contentTypes) {
		this(fileName, contentTypes, null);
	}

	public ContentTypeInfo(final String fileName, final IContentType[] contentTypes, final @Nullable IGrammar explicitGrammar) {
		this.fileName = fileName;
		this.contentTypes = contentTypes;
		this.explicitGrammar = explicitGrammar;
	}

	public String getFileName() {
		return fileName;
	}

	public IContentType[] getContentTypes() {
		return contentTypes;
	}

	/**
	 * A file choice bypasses workspace grammar selection; null leaves selection to the registry.
	 */
	public @Nullable IGrammar getExplicitGrammar() {
		return explicitGrammar;
	}
}
