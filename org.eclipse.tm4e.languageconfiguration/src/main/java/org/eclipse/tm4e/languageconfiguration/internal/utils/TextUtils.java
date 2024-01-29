/**
 * Copyright (c) 2015-2022 Angelo ZERR and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package org.eclipse.tm4e.languageconfiguration.internal.utils;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextUtilities;
import org.eclipse.tm4e.languageconfiguration.internal.utils.TextEditorPrefs.TabPrefs;

public final class TextUtils {

	/**
	 * @return true if text of the command is an enter and false otherwise.
	 */
	public static boolean isEnter(final IDocument doc, final DocumentCommand cmd) {
		return cmd.length == 0 && cmd.text != null && TextUtilities.equals(doc.getLegalLineDelimiters(), cmd.text) != -1;
	}

	public static String normalizeIndentation(final String text, final TabPrefs tabPrefs) {
		int firstNonWhitespaceIndex = firstNonWhitespaceIndex(text);
		if (firstNonWhitespaceIndex == -1) {
			firstNonWhitespaceIndex = text.length();
		}
		return normalizeIndentationFromWhitespace(text.substring(0, firstNonWhitespaceIndex), tabPrefs)
				+ text.substring(firstNonWhitespaceIndex);
	}

	private static String normalizeIndentationFromWhitespace(final String text, final TabPrefs tabPrefs) {
		int spacesCnt = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\t') {
				spacesCnt += tabPrefs.tabWidth;
			} else {
				spacesCnt++;
			}
		}

		final var result = new StringBuilder();
		if (!tabPrefs.useSpacesForTabs) {
			final long tabsCnt = spacesCnt / tabPrefs.tabWidth;
			spacesCnt = spacesCnt % tabPrefs.tabWidth;
			for (int i = 0; i < tabsCnt; i++) {
				result.append('\t');
			}
		}

		for (int i = 0; i < spacesCnt; i++) {
			result.append(' ');
		}

		return result.toString();
	}

	/**
	 * Returns the start of searchFor at the offset in the searchIn.
	 * If the searchFor is not in the searchIn at the offset, returns -1.
	 * <p>
	 * Example:
	 *
	 * <pre>
	 * text="apple banana", offset=8, string="banana" -> returns 6
	 * </pre>
	 */
	public static int startIndexOfOffsetTouchingString(final String searchIn, final int offset, final String searchFor) {
		final int start = Math.max(0, offset - searchFor.length());
		int end = offset + searchFor.length();
		end = end >= searchIn.length() ? searchIn.length() : end;
		try {
			final int indexInSubtext = searchIn.substring(start, end).indexOf(searchFor);
			return indexInSubtext == -1 ? -1 : start + indexInSubtext;
		} catch (final IndexOutOfBoundsException e) {
			return -1;
		}
	}

	/**
	 * @returns first index of the string that is not whitespace or -1 if string is empty or contains only whitespaces
	 */
	private static int firstNonWhitespaceIndex(final String text) {
		for (int i = 0, len = text.length(); i < len; i++) {
			final char c = text.charAt(i);
			if (c != ' ' && c != '\t') {
				return i;
			}
		}
		return -1;
	}

	public static String getIndentationFromWhitespace(final String whitespace, final TabPrefs tabPrefs) {
		final var tab = "\t";
		int indentOffset = 0;
		boolean startsWithTab = true;
		boolean startsWithSpaces = true;
		final String spaces = tabPrefs.useSpacesForTabs
				? " ".repeat(tabPrefs.tabWidth)
				: "";
		while (startsWithTab || startsWithSpaces) {
			startsWithTab = whitespace.startsWith(tab, indentOffset);
			startsWithSpaces = tabPrefs.useSpacesForTabs && whitespace.startsWith(spaces, indentOffset);
			if (startsWithTab) {
				indentOffset += tab.length();
			}
			if (startsWithSpaces) {
				indentOffset += spaces.length();
			}
		}
		return whitespace.substring(0, indentOffset);
	}

	public static String getIndentationAtPosition(final IDocument doc, final int offset) {
		try {
			// find start offset of current line
			final int lineStartOffset = doc.getLineInformationOfOffset(offset).getOffset();

			// find white spaces
			final int indentationEndOffset = findEndOfWhiteSpace(doc, lineStartOffset, offset);

			return doc.get(lineStartOffset, indentationEndOffset - lineStartOffset);
		} catch (final BadLocationException excp) {
			// stop work
		}
		return "";
	}

	/**
	 * Returns the first offset greater than <code>offset</code> and smaller than
	 * <code>end</code> whose character is not a space or tab character. If no such
	 * offset is found, <code>end</code> is returned.
	 *
	 * @param doc the document to search in
	 * @param startAt the offset at which searching start
	 * @param endAt the offset at which searching stops
	 *
	 * @return the offset in the specified range whose character is not a space or tab
	 *
	 * @exception BadLocationException if position is an invalid range in the given document
	 */
	private static int findEndOfWhiteSpace(final IDocument doc, int startAt, final int endAt) throws BadLocationException {
		while (startAt < endAt) {
			final char c = doc.getChar(startAt);
			if (c != ' ' && c != '\t') {
				return startAt;
			}
			startAt++;
		}
		return endAt;
	}

	/**
	 * Determines if all the characters at any offset of the specified document line are the whitespace characters.
	 *
	 * @param doc the document to search in
	 * @param lineIndex zero-based document line number
	 *
	 * @return <code>true</code> if all the characters of the specified document line are the whitespace
	 *         characters, otherwise returns <code>false</code>
	 */
	public static boolean isBlankLine(final IDocument doc, final int lineIndex) {
		try {
			int offset = doc.getLineOffset(lineIndex);
			final int lineEnd = offset + doc.getLineLength(lineIndex);
			while (offset < lineEnd) {
				if (!Character.isWhitespace(doc.getChar(offset))) {
					return false;
				}
				offset++;
			}
		} catch (final BadLocationException e) {
			// Ignore, forcing a positive result
		}
		return true;
	}

	private TextUtils() {
	}
}
