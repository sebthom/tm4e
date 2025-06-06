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
package org.eclipse.tm4e.core.grammar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.tm4e.core.registry.IGrammarSource.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.tm4e.core.Data;
import org.eclipse.tm4e.core.internal.utils.ResourceUtils;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.registry.Registry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test for grammar tokenizer.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
class GrammarTest {

	private static final String[] EXPECTED_SINGLE_LINE_TOKENS = {
		"Token from 0 to 8 with scopes [source.js, meta.function.js, storage.type.function.js]",
		"Token from 8 to 9 with scopes [source.js, meta.function.js]",
		"Token from 9 to 12 with scopes [source.js, meta.function.js, entity.name.function.js]",
		"Token from 12 to 13 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, meta.brace.round.js]",
		"Token from 13 to 14 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, parameter.name.js, variable.parameter.js]",
		"Token from 14 to 15 with scopes [source.js, meta.function.js, meta.function.type.parameter.js]",
		"Token from 15 to 16 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, parameter.name.js, variable.parameter.js]",
		"Token from 16 to 17 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, meta.brace.round.js]",
		"Token from 17 to 18 with scopes [source.js, meta.function.js]",
		"Token from 18 to 19 with scopes [source.js, meta.function.js, meta.decl.block.js, meta.brace.curly.js]",
		"Token from 19 to 20 with scopes [source.js, meta.function.js, meta.decl.block.js]",
		"Token from 20 to 26 with scopes [source.js, meta.function.js, meta.decl.block.js, keyword.control.js]",
		"Token from 26 to 28 with scopes [source.js, meta.function.js, meta.decl.block.js]",
		"Token from 28 to 29 with scopes [source.js, meta.function.js, meta.decl.block.js, keyword.operator.arithmetic.js]",
		"Token from 29 to 32 with scopes [source.js, meta.function.js, meta.decl.block.js]",
		"Token from 32 to 33 with scopes [source.js, meta.function.js, meta.decl.block.js, meta.brace.curly.js]" };

	private static final String[] EXPECTED_MULTI_LINE_TOKENS = {
		"Token from 0 to 8 with scopes [source.js, meta.function.js, storage.type.function.js]",
		"Token from 8 to 9 with scopes [source.js, meta.function.js]",
		"Token from 9 to 12 with scopes [source.js, meta.function.js, entity.name.function.js]",
		"Token from 12 to 13 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, meta.brace.round.js]",
		"Token from 13 to 14 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, parameter.name.js, variable.parameter.js]",
		"Token from 14 to 15 with scopes [source.js, meta.function.js, meta.function.type.parameter.js]",
		"Token from 15 to 16 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, parameter.name.js, variable.parameter.js]",
		"Token from 16 to 17 with scopes [source.js, meta.function.js, meta.function.type.parameter.js, meta.brace.round.js]",
		"Token from 0 to 1 with scopes [source.js, meta.function.js, meta.decl.block.js, meta.brace.curly.js]",
		"Token from 1 to 2 with scopes [source.js, meta.function.js, meta.decl.block.js]",
		"Token from 2 to 8 with scopes [source.js, meta.function.js, meta.decl.block.js, keyword.control.js]",
		"Token from 8 to 10 with scopes [source.js, meta.function.js, meta.decl.block.js]",
		"Token from 10 to 11 with scopes [source.js, meta.function.js, meta.decl.block.js, keyword.operator.arithmetic.js]",
		"Token from 11 to 14 with scopes [source.js, meta.function.js, meta.decl.block.js]",
		"Token from 14 to 15 with scopes [source.js, meta.function.js, meta.decl.block.js, meta.brace.curly.js]" };

	@Test
	void testTokenizeConcurrent() throws Exception {
		final var registry = new Registry();
		final var grammar = registry.addGrammar(fromFile(Paths.get("../org.eclipse.tm4e.language_pack/syntaxes/xml/xml.tmLanguage.json")));
		final String content = Files.readString(Paths.get("../org.eclipse.tm4e.language_pack/syntaxes/xml/xml.example.xml"));

		final int numThreads = 4;
		final int numIterations = 10;

		final var executor = Executors.newFixedThreadPool(numThreads);
		final Runnable tokenizationTask = () -> {
			for (int i = 0; i < numIterations; i++) {
				final var r = TokenizationUtils.tokenizeText(content, grammar);
				assertThat(r.count()).isGreaterThan(10);
			}
		};

		final List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < numThreads; i++) {
			futures.add(executor.submit(tokenizationTask));
		}

		for (final Future<?> future : futures) {
			future.get();
		}

		executor.shutdown();
		executor.awaitTermination(1, TimeUnit.MINUTES);
	}

	@Test
	void testTokenizeSingleLineExpression() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));
		final var lineTokens = grammar.tokenizeLine("function add(a,b) { return a+b; }");
		assertThat(lineTokens.isStoppedEarly()).isFalse();
		for (int i = 0; i < lineTokens.getTokens().length; i++) {
			final IToken token = lineTokens.getTokens()[i];
			final String s = "Token from " + token.getStartIndex() + " to " + token.getEndIndex() + " with scopes " + token.getScopes();
			assertThat(s).isEqualTo(EXPECTED_SINGLE_LINE_TOKENS[i]);
		}
	}

	@Test
	void testTokenizeMultilineExpression() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));

		IStateStack ruleStack = null;
		int i = 0;
		int j = 0;
		final String[] lines = { "function add(a,b)", "{ return a+b; }" };
		for (final String line : lines) {
			final var lineTokens = grammar.tokenizeLine(line, ruleStack, null);
			assertThat(lineTokens.isStoppedEarly()).isFalse();
			ruleStack = lineTokens.getRuleStack();
			for (i = 0; i < lineTokens.getTokens().length; i++) {
				final IToken token = lineTokens.getTokens()[i];
				final String s = "Token from " + token.getStartIndex() + " to " + token.getEndIndex() + " with scopes " + token.getScopes();
				assertThat(s).isEqualTo(EXPECTED_MULTI_LINE_TOKENS[i + j]);
			}
			j = i;
		}
	}

	@Test
	void testTokenize0Tokens() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));
		final String lineText = "";
		final var lineTokens = grammar.tokenizeLine(lineText);
		assertThat(lineTokens.isStoppedEarly()).isFalse();

		final var endIndexOffset = 1; // IToken's end-indexes are exclusive

		assertThat(lineTokens.getTokens())
				.hasOnlyOneElementSatisfying(token -> {
					assertThat(token.getStartIndex()).isZero();
					assertThat(token.getEndIndex()).isEqualTo(endIndexOffset);
				});
	}

	@Test
	void testTokenize1Token() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));
		final String lineText = "true";
		final var lineTokens = grammar.tokenizeLine(lineText);
		assertThat(lineTokens.isStoppedEarly()).isFalse();

		final var endIndexOffset = 1; // IToken's end-indexes are exclusive

		assertThat(lineTokens.getTokens())
				.hasOnlyOneElementSatisfying(token -> {
					assertThat(token.getStartIndex()).isZero();
					assertThat(token.getEndIndex()).isEqualTo(3 + endIndexOffset);
				});
	}

	@Test
	void testTokenize1TokenWithNewLine() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));
		final String lineText = "true\n";
		final var lineTokens = grammar.tokenizeLine(lineText);
		assertThat(lineTokens.isStoppedEarly()).isFalse();

		final var endIndexOffset = 1; // IToken's end-indexes are exclusive

		System.out.println(Arrays.toString(lineTokens.getTokens()));

		assertThat(lineTokens.getTokens())
				.hasOnlyOneElementSatisfying(token -> { // TODO why is only 1 token returned? The token for \n is missing
					assertThat(token.getStartIndex()).isZero();
					assertThat(token.getEndIndex()).isEqualTo(3 + endIndexOffset);
				});
	}

	@Test
	void testTokenize1IllegalToken() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));
		final String lineText = "@"; // Uncaught SyntaxError: illegal character U+0040
		final var lineTokens = grammar.tokenizeLine(lineText);
		assertThat(lineTokens.isStoppedEarly()).isFalse();

		final var endIndexOffset = 1; // IToken's end-indexes are exclusive

		assertThat(lineTokens.getTokens())
				.hasOnlyOneElementSatisfying(token -> {
					assertThat(token.getStartIndex()).isZero();
					assertThat(token.getEndIndex()).isEqualTo(0 + endIndexOffset + 1); // TODO why does end-index have extra +1 offset?
				});
	}

	@Test
	void testTokenize2Tokens() throws Exception {
		final var registry = new Registry();
		final IGrammar grammar = registry.addGrammar(fromResource(Data.class, "JavaScript.tmLanguage"));

		final String lineText = "{}";
		final var lineTokens = grammar.tokenizeLine(lineText);
		assertThat(lineTokens.isStoppedEarly()).isFalse();

		final var endIndexOffset = 1; // IToken's end-indexes are exclusive

		assertThat(lineTokens.getTokens())
				.hasSize(2)
				.satisfiesExactly(
						first -> {
							assertThat(first.getStartIndex()).isZero();
							assertThat(first.getEndIndex()).isEqualTo(endIndexOffset);
						},
						second -> {
							assertThat(second.getStartIndex()).isEqualTo(1);
							assertThat(second.getEndIndex()).isEqualTo(1 + endIndexOffset);
						});
	}

	@Test
	void testTokenizeMultilineYaml() throws Exception {
		final var registry = new Registry();
		final var grammar = registry.addGrammar(fromResource(Data.class, "yaml.tmLanguage.json"));
		final var lines = ">\n should.be.string.unquoted.block.yaml\n should.also.be.string.unquoted.block.yaml";
		final var result = TokenizationUtils.tokenizeText(lines, grammar).iterator();
		assertThat(result.next().getTokens()).anyMatch(t -> t.getScopes().contains("keyword.control.flow.block-scalar.folded.yaml"));
		assertThat(result.next().getTokens()).anyMatch(t -> t.getScopes().contains("string.unquoted.block.yaml"));
		assertThat(result.next().getTokens()).anyMatch(t -> t.getScopes().contains("string.unquoted.block.yaml"));
	}

	@Test
	void testTokenizeTypeScriptFile() throws Exception {
		final var grammar = new Registry().addGrammar(fromResource(Data.class, "TypeScript.tmLanguage.json"));

		final List<String> expectedTokens;
		try (var reader = ResourceUtils.getResourceReader(Data.class, "raytracer_tokens.txt")) {
			expectedTokens = reader.lines().toList();
		}

		IStateStack stateStack = null;
		int tokenIndex = -1;
		try (var reader = ResourceUtils.getResourceReader(Data.class, "raytracer.ts")) {
			String line;
			while ((line = reader.readLine()) != null) {
				final var lineTokens = grammar.tokenizeLine(line, stateStack, null);
				stateStack = lineTokens.getRuleStack();
				for (int i = 0; i < lineTokens.getTokens().length; i++) {
					tokenIndex++;
					final var token = lineTokens.getTokens()[i];
					assertThat("Token from " + token.getStartIndex() + " to " + token.getEndIndex() + " with scopes "
							+ token.getScopes()).isEqualTo(expectedTokens.get(tokenIndex));
				}
			}
		}
	}

	@Test
	void testTokenizeWithTimeout() throws IOException {
		final var grammar = new Registry().addGrammar(fromResource(Data.class, "TypeScript.tmLanguage.json"));

		try (var reader = ResourceUtils.getResourceReader(Data.class, "raytracer.ts")) {
			final String veryLongLine = reader.lines().collect(Collectors.joining());
			final var result1 = grammar.tokenizeLine(veryLongLine);
			assertThat(result1.isStoppedEarly()).isFalse();

			final var lastToken1 = result1.getTokens()[result1.getTokens().length - 1];

			final var result2 = grammar.tokenizeLine(veryLongLine, null, Duration.ofMillis(10));
			assertThat(result2.isStoppedEarly()).isTrue();
			assertThat(result2.getTokens()).hasSizeLessThan(result1.getTokens().length);
			assertThat(result1.getTokens()).contains(result2.getTokens());
			final var lastToken2 = result2.getTokens()[result2.getTokens().length - 1];
			assertThat(lastToken2.getEndIndex()).isLessThan(lastToken1.getEndIndex());
		}
	}

	// TODO see https://github.com/microsoft/vscode-textmate/issues/173
	@Disabled
	@Test
	void testShadowedRulesAreResolvedCorrectly() {
		final var registry = new Registry();
		final var grammar = registry.addGrammar(fromString(IGrammarSource.ContentType.JSON, """
			{
				"scopeName": "source.test",
				"repository": {
					"foo": {
						"include": "#bar"
					},
					"bar": {
						"match": "bar1",
						"name": "outer"
					}
				},
				"patterns": [{
						"patterns": [{
							"include": "#foo"
						}],
						"repository": {
							"bar": {
								"match": "bar1",
								"name": "inner"
							}
						}
					},
					{
						"begin": "begin",
						"patterns": [{
							"include": "#foo"
						}],
						"end": "end"
					}
				]
			}
			"""));

		final var lineTokens = grammar.tokenizeLine("bar1");
		assertThat(lineTokens.isStoppedEarly()).isFalse();

		assertThat(lineTokens.getTokens())
				.hasOnlyOneElementSatisfying(token -> {
					assertThat(token.getStartIndex()).isZero();
					assertThat(token.getEndIndex()).isEqualTo(4);
					assertThat(token.getScopes()).containsExactly("source.test", "outer");
				});
	}
}
