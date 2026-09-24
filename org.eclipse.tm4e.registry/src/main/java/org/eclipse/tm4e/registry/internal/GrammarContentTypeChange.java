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
package org.eclipse.tm4e.registry.internal;

import static org.eclipse.tm4e.registry.GrammarContentType.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.registry.GrammarContentType;
import org.eclipse.tm4e.registry.IGrammarDefinition;

/** Applies one explicitly requested content-type update and can undo it if saving the import fails. */
final class GrammarContentTypeChange {

	private final GrammarContentType next;
	private final @Nullable GrammarContentType previous;
	private final boolean create;

	GrammarContentTypeChange(final GrammarContentType next, final @Nullable GrammarContentType previous) {
		this.next = next;
		this.previous = previous;
		// Another open dialog may have saved this import first. Do not create a second type and lose ownership of the first.
		if (previous != null && !previous.id().equals(next.id()))
			throw new IllegalArgumentException(
					"This grammar was set up in another dialog. Reopen the import to use its existing content type.");
		final IContentType existing = Platform.getContentTypeManager().getContentType(next.id());
		create = existing == null;
		if (existing != null && (previous == null || !matches(existing, previous)))
			throw new IllegalArgumentException("Content type '" + existing.getName()
					+ "' was changed outside this import. Manage it in General > Content Types, or import without changing associations.");
	}

	void apply() throws CoreException {
		final var manager = Platform.getContentTypeManager();
		final IContentType ctype;
		if (create) {
			final IContentType parent = manager.getContentType(GrammarContentType.BASE_TYPE_ID);
			if (parent == null)
				throw new IllegalStateException("The TM4E Syntax Highlighting content type is unavailable.");
			ctype = manager.addContentType(next.id(), next.name(), parent);
		} else {
			ctype = manager.getContentType(next.id());
		}
		if (ctype == null)
			throw new IllegalStateException("The content type was removed while saving: " + next.id());
		if (previous != null && !create)
			removeAssociations(ctype, previous);
		addAssociations(ctype, next);
	}

	void rollback() throws CoreException {
		final var manager = Platform.getContentTypeManager();
		final IContentType type = manager.getContentType(next.id());
		if (type == null)
			return;
		removeAssociations(type, next);
		if (create) {
			// NOTE: Eclipse 4.32 leaves filename indexes behind when removing a type. Remove specs first.
			// Keep this order while that platform version is supported, including after a partially failed add.
			manager.removeContentType(type.getId());
		} else if (previous != null) {
			addAssociations(type, previous);
		}
	}

	private static void addAssociations(final IContentType type, final GrammarContentType setup) throws CoreException {
		for (final var association : setup.fileAssociations()) {
			type.addFileSpec(fileSpecValue(association), fileSpecType(association));
		}
	}

	private static void removeAssociations(final IContentType type, final GrammarContentType setup) throws CoreException {
		for (final var association : setup.fileAssociations()) {
			type.removeFileSpec(fileSpecValue(association), fileSpecType(association));
		}
	}

	private static boolean matches(final IContentType type, final GrammarContentType saved) {
		final IContentType parent = type.getBaseType();
		// Saved imports may still use the language-pack parent. Other parents signal a manual hierarchy change.
		if (!type.isUserDefined() || !type.getName().equals(saved.name()) || parent == null
				|| !(GrammarContentType.BASE_TYPE_ID.equals(parent.getId())
						|| "org.eclipse.tm4e.language_pack.basetype".equals(parent.getId()))
				|| !Objects.equals(type.getDefaultCharset(), parent.getDefaultCharset()))
			return false;
		for (final int kind : List.of(IContentType.FILE_EXTENSION_SPEC, IContentType.FILE_NAME_SPEC, IContentType.FILE_PATTERN_SPEC)) {
			// Eclipse deduplicates specs without regard to case. Saved variants such as Makefile/makefile are not manual edits.
			final var expected = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			saved.fileAssociations().stream().filter(spec -> fileSpecType(spec) == kind)
					.map(GrammarContentType::fileSpecValue).forEach(expected::add);
			final var actual = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
			actual.addAll(Arrays.asList(type.getFileSpecs(kind)));
			if (!expected.equals(actual))
				return false;
		}
		return true;
	}

	static void checkConflicts(final AbstractGrammarRegistryManager registry, final IGrammarDefinition definition,
			final GrammarContentType setup, final Set<String> changedSources) {
		final String scope = definition.getScope().getName();
		// Bindings store the scope, not a source file. Recheck at save because another dialog may have imported that scope first.
		final IGrammarDefinition selected = registry.userDefinitions.getBestForScope(scope);
		if (selected != null
				&& !AbstractGrammarRegistryManager.sourceKey(selected).equals(AbstractGrammarRegistryManager.sourceKey(definition)))
			throw new IllegalArgumentException("Remove the earlier import with scope '" + scope
					+ "' before making this grammar the workspace default.");
		// Saved descriptions detect manual edits; they are not the current associations. Only pending setups replace
		// Eclipse's live associations here, including updates whose old associations still exist until Apply/Finish.
		final var pending = new HashMap<String, GrammarContentType>();
		for (final var source : changedSources) {
			final var other = registry.grammarContentTypes.get(source);
			if (other == null
					|| registry.userDefinitions.stream().noneMatch(item -> AbstractGrammarRegistryManager.sourceKey(item).equals(source)))
				continue;
			pending.put(other.id(), other);
			final String binding = registry.userContentTypeToScopeBindings.get(other.id());
			if (binding != null && !binding.equals(scope)
					&& overlaps(setup.fileAssociations(), other.fileAssociations()))
				throw conflict(other.name());
		}
		final var manager = Platform.getContentTypeManager();
		for (final IContentType type : manager.getAllContentTypes()) {
			IGrammarDefinition binding = null;
			for (IContentType candidate = type; candidate != null && binding == null; candidate = candidate.getBaseType()) {
				binding = registry.getUserGrammarBinding(candidate);
			}
			if (binding == null || binding.getScope().getName().equals(scope))
				continue;
			if (overlaps(setup.fileAssociations(), type, pending))
				throw conflict(type.getName());
		}
	}

	private static boolean overlaps(final List<String> associations, final IContentType type,
			final Map<String, GrammarContentType> pending) {
		for (IContentType candidate = type; candidate != null; candidate = candidate.getBaseType()) {
			final var replacement = pending.get(candidate.getId());
			if (replacement != null) {
				if (overlaps(associations, replacement.fileAssociations()))
					return true;
			} else {
				final var current = new ArrayList<>(Arrays.asList(candidate.getFileSpecs(IContentType.FILE_NAME_SPEC)));
				for (final String extension : candidate.getFileSpecs(IContentType.FILE_EXTENSION_SPEC)) {
					current.add("*." + extension);
				}
				if (overlaps(associations, current))
					return true;
				for (final String pattern : candidate.getFileSpecs(IContentType.FILE_PATTERN_SPEC)) {
					if (associations.stream().anyMatch(association -> overlapsPattern(association, pattern)))
						return true;
				}
			}
			// Eclipse inherits associations only until a type declares built-in specs. Walk that same chain, using
			// pending edits for parents too; matching sample names would still see their obsolete platform associations.
			if (candidate.getFileSpecs(IContentType.FILE_NAME_SPEC | IContentType.FILE_EXTENSION_SPEC
					| IContentType.FILE_PATTERN_SPEC | IContentType.IGNORE_USER_DEFINED).length != 0)
				break;
		}
		return false;
	}

	private static IllegalArgumentException conflict(final String name) {
		return new IllegalArgumentException("These files already have a workspace grammar choice for '" + name
				+ "'. Remove that binding first, or import without setting a workspace default.");
	}

	private static boolean overlaps(final List<String> first, final List<String> second) {
		for (final String left : first) {
			for (final String right : second) {
				final boolean leftSuffix = left.startsWith("*.");
				final boolean rightSuffix = right.startsWith("*.");
				final String leftValue = (leftSuffix ? left.substring(1) : left).toLowerCase(Locale.ROOT);
				final String rightValue = (rightSuffix ? right.substring(1) : right).toLowerCase(Locale.ROOT);
				if (leftSuffix && rightSuffix ? leftValue.endsWith(rightValue) || rightValue.endsWith(leftValue)
						: leftSuffix ? rightValue.endsWith(leftValue)
								: rightSuffix ? leftValue.endsWith(rightValue)
										: leftValue.equals(rightValue))
					return true;
			}
		}
		return false;
	}

	private static boolean overlapsPattern(final String association, final String pattern) {
		if (!association.startsWith("*.")) {
			// Eclipse treats exact filename associations without regard to case and converts patterns with these replacements.
			return Pattern.compile(pattern.replace(".", "\\.").replace('?', '.').replace("*", ".*"),
					Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(association).matches();
		}
		// Eclipse also accepts regex operators in patterns. Keep rejecting uncertain overlaps for those.
		// Ordinary '*'/'?' patterns, including paths, can be checked by comparing their ends.
		if (pattern.chars().anyMatch(ch -> "\\[](){}+|^$".indexOf(ch) >= 0))
			return true;
		final String suffix = association.substring(1).toLowerCase(Locale.ROOT);
		final String glob = pattern.toLowerCase(Locale.ROOT);
		int suffixIndex = suffix.length() - 1;
		// Only the tail after the last '*' constrains a suffix. A '*' can consume any remaining suffix characters,
		// while the import's leading '*' can supply the pattern's prefix. '?' consumes exactly one character.
		for (int patternIndex = glob.length() - 1; patternIndex >= 0 && suffixIndex >= 0; patternIndex--, suffixIndex--) {
			final char ch = glob.charAt(patternIndex);
			if (ch == '*')
				return true;
			if (ch != '?' && ch != suffix.charAt(suffixIndex))
				return false;
		}
		return suffixIndex < 0;
	}
}
