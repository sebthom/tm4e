/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.ui.internal.menus;

import java.util.Comparator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.tm4e.core.TMException;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.TMUIMessages;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection.Language;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.ui.ISources;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Shows the current workspace file's saved language and offers to change or reset it in all open editors.
 */
public final class LanguageContribution extends CompoundContributionItem implements IWorkbenchContribution {

	private @Nullable IHandlerService handlerService;

	@Override
	public void initialize(final IServiceLocator serviceLocator) {
		handlerService = serviceLocator.getService(IHandlerService.class);
	}

	@Override
	protected IContributionItem[] getContributionItems() {
		final var service = handlerService;
		if (service == null || !(service.getCurrentState().getVariable(ISources.ACTIVE_PART_NAME) instanceof final ITextEditor editor)
				|| TMPresentationReconciler.getTMPresentationReconciler(editor) == null)
			return new IContributionItem[0];
		final var input = editor.getEditorInput();
		final var file = input.getAdapter(IFile.class);
		final var provider = editor.getDocumentProvider();
		final var document = provider == null ? null : provider.getDocument(input);
		if (file == null || !file.isAccessible() || document == null)
			return new IContributionItem[0];
		final var chooseLanguage = new Action(TMUIMessages.LanguageSelection_action) {
			@Override
			public void run() {
				chooseLanguage(editor, file);
			}
		};
		final var chooseLanguageItem = new ActionContributionItem(chooseLanguage);
		try {
			// Removing a grammar leaves the file's saved choice in place. Keep reset available even if the grammar is gone.
			if (FileLanguageSelection.hasSavedLanguage(file)) {
				final var saved = FileLanguageSelection.getSavedLanguage(file);
				final var name = saved == null ? TMUIMessages.LanguageSelection_unavailable : getLanguageName(saved);
				// Grammar names are text, not menu mnemonics.
				chooseLanguage.setText(NLS.bind(TMUIMessages.LanguageSelection_actionWithSelection, name.replace("&", "&&")));
				// Keep reset outside the searchable list so filtering cannot hide it.
				final var reset = new Action(TMUIMessages.LanguageSelection_reset) {
					@Override
					public void run() {
						saveLanguage(editor, file, null);
					}
				};
				return new IContributionItem[] { chooseLanguageItem, new ActionContributionItem(reset) };
			}
		} catch (final CoreException ex) {
			TMUIPlugin.logError(ex);
		}
		return new IContributionItem[] { chooseLanguageItem };
	}

	private static void chooseLanguage(final ITextEditor editor, final IFile file) {
		final var shell = editor.getSite().getShell();
		try {
			final var saved = FileLanguageSelection.getSavedLanguage(file);
			final var languages = FileLanguageSelection.getAvailableLanguages();
			// A file's saved grammar/type pair remains valid after its workspace binding changes.
			if (saved != null && !languages.contains(saved))
				languages.add(saved);
			languages.sort(Comparator.comparing(LanguageContribution::getLabel, String.CASE_INSENSITIVE_ORDER));
			final var dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
				@Override
				public String getText(final Object element) {
					return getLabel((Language) element);
				}
			});
			dialog.setTitle(TMUIMessages.LanguageSelection_title);
			dialog.setMessage(TMUIMessages.LanguageSelection_message);
			dialog.setMultipleSelection(false);
			dialog.setElements(languages.toArray());
			if (saved != null)
				dialog.setInitialSelections(saved);
			if (dialog.open() != Window.OK)
				return;
			if (dialog.getFirstResult() instanceof final Language selected)
				saveLanguage(editor, file, selected);
		} catch (final CoreException ex) {
			showError(editor, ex);
		}
	}

	private static void saveLanguage(final ITextEditor editor, final IFile file, final @Nullable Language language) {
		try {
			FileLanguageSelection.setLanguage(file, language);
		} catch (final CoreException | IllegalArgumentException ex) {
			showError(editor, ex);
		}
	}

	private static void showError(final ITextEditor editor, final Exception ex) {
		TMUIPlugin.logError(ex);
		MessageDialog.openError(editor.getSite().getShell(), TMUIMessages.LanguageSelection_title,
				TMUIMessages.LanguageSelection_error);
	}

	private static String getLanguageName(final Language language) {
		try {
			// The grammar supplies a readable name even for choices with no content type or editing rules.
			final var grammar = TMEclipseRegistryPlugin.getGrammarRegistryManager()
					.getGrammarForScope(ITMScope.parse(language.scopeName()));
			final var name = grammar == null ? null : grammar.getName();
			if (name != null && !name.isBlank())
				return name;
		} catch (final TMException ex) {
			TMUIPlugin.logError(ex);
		}
		// An unnamed or unreadable grammar can still be identified and reset using its saved type and scope.
		return getLabel(language);
	}

	private static String getLabel(final Language language) {
		final var typeId = language.contentTypeId();
		final var type = typeId == null ? null : Platform.getContentTypeManager().getContentType(typeId);
		// Show the scope name, including any plugin ID, so users can distinguish providers of the same language.
		return type == null ? NLS.bind(TMUIMessages.LanguageSelection_syntaxOnly, language.scopeName())
				: type.getName() + " (" + language.scopeName() + ")";
	}
}
