/**
 *  Copyright (c) 2015-2016 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - translation and adaptation to Java
 */
package org.eclipse.tm4e.core.internal.oniguruma;

public class OnigScanner {

	private final OnigSearcher searcher;

	public OnigScanner(String[] regexps) {
		this.searcher = new OnigSearcher(regexps);
	}

	public IOnigNextMatchResult _findNextMatchSync(OnigString lin, int pos) {
		OnigResult bestResult = searcher.search(lin, pos);
		return bestResult;
	}

	public IOnigNextMatchResult _findNextMatchSync(String lin, int pos) {
		return _findNextMatchSync(new OnigString(lin), pos);
	}
}