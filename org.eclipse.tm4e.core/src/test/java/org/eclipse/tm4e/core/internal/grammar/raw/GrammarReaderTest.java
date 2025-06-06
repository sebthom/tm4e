/**
 * Copyright (c) 2022 Sebastian Thomschke and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Sebastian Thomschke - initial implementation
 */
package org.eclipse.tm4e.core.internal.grammar.raw;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.tm4e.core.Data;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.junit.jupiter.api.Test;

class GrammarReaderTest {

	/**
	 * Loads the same TextMate grammar in different formats and checks loading them results in equal IRawGrammar objects.
	 */
	@Test
	void testLoadDifferentPlistFormats() throws Exception {
		final var grammarFromXML = RawGrammarReader.readGrammar(IGrammarSource.fromResource(Data.class, "JavaScript.tmLanguage"));
		assertThat(grammarFromXML).isNotNull();
		assertThat(grammarFromXML.getFileTypes()).isNotEmpty();

		final var grammarFromJSON = RawGrammarReader.readGrammar(IGrammarSource.fromResource(Data.class, "JavaScript.tmLanguage.json"));
		assertThat(grammarFromJSON).isEqualTo(grammarFromXML);

		final var grammarFromYAML = RawGrammarReader.readGrammar(IGrammarSource.fromResource(Data.class, "JavaScript.tmLanguage.yaml"));
		assertThat(grammarFromYAML).isEqualTo(grammarFromJSON);
	}
}
