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
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.ui.internal.preferences.TextMatePreferencePage;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Opens the TM4E preferences page from its contextual menu command.
 */
public final class TextMatePreferencesContribution extends AbstractHandler {

	@Override
	public @Nullable Object execute(final ExecutionEvent event) {
		PreferencesUtil.createPreferenceDialogOn(HandlerUtil.getActiveShell(event), TextMatePreferencePage.PAGE_ID, null, null)
				.open();
		return null;
	}
}
