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
package org.eclipse.tm4e.core.internal.utils;

import java.util.List;

import org.eclipse.jdt.annotation.Nullable;

public final class MoreCollections {

	@Nullable
	public static <T> T findLastElement(@Nullable final List<T> list) {
		if (list == null || list.isEmpty())
			return null;
		return getLastElement(list);
	}

	/**
	 * @param list a non-empty list with non-nullable elements
	 */
	public static <T> T getLastElement(final List<T> list) {
		return list.get(list.size() - 1);
	}

   private MoreCollections() {
   }
}
