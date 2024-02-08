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
package org.eclipse.tm4e.core.internal.theme;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.core.theme.IStyle;
import org.eclipse.tm4e.core.theme.RGB;

public class Style implements IStyle {

	private @Nullable RGB color;
	private @Nullable RGB backgroundColor;

	private boolean bold;
	private boolean italic;
	private boolean underline;
	private boolean strikeThrough;

	public void setColor(final RGB color) {
		this.color = color;
	}

	@Nullable
	@Override
	public RGB getColor() {
		return color;
	}

	@Nullable
	@Override
	public RGB getBackgroundColor() {
		return backgroundColor;
	}

	public void setBackgroundColor(final RGB backgroundColor) {
		this.backgroundColor = backgroundColor;
	}

	public void setBold(final boolean bold) {
		this.bold = bold;
	}

	@Override
	public boolean isBold() {
		return bold;
	}

	public void setItalic(final boolean italic) {
		this.italic = italic;
	}

	@Override
	public boolean isItalic() {
		return italic;
	}

	@Override
	public boolean isUnderline() {
		return underline;
	}

	public void setUnderline(final boolean underline) {
		this.underline = underline;
	}

	@Override
	public boolean isStrikeThrough() {
		return strikeThrough;
	}

	public void setStrikeThrough(final boolean strikeThrough) {
		this.strikeThrough = strikeThrough;
	}
}
