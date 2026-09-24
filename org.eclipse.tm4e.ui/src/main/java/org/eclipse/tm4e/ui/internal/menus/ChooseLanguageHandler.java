/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
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
package org.eclipse.tm4e.ui.internal.menus;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.ui.ISources;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Opens the shared language chooser for a workspace file from menus, Quick Access, or user key bindings.
 */
public final class ChooseLanguageHandler extends AbstractHandler {

	static final String COMMAND_ID = "org.eclipse.tm4e.ui.chooseLanguageCommand";

	@Override
	public void setEnabled(final @Nullable Object evaluationContext) {
		// The active editor can remain set while a view owns focus. Follow the originating part, as the menu does.
		setBaseEnabled(evaluationContext instanceof final IEvaluationContext context
				&& context.getVariable(ISources.ACTIVE_PART_NAME) instanceof final ITextEditor editor
				&& getSelectableFile(editor) != null);
	}

	@Override
	public @Nullable Object execute(final ExecutionEvent event) {
		// Quick Access supplies the editor context it captured before opening; querying current focus would lose it.
		if (HandlerUtil.getActivePart(event) instanceof final ITextEditor editor) {
			// Recheck the file and document because they may have changed since command enablement was evaluated.
			final var file = getSelectableFile(editor);
			if (file != null)
				LanguageContribution.chooseLanguage(editor, file);
		}
		return null;
	}

	static @Nullable IFile getSelectableFile(final ITextEditor editor) {
		// An installed reconciler need not have a grammar yet: users must be able to choose their first language.
		if (TMPresentationReconciler.getTMPresentationReconciler(editor) == null)
			return null;
		final var input = editor.getEditorInput();
		final var file = input.getAdapter(IFile.class);
		final var provider = editor.getDocumentProvider();
		// Language choices live in workspace resource properties, so external files cannot save them.
		return file != null && file.isAccessible() && provider != null && provider.getDocument(input) != null ? file : null;
	}
}
