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
package org.eclipse.tm4e.ui.internal;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.ui.text.TMPresentationReconciler;
import org.eclipse.ui.IEditorPart;

/**
 * Tests whether an editor has a {@link TMPresentationReconciler} installed or has active TextMate highlighting.
 */
public final class TMPropertyTester extends PropertyTester {

	private static final String CAN_SUPPORT_TEXT_MATE = "canSupportTextMate";
	private static final String HAS_TEXT_MATE_RECONCILER = "hasTextMateReconciler";

	@Override
	public boolean test(final @Nullable Object receiver, final String property, final Object[] args, final @Nullable Object expectedValue) {
		if (receiver instanceof final IEditorPart editorPart) {
			final var reconciler = TMPresentationReconciler.getTMPresentationReconciler(editorPart);
			// Language selection must stay available before a grammar enables highlighting.
			if (HAS_TEXT_MATE_RECONCILER.equals(property))
				return reconciler != null;
			if (CAN_SUPPORT_TEXT_MATE.equals(property))
				return reconciler != null && reconciler.isEnabled();
		}
		return false;
	}
}
