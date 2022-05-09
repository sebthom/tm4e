/**
 * Copyright (c) 2022 Sebastian Thomschke and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.tm4e.core.grammar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.eclipse.tm4e.core.registry.Registry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgs = { "-Xms512M", "-Xmx512M" })
@Warmup(iterations = 3)
@Measurement(iterations = 3)
public class GrammarBenchmark {

	public static void main(String... args) throws Exception {
		var r = new Runner(new OptionsBuilder()
				.include(GrammarBenchmark.class.getSimpleName())
				.shouldDoGC(true)
				.build());
		r.run();
	}

	private IGrammar grammar;
	private String[] source;

	@Setup
	public void setup() throws Exception {
		final var registry = new Registry();
		try (
				var grammarIS = GrammarBenchmark.class.getResourceAsStream("GrammarBenchmark.Java.tmLanguage.json");
				var sourceIS = GrammarBenchmark.class.getResourceAsStream("GrammarBenchmark.JavaFile.txt")) {
			grammar = registry.loadGrammarFromPathSync("GrammarBenchmark.Java.tmLanguage.json", grammarIS);
			source = new BufferedReader(new InputStreamReader(sourceIS, StandardCharsets.UTF_8))
					.lines()
					.toArray(String[]::new);
		}
	}

	@Benchmark
	public void benchmarkTokenizeLine() {
		IStackElement prev = null;
		for (var line : source) {
			prev = grammar.tokenizeLine(line, prev).getRuleStack();
		}
	}
}