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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.tm4e.core.TMException;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.TMUIMessages;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection.Language;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.FilteredList;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.menus.CommandContributionItem;
import org.eclipse.ui.menus.CommandContributionItemParameter;
import org.eclipse.ui.menus.IWorkbenchContribution;
import org.eclipse.ui.services.IServiceLocator;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Shows the active editor's effective language and offers workspace files a language choice or reset for all open editors.
 */
public final class LanguageContribution extends CompoundContributionItem implements IWorkbenchContribution {

	private @Nullable IServiceLocator serviceLocator;

	@Override
	public void initialize(final IServiceLocator serviceLocator) {
		this.serviceLocator = serviceLocator;
	}

	@Override
	protected IContributionItem[] getContributionItems() {
		final IServiceLocator locator = serviceLocator;
		if (locator == null)
			return new IContributionItem[0];
		final IHandlerService service = locator.getService(IHandlerService.class);
		if (service == null || !(service.getCurrentState().getVariable(ISources.ACTIVE_PART_NAME) instanceof final IEditorPart editor))
			return new IContributionItem[0];

		final var reconciler = TMPresentationReconciler.getTMPresentationReconciler(editor);
		if (reconciler == null)
			return new IContributionItem[0];

		final IContributionItem currentLangItem = createCurrentLanguageItem(reconciler);
		// The effective language is useful for external files too; only workspace files can save a language choice.
		if (!(editor instanceof final ITextEditor textEditor))
			return new IContributionItem[] { currentLangItem };

		final var file = ChooseLanguageHandler.getSelectableFile(textEditor);
		if (file == null)
			return new IContributionItem[] { currentLangItem };

		final var chooseLang = new CommandContributionItemParameter(locator, null, ChooseLanguageHandler.COMMAND_ID,
				CommandContributionItem.STYLE_PUSH);
		// Keep the saved-language label local to this menu; Quick Access and Keys use the stable command name.
		chooseLang.label = TMUIMessages.LanguageSelection_action;
		try {
			// Removing a grammar leaves the file's saved choice in place. Keep reset available even if the grammar is gone.
			if (FileLanguageSelection.hasSavedLanguage(file)) {
				final Language saved = FileLanguageSelection.getSavedLanguage(file);
				final String name = saved == null ? TMUIMessages.LanguageSelection_unavailable : getLanguageName(saved);
				// Grammar names are text, not menu mnemonics.
				chooseLang.label = NLS.bind(TMUIMessages.LanguageSelection_actionWithSelection, name.replace("&", "&&"));
				// Keep reset outside the searchable list so filtering cannot hide it.
				final var reset = new Action(TMUIMessages.LanguageSelection_reset) {
					@Override
					public void run() {
						saveLanguage(textEditor, file, null);
					}
				};
				return new IContributionItem[] { currentLangItem, new CommandContributionItem(chooseLang), new ActionContributionItem(reset) };
			}
		} catch (final CoreException ex) {
			TMUIPlugin.logError(ex);
		}
		return new IContributionItem[] { currentLangItem, new CommandContributionItem(chooseLang) };
	}

	private static IContributionItem createCurrentLanguageItem(final TMPresentationReconciler reconciler) {
		// Report what this editor uses now. Workspace changes may require reopening it, and unavailable file choices fall back.
		final IGrammar grammar = reconciler.getGrammar();
		String name = TMUIMessages.LanguageSelection_none;
		if (grammar != null) {
			final var grammarName = grammar.getName();
			name = grammarName == null || grammarName.isBlank() ? grammar.getScopeName() : grammarName;
		}
		// Escape menu mnemonics so names such as "A&B" remain literal text.
		final var currentLang = new Action(NLS.bind(TMUIMessages.LanguageSelection_current, name.replace("&", "&&"))) {
		};
		currentLang.setEnabled(false);
		return new ActionContributionItem(currentLang);
	}

	static void chooseLanguage(final ITextEditor editor, final IFile file) {
		final var shell = editor.getSite().getShell();
		try {
			final Language saved = FileLanguageSelection.getSavedLanguage(file);
			final List<Language> langs = FileLanguageSelection.getAvailableLanguages();
			// A file's saved grammar/type pair remains valid after its workspace binding changes.
			if (saved != null && !langs.contains(saved))
				langs.add(saved);
			final Set<Language> matching = FileLanguageSelection.getMatchingLanguages(file.getName(), langs);
			final var labelProvider = new LabelProvider() {
				// Resolving a name can load a grammar. Reuse each label while sorting and filtering this dialog.
				private final HashMap<Language, String> labels = new HashMap<>();

				@Override
				public String getText(final Object element) {
					return labels.computeIfAbsent((Language) element, LanguageContribution::getLabel);
				}
			};
			final var matchingLabels = new HashSet<String>();
			matching.forEach(language -> matchingLabels.add(labelProvider.getText(language)));
			final var dialog = new ElementListSelectionDialog(shell, labelProvider) {
				@Override
				protected FilteredList createFilteredList(final Composite parent) {
					final var list = super.createFilteredList(parent);
					// FilteredList sorts rendered labels after setElements, so ranking must be installed on the list itself.
					list.setComparator((left, right) -> compareLabels((String) left, (String) right, matchingLabels));
					return list;
				}
			};
			dialog.setTitle(TMUIMessages.LanguageSelection_title);
			dialog.setMessage(TMUIMessages.LanguageSelection_message);
			dialog.setMultipleSelection(false);
			dialog.setElements(langs.toArray());
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

	private static int compareLabels(final String left, final String right, final Set<String> matchingLabels) {
		final int rank = Boolean.compare(!matchingLabels.contains(left), !matchingLabels.contains(right));
		if (rank != 0)
			return rank;
		final int ignoreCase = String.CASE_INSENSITIVE_ORDER.compare(left, right);
		return ignoreCase != 0 ? ignoreCase : left.compareTo(right);
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

	private static String getLanguageName(final Language lang) {
		try {
			// The grammar supplies a readable name even for choices with no content type or editing rules.
			final IGrammar grammar = TMEclipseRegistryPlugin.getGrammarRegistryManager()
					.getGrammarForScope(ITMScope.parse(lang.scopeName()));
			final String name = grammar == null ? null : grammar.getName();
			if (name != null && !name.isBlank())
				return name;
		} catch (final TMException ex) {
			TMUIPlugin.logError(ex);
		}
		// An unnamed or unreadable grammar remains identifiable by its content type or scope.
		final String typeId = lang.contentTypeId();
		final IContentType type = typeId == null ? null : Platform.getContentTypeManager().getContentType(typeId);
		return type == null ? lang.scopeName() : type.getName();
	}

	private static String getLabel(final Language lang) {
		final String typeId = lang.contentTypeId();
		final IContentType type = typeId == null ? null : Platform.getContentTypeManager().getContentType(typeId);
		// The dialog filters these labels, so include the readable grammar name even without a content-type binding.
		final String name = getLanguageName(lang);
		// Keep the qualified scope and any distinct type name to distinguish providers and editing-rule choices.
		final String details = type == null || type.getName().equals(name) ? lang.scopeName()
				: type.getName() + ", " + lang.scopeName();
		final String label = name.equals(details) ? name : name + " (" + details + ")";
		return type == null ? NLS.bind(TMUIMessages.LanguageSelection_syntaxOnly, label) : label;
	}
}
