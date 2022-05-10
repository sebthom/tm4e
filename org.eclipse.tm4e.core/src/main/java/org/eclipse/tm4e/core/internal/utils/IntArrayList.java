/**
 * Copyright (c) 2022 Sebastian Thomschke and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.core.internal.utils;

import java.util.Arrays;

public class IntArrayList {

	public static final IntArrayList EMPTY = new IntArrayList(0) {
		@Override
		public void add(final int value) {
			throw new UnsupportedOperationException();
		}
	};

	private int[] values;
	private int size;

	public IntArrayList() {
		this(8);
	}

	public IntArrayList(final int initialCapacity) {
		values = new int[initialCapacity];
	}

	public void add(final int value) {
		if (size == values.length) {
			values = size == 0
					? new int[1]
					: Arrays.copyOf(values, (int) (size * 1.6F));
		}
		values[size] = value;
		size++;
	}

	public int get(final int index) {
		return values[index];
	}

	public int getLast() {
		return values[size - 1];
	}

	public int getLast(final int index) {
		return values[size - index];
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public boolean isNotEmpty() {
		return size != 0;
	}

	public int remove(final int index) {
		final int old = values[index];
		final int newSize = size - 1;
		if (size > 0 && index < newSize) {
			System.arraycopy(
					values, index + 1,
					values, index,
					newSize - index);
		}
		size = newSize;
		return old;
	}

	public int removeLast() {
		final int old = values[size - 1];
		size--;
		return old;
	}

	public void set(final int index, final int value) {
		values[index] = value;
	}

	public int size() {
		return size;
	}

	public int[] toArray() {
		return Arrays.copyOf(values, size);
	}

	@Override
	public String toString() {
		return Arrays.toString(values);
	}
}
