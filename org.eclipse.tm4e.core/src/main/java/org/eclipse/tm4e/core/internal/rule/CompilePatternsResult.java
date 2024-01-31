/**
 * Copyright (c) 2015-2017 Angelo ZERR.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Initial code from https://github.com/microsoft/vscode-textmate/
 * Initial copyright Copyright (C) Microsoft Corporation. All rights reserved.
 * Initial license: MIT
 *
 * Contributors:
 * - Microsoft Corporation: Initial code, written in TypeScript, licensed under MIT license
 * - Angelo Zerr <angelo.zerr@gmail.com> - translation and adaptation to Java
 */
package org.eclipse.tm4e.core.internal.rule;

/**
 * @see <a href=
 *      "https://github.com/microsoft/vscode-textmate/blob/09effd8b7429b71010e0fa34ea2e16e622692946/src/rule.ts#L91">
 *      github.com/microsoft/vscode-textmate/blob/main/src/rule.ts</a>
 */
final class CompilePatternsResult {

	final RuleId[] patterns;
	final boolean hasMissingPatterns;

	CompilePatternsResult(final RuleId[] patterns, final boolean hasMissingPatterns) {
		this.hasMissingPatterns = hasMissingPatterns;
		this.patterns = patterns;
	}
}
