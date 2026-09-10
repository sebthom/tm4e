/*******************************************************************************
 * Copyright (c) 2026 Vegard IT GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * - Sebastian Thomschke (Vegard IT) - initial implementation
 *******************************************************************************/
package org.eclipse.tm4e.ui.tests.internal.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.jface.text.templates.persistence.TemplateStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.templates.TMTemplateCompletionProcessor;
import org.eclipse.tm4e.ui.internal.utils.UI;
import org.eclipse.tm4e.ui.templates.DefaultTMTemplateContextType;
import org.eclipse.tm4e.ui.tests.support.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies template completion after delayed UI dispatch and at document boundaries.
 */
class TMTemplateCompletionProcessorTest {

	private static final String TEMPLATE_NAME = "tm4e1040";

	private final TMTemplateCompletionProcessor processor = new TMTemplateCompletionProcessor();
	private final Document document = new Document(TEMPLATE_NAME);
	private final List<IStatus> errors = new CopyOnWriteArrayList<>();
	private final ILog log = Platform.getLog(TMTemplateCompletionProcessor.class);
	private final ILogListener logListener = (status, pluginId) -> {
		if (status.matches(IStatus.ERROR)) {
			errors.add(status);
		}
	};

	private Shell shell;
	private TextViewer viewer;
	private TemplateStore templateStore;
	private TemplatePersistenceData templateData;

	@BeforeEach
	void setup() {
		shell = new Shell(UI.getDisplay());
		viewer = new TextViewer(shell, SWT.NONE);
		viewer.setDocument(document);
		viewer.setSelectedRange(document.getLength(), 0);

		final var plugin = TMUIPlugin.getDefault();
		assertThat(plugin).isNotNull();
		templateStore = plugin.getTemplateStore();
		// A temporary template makes valid-boundary checks independent of contributed templates and preferences.
		templateData = new TemplatePersistenceData(new Template(TEMPLATE_NAME, "Regression test",
				DefaultTMTemplateContextType.CONTEXT_ID, "replacement", false), true);
		templateStore.add(templateData);
		log.addLogListener(logListener);
	}

	@AfterEach
	void tearDown() {
		log.removeLogListener(logListener);
		// Completion uses shared plugin services; release only the template and model created by this test.
		templateStore.delete(templateData);
		TMUIPlugin.getTMModelManager().disconnect(document);
		shell.dispose();
		assertThat(errors).as("Template completion must not log errors").isEmpty();
	}

	@Test
	void rejectsOffsetAfterDocumentShrinksBeforeUIDispatch() throws Exception {
		final var completion = startCompletion(document.getLength());

		// The test holds the UI thread until after deletion, so the worker's UI callback sees the shorter document.
		document.replace(document.getLength() - 1, 1, "");
		viewer.setSelectedRange(document.getLength(), 0);

		assertThat(awaitCompletion(completion)).isEmpty();
	}

	@Test
	void completesAtDocumentEndFromBackgroundThread() throws Exception {
		assertAppliesTemplate(awaitCompletion(startCompletion(document.getLength())));
	}

	@Test
	void completesInEmptyDocument() {
		document.set("");
		viewer.setSelectedRange(0, 0);
		assertAppliesTemplate(processor.computeCompletionProposals(viewer, 0));
	}

	@Test
	void rejectsNegativeOffset() {
		assertThat(processor.computeCompletionProposals(viewer, -1)).isEmpty();
	}

	@Test
	void rejectsMissingDocument() {
		viewer.setDocument(null);
		assertThat(processor.computeCompletionProposals(viewer, 0)).isEmpty();
	}

	private CompletableFuture<ICompletionProposal[]> startCompletion(final int offset) throws InterruptedException {
		final var started = new CountDownLatch(1);
		final var completion = CompletableFuture.supplyAsync(() -> {
			started.countDown();
			return processor.computeCompletionProposals(viewer, offset);
		});
		assertThat(started.await(5, TimeUnit.SECONDS)).as("Completion worker started").isTrue();
		return completion;
	}

	private ICompletionProposal[] awaitCompletion(final CompletableFuture<ICompletionProposal[]> completion) throws Exception {
		// Pump UI events while waiting: the completion worker blocks in UI.runSync().
		TestUtils.waitForAndAssertCondition(5_000, completion::isDone);
		return completion.get();
	}

	private void assertAppliesTemplate(final ICompletionProposal[] proposals) {
		assertThat(proposals).filteredOn(proposal -> proposal.getDisplayString().startsWith(TEMPLATE_NAME))
				.singleElement().isInstanceOfSatisfying(ICompletionProposalExtension2.class, proposal -> {
					// TemplateProposal's document-only apply method is a no-op; use the same entry point as content assist.
					proposal.apply(viewer, '\0', 0, document.getLength());
					assertThat(document.get()).isEqualTo("replacement");
				});
	}
}
