/**
 * Copyright (c) 2018 Angelo ZERR.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * - Angelo Zerr <angelo.zerr@gmail.com> - initial implementation
 * - Sebastian Thomschke (Vegard IT) - code adapted from lspe4/LSPFoldingReconcilingStrategy and modernized
 */
package org.eclipse.tm4e.languageconfiguration.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.NonNullByDefault;
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
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;

abstract class AbstractFoldingStrategy
		implements IReconcilingStrategy, IReconcilingStrategyExtension, IProjectionListener, ITextViewerLifecycle {

	/**
	 * A FoldingAnnotation is a {@link ProjectionAnnotation} it is folding and overriding the
	 * paint method (in a hacky type way) to prevent one line folding annotations to be drawn.
	 */
	private static final class FoldingAnnotation extends ProjectionAnnotation {
		private boolean visible; /* workaround for BUG85874 */

		/**
		 * @param isCollapsed true if this annotation should be collapsed, false otherwise
		 */
		FoldingAnnotation(boolean isCollapsed) {
			super(isCollapsed);
			visible = false;
		}

		/**
		 * Does not paint hidden annotations. Annotations are hidden when they only span one line.
		 *
		 * @see ProjectionAnnotation#paint(GC, Canvas, Rectangle)
		 */
		@Override
		@NonNullByDefault({})
		public void paint(final GC gc, final Canvas canvas, final Rectangle rectangle) {
			/* workaround for BUG85874 */
			/*
			 * only need to check annotations that are expanded because hidden annotations
			 * should never have been given the chance to collapse.
			 */
			if (!isCollapsed()) {
				// working with rectangle, so line height
				final FontMetrics metrics = gc.getFontMetrics();
				if (metrics != null && ((rectangle.height / metrics.getHeight()) <= 1)) {
					// do not draw annotations that only span one line and mark them as not visible
					visible = false;
					return;
				}
			}
			visible = true;
			super.paint(gc, canvas, rectangle);
		}

		@Override
		public void markCollapsed() {
			/* workaround for BUG85874 */
			// do not mark collapsed if annotation is not visible
			if (visible)
				super.markCollapsed();
		}
	}

	protected record FoldingRange(int startLine, int endLine, boolean autoCollapse) {
	}

	protected @Nullable IDocument document;
	protected @Nullable ProjectionAnnotationModel projectionAnnotationModel;
	protected @Nullable ProjectionViewer viewer;

	protected void applyFolding(final @Nullable List<FoldingRange> ranges) {
		// these are what are passed off to the annotation model to actually create and maintain the annotations
		final var modifications = new ArrayList<FoldingAnnotation>();
		final var deletions = new ArrayList<FoldingAnnotation>();
		final var existing = new ArrayList<FoldingAnnotation>();
		final var additions = new HashMap<FoldingAnnotation, Position>();

		// find and mark all folding annotations with length 0 for deletion
		markInvalidAnnotationsForDeletion(deletions, existing);

		try {
			if (ranges != null) {
				Collections.sort(ranges, Comparator.comparing(FoldingRange::endLine));
				for (final FoldingRange foldingRange : ranges) {
					updateAnnotation(
							modifications,
							deletions,
							existing,
							additions,
							foldingRange.startLine(),
							foldingRange.endLine(),
							foldingRange.autoCollapse);
				}
			}
		} catch (final BadLocationException ex) {
			// should never occur
		}

		// be sure projection has not been disabled
		final var projectionAnnotationModel = this.projectionAnnotationModel;
		if (projectionAnnotationModel != null) {
			if (!existing.isEmpty()) {
				deletions.addAll(existing);
			}
			// send the calculated updates to the annotations to the annotation model
			projectionAnnotationModel.modifyAnnotations(
					deletions.toArray(Annotation[]::new),
					additions,
					modifications.toArray(Annotation[]::new));
		}
	}

	@Override
	public void initialReconcile() {
		reconcile(null);
	}

	@Override
	public void install(final @Nullable ITextViewer viewer) {
		if (this.viewer != null) {
			this.viewer.removeProjectionListener(this);
		}
		if (viewer instanceof ProjectionViewer projViewer) {
			this.viewer = projViewer;
			this.viewer.addProjectionListener(this);
			this.projectionAnnotationModel = projViewer.getProjectionAnnotationModel();
		}
	}

	/**
	 * Searches the given {@link DirtyRegion} for annotations that now have a length of 0.
	 * This is caused when something that was being folded has been deleted.
	 * These {@link FoldingAnnotation}s are then added to the {@link List} of {@link FoldingAnnotation}s to be deleted
	 *
	 * @param deletions the current list of {@link FoldingAnnotation}s marked for deletion that the newly found invalid
	 *            {@link FoldingAnnotation}s will be added to
	 */
	private void markInvalidAnnotationsForDeletion(final List<FoldingAnnotation> deletions, final List<FoldingAnnotation> existing) {
		final var projectionAnnotationModel = this.projectionAnnotationModel;
		if (projectionAnnotationModel == null)
			return;

		final var it = projectionAnnotationModel.getAnnotationIterator();
		if (it == null)
			return;

		while (it.hasNext()) {
			if (it.next() instanceof FoldingAnnotation anno) {
				Position pos = projectionAnnotationModel.getPosition(anno);
				if (pos.length == 0) {
					deletions.add(anno);
				} else {
					existing.add(anno);
				}
			}
		}
	}

	/**
	 * @param modifications the folding annotations to update.
	 * @param deletions the folding annotations to delete.
	 * @param existing the existing folding annotations.
	 * @param additions annotation to add
	 * @param line the line index
	 * @param endLineNumber the end line number
	 */
	private void updateAnnotation(
			final List<FoldingAnnotation> modifications,
			final List<FoldingAnnotation> deletions,
			final List<FoldingAnnotation> existing,
			final Map<FoldingAnnotation, Position> additions,
			final int line,
			final int endLineNumber,
			final boolean collapsedByDefault) throws BadLocationException {
		final var document = this.document;
		if (document == null)
			return;

		final int startOffset = document.getLineOffset(line);
		final int endOffset = document.getLineOffset(endLineNumber) + document.getLineLength(endLineNumber);
		final var newPos = new Position(startOffset, endOffset - startOffset);
		if (!existing.isEmpty()) {
			final FoldingAnnotation existingAnnotation = existing.remove(existing.size() - 1);

			// if a new position can be calculated then update the position of the annotation,
			// else the annotation needs to be deleted
			if (newPos.length > 0 && projectionAnnotationModel != null) {
				final Position oldPos = projectionAnnotationModel.getPosition(existingAnnotation);
				// only update the position if we have to
				if (!newPos.equals(oldPos)) {
					oldPos.setOffset(newPos.offset);
					oldPos.setLength(newPos.length);
					modifications.add(existingAnnotation);
				}
			} else {
				deletions.add(existingAnnotation);
			}
		} else {
			additions.put(new FoldingAnnotation(collapsedByDefault), newPos);
		}
	}

	@Override
	public void projectionDisabled() {
		projectionAnnotationModel = null;
	}

	@Override
	public void projectionEnabled() {
		if (viewer != null) {
			projectionAnnotationModel = viewer.getProjectionAnnotationModel();
		}
	}

	@Override
	public void reconcile(final @Nullable DirtyRegion dirtyRegion, final @Nullable IRegion subRegion) {
		reconcile(subRegion);
	}

	@Override
	public final void reconcile(final @Nullable IRegion subRegion) {
		final IDocument document = this.document;
		if (projectionAnnotationModel == null || document == null) {
			return;
		}

		doReconcile(subRegion);
	}

	protected abstract void doReconcile(final @Nullable IRegion subRegion);

	@Override
	public void setDocument(final @Nullable IDocument document) {
		this.document = document;
	}

	@Override
	public void setProgressMonitor(final @Nullable IProgressMonitor monitor) {
		// Do nothing
	}

	@Override
	public void uninstall() {
		setDocument(null);
		if (viewer != null) {
			viewer.removeProjectionListener(this);
			viewer = null;
		}
		projectionDisabled();
	}
}
