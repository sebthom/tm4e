/**
 * Copyright (c) 2023 Vegard IT GmbH and others.
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

import java.util.concurrent.PriorityBlockingQueue;

/**
 * PriorityBlockingQueue that prevents duplicates
 */
public class UniquePriorityBlockingQueue<E> extends PriorityBlockingQueue<E> {

	private static final long serialVersionUID = 1L;

	@Override
	public boolean offer(final E e) {
		if (contains(e))
			return false;
		return super.offer(e);
	}
}
