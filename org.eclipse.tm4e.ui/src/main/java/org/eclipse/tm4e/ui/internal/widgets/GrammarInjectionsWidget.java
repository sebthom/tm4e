/**
 * Copyright (c) 2022 Sebastian Thomschke and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Sebastian Thomschke - initial implementation
 */
package org.eclipse.tm4e.ui.internal.widgets;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.tm4e.ui.internal.TMUIMessages;

/**
 * Widget which displays a grammar's injections.
 */
public final class GrammarInjectionsWidget extends TableAndButtonsWidget {

	public GrammarInjectionsWidget(final Composite parent, final int style) {
		super(parent, style, TMUIMessages.GrammarInjectionsWidget_description);
		super.setContentProvider(ArrayContentProvider.getInstance());
		super.setLabelProvider(new GrammarInjectionLabelProvider());
	}

	@Override
	protected void createButtons(final Composite parent) {
	}
}
