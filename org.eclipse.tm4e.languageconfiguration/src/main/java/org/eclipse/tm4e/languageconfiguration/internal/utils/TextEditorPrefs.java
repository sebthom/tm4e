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
 * - Sebastian Thomschke - initial implementation
 */
package org.eclipse.tm4e.languageconfiguration.internal.utils;

import static org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants.*;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.ITextEditor;

public final class TextEditorPrefs {

	public static final class TabPrefs {

		/** Specifies if the text editor uses spaces for tabs. */
		public final boolean useSpacesForTabs;

		/** The number of spaces used per tab in the text editor. */
		public final int tabWidth;

		public TabPrefs(boolean useSpacesForTabs, int tabWidth) {
			this.useSpacesForTabs = useSpacesForTabs;
			this.tabWidth = tabWidth;
		}
	}

	public static TabPrefs getTabPrefs(final @Nullable ITextEditor editor) {
		final var editorPrefStore = editor == null ? null : editor.getAdapter(IPreferenceStore.class);
		final var useSpacesPrefStore = editorPrefStore != null && editorPrefStore.contains(EDITOR_SPACES_FOR_TABS)
				? editorPrefStore
				: EditorsUI.getPreferenceStore();
		final var tabWidthPrefStore = editorPrefStore != null && editorPrefStore.contains(EDITOR_TAB_WIDTH)
				? editorPrefStore
				: EditorsUI.getPreferenceStore();
		return new TabPrefs(useSpacesPrefStore.getBoolean(EDITOR_SPACES_FOR_TABS),
				tabWidthPrefStore.getInt(EDITOR_TAB_WIDTH));
	}

	private TextEditorPrefs() {
	}
}
