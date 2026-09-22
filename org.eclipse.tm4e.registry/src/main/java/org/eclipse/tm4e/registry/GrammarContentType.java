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
package org.eclipse.tm4e.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.runtime.content.IContentType;

/**
 * Immutable description of a content type created for an imported grammar.
 * Keeping the saved description separate from Eclipse's mutable content type lets edit sessions detect manual changes.
 * Associations use exact file names or suffixes such as {@code *.m} and {@code *.spec.rb}.
 */
public record GrammarContentType(String id, String name, List<String> fileAssociations) {

	/** Shared parent for imported and bundled language types, available without the optional language pack. */
	public static final String BASE_TYPE_ID = "org.eclipse.tm4e.registry.basetype";

	public GrammarContentType {
		fileAssociations = List.copyOf(fileAssociations);
		if (id.isBlank() || name.isBlank() || fileAssociations.isEmpty())
			throw new IllegalArgumentException("A language name and at least one file association are required.");
		fileAssociations.forEach(GrammarContentType::fileSpecType);
	}

	/** Validates an association and returns the Eclipse file-spec kind that preserves its meaning. */
	public static int fileSpecType(final String association) {
		final boolean suffix = association.startsWith("*.");
		final String value = suffix ? association.substring(2) : association;
		if (value.isBlank() || !value.equals(value.strip()) || value.chars().anyMatch(ch -> "*?/,;\\\r\n".indexOf(ch) >= 0))
			throw new IllegalArgumentException("Use file names or *.extension. Paths and other wildcard patterns are not supported: "
					+ association);
		if (!suffix)
			return IContentType.FILE_NAME_SPEC;
		if (value.indexOf('.') < 0)
			return IContentType.FILE_EXTENSION_SPEC;
		// Eclipse's pattern conversion does not escape every regular-expression character.
		// Restrict compound suffixes rather than quietly giving characters such as '+' another meaning.
		if (!value.matches("[\\p{L}\\p{N}_.-]+"))
			throw new IllegalArgumentException("This compound suffix cannot be represented safely in Eclipse: " + association);
		return IContentType.FILE_PATTERN_SPEC;
	}

	public static String fileSpecValue(final String association) {
		return fileSpecType(association) == IContentType.FILE_EXTENSION_SPEC ? association.substring(2) : association;
	}

	/**
	 * Suggests both name and suffix matches because TextMate's fileTypes entries do not distinguish between them.
	 * Unsupported path entries stay visible so the user can correct or clear them before importing.
	 */
	public static List<String> suggestFileAssociations(final Collection<String> fileTypes) {
		final var associations = new ArrayList<String>();
		for (final String fileType : fileTypes) {
			associations.add(fileType);
			if (!fileType.contains("/") && !fileType.contains("\\") && !fileType.startsWith("."))
				associations.add("*." + fileType);
		}
		return associations.stream().distinct().toList();
	}
}
