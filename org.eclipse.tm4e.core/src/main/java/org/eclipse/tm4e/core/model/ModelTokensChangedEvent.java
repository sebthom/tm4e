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
package org.eclipse.tm4e.core.model;

import java.util.Arrays;
import java.util.List;

/**
 * Model tokens changed event.
 *
 * @see <a href="https://github.com/microsoft/vscode/blob/main/src/vs/editor/common/textModelEvents.ts">
 *      github.com/microsoft/vscode/blob/main/src/vs/editor/common/textModelEvents.ts</a>
 */
public class ModelTokensChangedEvent {

	public final List<Range> ranges;
	public final ITMModel model;

	public ModelTokensChangedEvent(final Range range, final ITMModel model) {
		this(Arrays.asList(range), model);
	}

	public ModelTokensChangedEvent(final List<Range> ranges, final ITMModel model) {
		this.ranges = ranges;
		this.model = model;
	}
}
