/**
 * Copyright (c) 2025 Vegard IT GmbH and others.
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
package org.eclipse.tm4e.languageconfiguration.internal.folding;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerLifecycle;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.projection.IProjectionListener;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.tm4e.ui.internal.utils.UI;

/**
 * Provides shared setup, cleanup, and scan cancellation for folding strategies.
 * Background scans update annotations on the UI thread only if their document and scan version are still current.
 */
public abstract class AbstractFoldingStrategy
		implements IReconcilingStrategy, IReconcilingStrategyExtension, ITextViewerLifecycle {

	protected volatile @Nullable IDocument document;
	protected volatile @Nullable ProjectionAnnotationModel projectionAnnotationModel;
	protected volatile @Nullable ITextViewer textViewer;
	protected volatile @Nullable ProjectionViewer projectionViewer;

	private static final Annotation[] EMPTY_ANNOTATIONS = new Annotation[0];
	// Both UI lifecycle changes and new worker scans invalidate queued results without waiting for a running scan.
	private final AtomicLong reconciliationVersion = new AtomicLong();

	private final IProjectionListener projectionListener = new IProjectionListener() {
		@Override
		public void projectionDisabled() {
			projectionAnnotationModel = null;
			reconciliationVersion.incrementAndGet();
		}

		@Override
		public void projectionEnabled() {
			if (projectionViewer != null) {
				projectionAnnotationModel = projectionViewer.getProjectionAnnotationModel();
			}
			reconciliationVersion.incrementAndGet();
		}
	};

	protected final long beginReconcile() {
		return reconciliationVersion.incrementAndGet();
	}

	protected final boolean isCurrentReconcile(final long version) {
		return reconciliationVersion.get() == version;
	}

	/**
	 * Schedules an annotation comparison and update on the UI thread. Discards the update if the scan is no longer current.
	 *
	 * @param version the value returned by {@link #beginReconcile()} before reading the scan's input
	 * @param annotationModel the model captured with the scan's input
	 * @param update compares scanned ranges with the current annotations and applies the differences
	 */
	protected void updateAnnotations(final long version, final ProjectionAnnotationModel annotationModel, final Runnable update) {
		if (!isCurrentReconcile(version))
			return;

		// Compare and apply together on the UI thread. Computing differences before queuing them could add duplicate
		// folds when another scan's changes are still queued. UI lifecycle callbacks also cannot interrupt this update.
		UI.getDisplay().asyncExec(() -> {
			if (isCurrentReconcile(version) && projectionAnnotationModel == annotationModel)
				update.run();
		});
	}

	/**
	 * Applies annotation changes on the UI thread.
	 * Call this from the callback passed to {@link #updateAnnotations(long, ProjectionAnnotationModel, Runnable)}.
	 *
	 * @param annotationModel the model compared with the scanned ranges
	 * @param deletions the list of deleted annotations
	 * @param additions the set of annotations to add together with their associated position
	 * @param modifications the list of modified annotations
	 */
	protected void modifyAnnotations(final ProjectionAnnotationModel annotationModel,
			final Collection<? extends Annotation> deletions,
			final Map<? extends Annotation, ? extends Position> additions,
			final Collection<? extends Annotation> modifications) {
		if (deletions.isEmpty() && additions.isEmpty() && modifications.isEmpty())
			return;

		annotationModel.modifyAnnotations(
				deletions.isEmpty() ? EMPTY_ANNOTATIONS : deletions.toArray(Annotation[]::new),
				additions,
				modifications.isEmpty() ? EMPTY_ANNOTATIONS : modifications.toArray(Annotation[]::new));
	}

	@Override
	public void initialReconcile() {
		reconcile(null);
	}

	@Override
	public void install(final @Nullable ITextViewer textViewer) {
		if (projectionViewer != null) {
			projectionViewer.removeProjectionListener(projectionListener);
		}
		this.textViewer = textViewer;
		if (textViewer instanceof final ProjectionViewer projViewer) {
			projectionViewer = projViewer;
			projViewer.addProjectionListener(projectionListener);
			projectionAnnotationModel = projViewer.getProjectionAnnotationModel();
		}
		reconciliationVersion.incrementAndGet();
	}

	@Override
	public abstract void reconcile(final DirtyRegion dirtyRegion, final @Nullable IRegion subRegion);

	@Override
	public void reconcile(final @Nullable IRegion subRegion) {
		final var document = this.document;
		if (document == null)
			return;

		if (subRegion != null) {
			try {
				reconcile(new DirtyRegion(subRegion.getOffset(), subRegion.getLength(), DirtyRegion.INSERT,
						document.get(subRegion.getOffset(), subRegion.getLength())), subRegion);
				return;
			} catch (final BadLocationException ex) {
				// ignore
			}
		}

		reconcile(new DirtyRegion(0, document.getLength(), DirtyRegion.INSERT, document.get()), null);
	}

	@Override
	public void setDocument(final @Nullable IDocument document) {
		this.document = document;
		// The same document can now use another language, so document identity alone cannot cancel a stale scan.
		reconciliationVersion.incrementAndGet();
	}

	@Override
	public void setProgressMonitor(final @Nullable IProgressMonitor monitor) {
		// Nothing to do
	}

	@Override
	public void uninstall() {
		setDocument(null);
		if (projectionViewer != null) {
			projectionViewer.removeProjectionListener(projectionListener);
			projectionViewer = null;
		}
		projectionListener.projectionDisabled();
		textViewer = null;
	}
}
