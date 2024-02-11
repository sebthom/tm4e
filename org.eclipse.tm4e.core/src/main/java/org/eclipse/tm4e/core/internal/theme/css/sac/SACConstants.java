/*******************************************************************************
 * Copyright (c) 2008, 2013 Angelo Zerr and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.tm4e.core.internal.theme.css.sac;

/**
 * SAC Constants Parsers.
 *
 * @see <a href=
 *      "https://github.com/eclipse-platform/eclipse.platform.ui/blob/master/bundles/org.eclipse.e4.ui.css.core/src/org/eclipse/e4/ui/css/core/SACConstants.java">github.com/eclipse-platform/eclipse.platform.ui/blob/master/bundles/org.eclipse.e4.ui.css.core/src/org/eclipse/e4/ui/css/core/SACConstants.java</a>
 *
 * @author <a href="mailto:angelo.zerr@gmail.com">Angelo ZERR</a>
 *
 * @version 1.0.0
 */
final class SACConstants {

	/**
	 * org.w3c.flute.parser.Parser SAC Parser.
	 */
	static final String SACPARSER_FLUTE = "org.w3c.flute.parser.Parser";

	/**
	 * org.w3c.flute.parser.Parser SAC Parser.
	 */
	static final String SACPARSER_FLUTE_CSS3 = "org.w3c.flute.parser.CSS3Parser";

	/**
	 * com.steadystate.css.parser.SACParser SAC Parser
	 */
	static final String SACPARSER_STEADYSTATE = "com.steadystate.css.parser.SACParser";

	/**
	 * org.apache.batik.css.parser.Parser SAC Parser.
	 */
	static final String SACPARSER_BATIK = "org.apache.batik.css.parser.Parser";

	private SACConstants() {
	}
}
