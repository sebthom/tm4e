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
package org.eclipse.tm4e.ui.internal.wizards;

import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.lateNonNull;

import java.util.Objects;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Imports a TextMate grammar and optional workspace associations, either saving them directly or staging them in a caller's edit session.
 */
public final class TextMateGrammarImportWizard extends Wizard implements IImportWizard {

	// A supplied session belongs to the caller; null means the standalone wizard owns its sessions.
	private final IGrammarRegistryManager.@Nullable EditSession manager;
	private final boolean saveOnFinish;

	private SelectGrammarWizardPage mainPage = lateNonNull();
	private IGrammarDefinition createdDefinition = lateNonNull();

	/** Eclipse uses this constructor for File > Import, where Finish saves the selected grammar directly. */
	public TextMateGrammarImportWizard() {
		manager = null;
		saveOnFinish = true;
	}

	public TextMateGrammarImportWizard(final IGrammarRegistryManager.EditSession manager, final boolean saveOnFinish) {
		this.manager = manager;
		this.saveOnFinish = saveOnFinish;
	}

	@Override
	public void addPages() {
		// Standalone previews use saved imports; each Finish still owns a fresh edit session for safe retries.
		mainPage = new SelectGrammarWizardPage(manager == null ? TMEclipseRegistryPlugin.getGrammarRegistryManager() : manager);
		addPage(mainPage);
	}

	@Override
	public boolean performFinish() {
		final IGrammarDefinition selectedDef = mainPage.getGrammarDefinition();
		// A failed standalone attempt must not retain an earlier selection when Finish is retried.
		// Keep caller-owned sessions intact because they may also contain other preference-page edits.
		final IGrammarRegistryManager.EditSession editSession;
		if (manager == null) {
			final var liveManager = TMEclipseRegistryPlugin.getGrammarRegistryManager();
			// The page was filled before this fresh session exists, so compare its explicit baseline first.
			if (!Objects.equals(mainPage.getLoadedFileAssociations(),
					liveManager.getSavedGrammarFileAssociations(selectedDef))) {
				mainPage.setErrorMessage(
						"The matching files for this grammar changed in another dialog. Reopen the import to edit them again.");
				return false;
			}
			editSession = liveManager.newEditSession();
		} else {
			editSession = manager;
		}
		final IGrammarDefinition createdDefinition;
		try {
			createdDefinition = editSession.importGrammar(selectedDef, mainPage.getWorkspaceLanguageName(),
					mainPage.getFileAssociations());
			if (saveOnFinish) {
				editSession.save();
			}
		} catch (final IllegalArgumentException ex) {
			// Page validation checks the file, but only the registry can detect a conflicting earlier import.
			mainPage.setErrorMessage(ex.getMessage());
			return false;
		} catch (final BackingStoreException ex) {
			TMUIPlugin.logError(ex);
			// Returning false keeps the dialog open; JFace does not display save errors for us.
			mainPage.setErrorMessage(ex.getMessage());
			return false;
		}
		// A retry may succeed without revalidating the unchanged input.
		mainPage.setErrorMessage(null);
		this.createdDefinition = createdDefinition;
		return true;
	}

	@Override
	public void init(final IWorkbench workbench, final IStructuredSelection selection) {
	}

	public IGrammarDefinition getCreatedDefinition() {
		return createdDefinition;
	}
}
