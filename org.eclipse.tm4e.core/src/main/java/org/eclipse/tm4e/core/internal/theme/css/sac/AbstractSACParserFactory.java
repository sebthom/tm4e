/*******************************************************************************
 * Copyright (c) 2008, 2014 Angelo Zerr and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.tm4e.core.internal.theme.css.sac;

import static java.lang.System.Logger.Level.ERROR;

import java.lang.System.Logger;

import org.eclipse.jdt.annotation.Nullable;
import org.w3c.css.sac.Parser;
import org.w3c.css.sac.helpers.ParserFactory;

/**
 * SAC Parser Factory.
 *
 * @see <a href=
 *      "https://github.com/eclipse-platform/eclipse.platform.ui/blob/master/bundles/org.eclipse.e4.ui.css.core/src/org/eclipse/e4/ui/css/core/sac/SACParserFactory.java">github.com/eclipse-platform/eclipse.platform.ui/blob/master/bundles/org.eclipse.e4.ui.css.core/src/org/eclipse/e4/ui/css/core/sac/SACParserFactory.java</a>
 */
public abstract class AbstractSACParserFactory extends ParserFactory implements ISACParserFactory {

	private static final Logger LOGGER = System.getLogger(AbstractSACParserFactory.class.getName());

	private @Nullable String preferredParserName;

	/**
	 * Return default instance of SAC Parser. If preferredParserName is filled,
	 * it return the instance of SAC Parser registered with this name, otherwise
	 * this method search the SAC Parser class name to instantiate into System
	 * property with key org.w3c.css.sac.parser.
	 */
	@Override
	public Parser makeParser() throws ClassNotFoundException, IllegalAccessException, InstantiationException {
		try {
			if (preferredParserName != null) {
				return makeParser(preferredParserName);
			}
		} catch (final Exception | LinkageError ex) {
			LOGGER.log(ERROR, ex.getMessage(), ex);
		}
		return super.makeParser();
	}

	@Override
	public @Nullable String getPreferredParserName() {
		return preferredParserName;
	}

	@Override
	public void setPreferredParserName(final @Nullable String preferredParserName) {
		this.preferredParserName = preferredParserName;
	}
}
