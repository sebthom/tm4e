/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.languageconfiguration.tests;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.content.IContentDescriber;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Counts detection calls for a dedicated test content type without relying on timing or document size.
 */
public final class CountingContentDescriber implements IContentDescriber {

	static final AtomicInteger CALLS = new AtomicInteger();

	@Override
	public int describe(final InputStream contents, final @Nullable IContentDescription description) {
		CALLS.incrementAndGet();
		// Keep the filename match eligible without claiming to recognize JavaScript content.
		return INDETERMINATE;
	}

	@Override
	public QualifiedName[] getSupportedOptions() {
		return new QualifiedName[0];
	}
}
