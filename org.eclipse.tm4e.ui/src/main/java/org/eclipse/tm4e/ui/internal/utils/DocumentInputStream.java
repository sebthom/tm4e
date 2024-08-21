/*******************************************************************************
 * Copyright (c) 2024 Sebastian Thomschke and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Sebastian Thomschke - initial implementation
 *******************************************************************************/
package org.eclipse.tm4e.ui.internal.utils;

import java.nio.charset.Charset;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.ui.TMUIPlugin;

public final class DocumentInputStream extends CharsInputStream {

	private static Charset getCharset(final IDocument document) {
		final ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		if (bufferManager == null)
			return Charset.defaultCharset();
		final ITextFileBuffer buffer = bufferManager.getTextFileBuffer(document);
		if (buffer == null)
			return Charset.defaultCharset();
		try {
			final String charsetName = buffer.getEncoding();
			if (charsetName != null)
				return Charset.forName(charsetName);
		} catch (final Exception ex) {
			TMUIPlugin.logError(ex);
		}
		return Charset.defaultCharset();
	}

	public DocumentInputStream(final IDocument doc) {
		super(doc::getChar, doc::getLength, getCharset(doc));
	}
}
