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
package org.eclipse.tm4e.languageconfiguration.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.AnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.tm4e.languageconfiguration.internal.folding.CompositeFoldingStrategy;
import org.eclipse.tm4e.ui.internal.utils.UI;
import org.eclipse.tm4e.ui.tests.support.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that reconciling or closing one editor does not affect another editor's folding state.
 * Also checks that running scans do not block UI callbacks or publish results after disposal.
 */
class CompositeFoldingStrategyTest {

	/** Pauses one real folding scan without depending on document size or regex performance. */
	private static final class PausingDocument extends Document {
		final CountDownLatch scanStarted = new CountDownLatch(1);
		final CountDownLatch releaseScan = new CountDownLatch(1);
		volatile boolean pauseNextScan;

		PausingDocument() {
			super("root\n    child\nend\n");
		}

		@Override
		public int getLineOffset(final int line) throws BadLocationException {
			if (pauseNextScan && !UI.isUIThread()) {
				pauseNextScan = false;
				scanStarted.countDown();
				try {
					if (!releaseScan.await(10, TimeUnit.SECONDS))
						throw new AssertionError("The test did not release the folding scan.");
				} catch (final InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new AssertionError(ex);
				}
			}
			return super.getLineOffset(line);
		}
	}


	private final CompositeFoldingStrategy firstStrategy = new CompositeFoldingStrategy();
	private final CompositeFoldingStrategy secondStrategy = new CompositeFoldingStrategy();
	private Shell shell;
	private ProjectionViewer firstViewer;
	private ProjectionViewer secondViewer;

	@BeforeEach
	void setup() {
		shell = new Shell(UI.getDisplay());
		firstViewer = createViewer(firstStrategy, "first\n    child\nend\n");
		secondViewer = createViewer(secondStrategy, "second\n    child\nend\n");
	}

	private ProjectionViewer createViewer(final CompositeFoldingStrategy strategy, final String text) {
		final var viewer = new ProjectionViewer(shell, null, null, false, SWT.NONE);
		viewer.setDocument(new Document(text), new AnnotationModel());
		viewer.enableProjection();
		strategy.install(viewer);
		strategy.setDocument(viewer.getDocument());
		return viewer;
	}

	@AfterEach
	void teardown() {
		firstStrategy.uninstall();
		secondStrategy.uninstall();
		shell.dispose();
	}

	@Test
	void reconcilingOnlyUpdatesItsOwnEditor() throws BadLocationException {
		firstStrategy.initialReconcile();
		assertFoldingRegions(firstViewer, "first\n    child\n");
		assertFoldingRegions(secondViewer);

		secondStrategy.initialReconcile();
		assertFoldingRegions(secondViewer, "second\n    child\n");
		assertFoldingRegions(firstViewer, "first\n    child\n");
	}

	@Test
	void uninstallingOneEditorKeepsTheOtherEditorsFolding() throws BadLocationException {
		firstStrategy.uninstall();
		secondStrategy.initialReconcile();
		assertFoldingRegions(secondViewer, "second\n    child\n");
	}

	@Test
	void languageChangeDoesNotWaitForTheRunningScan() throws Exception {
		assertUiCallbackDoesNotWait(false);
	}

	@Test
	void uninstallDoesNotWaitOrAllowLateAnnotations() throws Exception {
		assertUiCallbackDoesNotWait(true);
	}

	private void assertUiCallbackDoesNotWait(final boolean uninstall) throws Exception {
		final var shell = new Shell(UI.getDisplay());
		final var viewer = new ProjectionViewer(shell, null, null, false, SWT.NONE);
		final var document = new PausingDocument();
		viewer.setDocument(document, new AnnotationModel());
		viewer.enableProjection();
		final var annotations = viewer.getProjectionAnnotationModel();
		final var strategy = new CompositeFoldingStrategy();
		strategy.install(viewer);
		strategy.setDocument(document);
		final var uiReturned = new CountDownLatch(1);
		try (var workers = Executors.newFixedThreadPool(2)) {
			document.pauseNextScan = true;
			final var scan = workers.submit(strategy::initialReconcile);
			assertThat(document.scanStarted.await(5, TimeUnit.SECONDS)).as("Background scan started").isTrue();
			// Release a blocked implementation after the deadline, so a failing test cannot hang SWT.
			final var watchdog = workers.submit(() -> {
				if (!uiReturned.await(2, TimeUnit.SECONDS))
					document.releaseScan.countDown();
				return null;
			});
			try {
				if (uninstall) {
					strategy.uninstall();
				} else {
					// Invoke the actual language callback without involving resource metadata in this concurrency test.
					final var refresh = CompositeFoldingStrategy.class.getDeclaredMethod("refreshLanguage", IDocument.class);
					refresh.setAccessible(true);
					refresh.invoke(strategy, document);
				}
				uiReturned.countDown();
				assertThat(document.releaseScan.getCount()).as("UI callback returned while the scan was paused").isEqualTo(1);
			} finally {
				uiReturned.countDown();
				document.releaseScan.countDown();
			}
			scan.get(5, TimeUnit.SECONDS);
			watchdog.get(5, TimeUnit.SECONDS);
			if (uninstall) {
				// Process queued UI work before checking that the uninstalled strategy added no annotations.
				while (UI.getDisplay().readAndDispatch()) {
				}
				assertThat(annotations.getAnnotationIterator().hasNext()).isFalse();
			} else {
				// Queue two completed scans before dispatching UI work. They must not add the same fold twice.
				workers.submit(strategy::initialReconcile).get(5, TimeUnit.SECONDS);
				workers.submit(strategy::initialReconcile).get(5, TimeUnit.SECONDS);
				TestUtils.waitForAndAssertCondition(5_000, () -> annotations.getAnnotationIterator().hasNext());
				while (UI.getDisplay().readAndDispatch()) {
				}
				final var folds = annotations.getAnnotationIterator();
				folds.next();
				assertThat(folds.hasNext()).as("One annotation for the single indentation block").isFalse();
			}
		} finally {
			document.releaseScan.countDown();
			strategy.uninstall();
			shell.dispose();
		}
	}

	private static void assertFoldingRegions(final ProjectionViewer viewer, final String... expected) throws BadLocationException {
		// Folding results are queued on the UI thread even when the scan is invoked directly.
		while (UI.getDisplay().readAndDispatch()) {
		}
		final var model = viewer.getProjectionAnnotationModel();
		final var regions = new ArrayList<String>();
		final var annotations = model.getAnnotationIterator();
		while (annotations.hasNext()) {
			final var position = model.getPosition(annotations.next());
			regions.add(viewer.getDocument().get(position.offset, position.length));
		}
		assertThat(regions).containsExactlyInAnyOrder(expected);
	}
}
