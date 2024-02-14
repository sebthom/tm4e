/**
 * Copyright (c) 2024 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Sebastian Thomschke (Vegard IT) - initial implementation
 */
package org.eclipse.tm4e.languageconfiguration.internal;

import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNonNull;

import java.util.ArrayList;

import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.tm4e.core.internal.utils.MoreCollections;
import org.eclipse.tm4e.languageconfiguration.LanguageConfigurationPlugin;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.ui.internal.model.DocumentHelper;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;

public class TMFoldingStrategy extends AbstractFoldingStrategy {

	private static final IContentType[] EMPTY_CONTENT_TYPES = new IContentType[0];
	private IContentType[] contentTypes = EMPTY_CONTENT_TYPES;

	public TMFoldingStrategy() {

		System.out.println("h");
	}

	@Override
	public void setDocument(final @Nullable IDocument document) {
		super.setDocument(document);
		if (document == null) {
			this.contentTypes = EMPTY_CONTENT_TYPES;
		} else {
			final var contentTypeInfo = ContentTypeHelper.findContentTypes(document);
			this.contentTypes = contentTypeInfo == null ? EMPTY_CONTENT_TYPES : contentTypeInfo.getContentTypes();
		}
	}

	@Override
	protected void doReconcile(final @Nullable IRegion subRegion) {
		if (contentTypes.length == 0)
			return;

		final var langcfg = LanguageConfigurationRegistryManager.getInstance().getLanguageConfigurationFor(contentTypes);
		if (langcfg == null)
			return;

		final var folding = langcfg.getFolding();
		if (folding == null)
			return;

		final var doc = castNonNull(document);
		try {
			final var startLineIndex = subRegion == null ? 0 : doc.getLineOfOffset(subRegion.getOffset());
			final var endLineIndex = subRegion == null ? doc.getNumberOfLines() - 1
					: doc.getLineOfOffset(subRegion.getOffset() + subRegion.getLength());

			final var foldingRanges = new ArrayList<FoldingRange>();
			final var foldingRangeStartLineIndexes = new ArrayList<Integer>();
			for (int i = startLineIndex; i < endLineIndex; i++) {
				final var lineText = DocumentHelper.getLineText(doc, i, false);
				if (folding.markers.start.matchesPartially(lineText)) {
					foldingRangeStartLineIndexes.add(i);
				} else if (folding.markers.end.matchesPartially(lineText)) {
					if (!foldingRangeStartLineIndexes.isEmpty()) {
						foldingRanges.add(new FoldingRange(MoreCollections.removeLastElement(foldingRangeStartLineIndexes), i, false));
					}
				}
			}

			if (!foldingRanges.isEmpty())
				applyFolding(foldingRanges);
		} catch (BadLocationException ex) {
			LanguageConfigurationPlugin.logError(ex);
		}
	}
}
