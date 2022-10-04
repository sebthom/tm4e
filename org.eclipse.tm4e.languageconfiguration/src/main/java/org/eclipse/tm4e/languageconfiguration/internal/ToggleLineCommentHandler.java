/**
 * Copyright (c) 2018, 2022 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Lucas Bullen (Red Hat Inc.) - initial API and implementation
 */
package org.eclipse.tm4e.languageconfiguration.internal;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IMultiTextSelection;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITypedRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.TypedRegion;
import org.eclipse.tm4e.languageconfiguration.internal.model.CharacterPair;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.languageconfiguration.internal.supports.CommentSupport;
import org.eclipse.tm4e.languageconfiguration.internal.utils.TextUtils;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeHelper;
import org.eclipse.tm4e.ui.internal.utils.ContentTypeInfo;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

public class ToggleLineCommentHandler extends AbstractHandler {

	public static final String TOGGLE_LINE_COMMENT_COMMAND_ID = "org.eclipse.tm4e.languageconfiguration.toggleLineCommentCommand";
	public static final String ADD_BLOCK_COMMENT_COMMAND_ID = "org.eclipse.tm4e.languageconfiguration.addBlockCommentCommand";
	public static final String REMOVE_BLOCK_COMMENT_COMMAND_ID = "org.eclipse.tm4e.languageconfiguration.removeBlockCommentCommand";

	@Nullable
	private static <T> T adapt(@Nullable final Object sourceObject, final Class<T> adapter) {
		return Adapters.adapt(sourceObject, adapter);
	}

	@Nullable
	@Override
	public Object execute(@Nullable final ExecutionEvent event) throws ExecutionException {
		if (event == null) {
			return null;
		}
		final var part = HandlerUtil.getActiveEditor(event);
		final var editor = adapt(part, ITextEditor.class);
		if (editor == null) {
			return null;
		}
		final var selection = editor.getSelectionProvider().getSelection();
		if (selection instanceof ITextSelection textSelection) {
			final var input = editor.getEditorInput();
			final var docProvider = editor.getDocumentProvider();
			if (docProvider == null || input == null) {
				return null;
			}

			final var document = docProvider.getDocument(input);
			if (document == null) {
				return null;
			}

			final ContentTypeInfo info;
			try {
				info = ContentTypeHelper.findContentTypes(document);
				if (info == null)
					return null;
			} catch (final CoreException e) {
				return null;
			}
			final var contentTypes = info.getContentTypes();
			final var command = event.getCommand();
			final var commentSupport = getCommentSupport(contentTypes);
			if (commentSupport == null) {
				return null;
			}
			// Check if comment support is valid according the command to do.
			if (!isValid(commentSupport, command)) {
				return null;
			}

			final var target = adapt(editor, IRewriteTarget.class);
			if (target != null) {
				target.beginCompoundChange();
			}
			try {
				switch (command.getId()) {
				case TOGGLE_LINE_COMMENT_COMMAND_ID: {
						final var lineComment = commentSupport.getLineComment();
						if (lineComment != null && !lineComment.isEmpty()) {
							updateLineComment(document, textSelection, lineComment, editor);
						} else {
							final var blockComment = commentSupport.getBlockComment();
							if (blockComment != null) {
								Set<Integer> lines = computeLines(textSelection, document);
								
								// Filter out the blank lines and lines that are outside of the text selection
								final int selectionStartLine = textSelection.getStartLine();
								final int selectionEndLine = textSelection.getEndLine();
								final int lineRange[] = {-1, -1};
								lines = lines.stream().filter(l -> 
											(l >= selectionStartLine && l <= selectionEndLine &&
												!TextUtils.isBlankLine(document, l)))
										.map(l -> {
											lineRange[0] = lineRange[0] == - 1 || lineRange[0] >  l ? l : lineRange[0] ;
											lineRange[1] = lineRange[1] < l ? l : lineRange[1] ;
											return l;
										}).collect(Collectors.toSet());
	
								final int first = lineRange[0];
								final int last = lineRange[1];
								boolean isToAdd = false;
								int shiftOffset = 0;
								int shiftLength = 0;
								for (final int line : lines) {
									final Set<ITypedRegion> existingBlocks = getBlockCommentPartsForLine(document, line, commentSupport);
									if (line == first) {
										isToAdd = existingBlocks.isEmpty();
									}
	
									// Remove existing comments block parts
									int deletedChars = 0;
									for (ITypedRegion existingBlock : existingBlocks) {
										existingBlock = new TypedRegion(existingBlock.getOffset() - deletedChars, existingBlock.getLength(), existingBlock.getType());
										document.replace(existingBlock.getOffset(), existingBlock.getLength(), "");
										deletedChars += existingBlock.getLength();
										
										final int selectionStart = textSelection.getOffset() + shiftOffset;
										final int selectionLength = textSelection.getLength() + shiftLength;
										final int selectionEnd = selectionStart + selectionLength;
										if (isBeforeSelection(existingBlock, selectionStart)) {
											shiftOffset -= existingBlock.getLength();
										} else if (isInsideSelection(existingBlock, selectionStart, selectionEnd)) {
											shiftLength -= existingBlock.getLength();
										} else if (blockComment.open.equals(existingBlock.getType()) && isSelectioinStartOverlaps(existingBlock, selectionStart)) {
											final int diff = selectionStart - existingBlock.getOffset();
											shiftOffset -= diff;
											final int lengthDiff = existingBlock.getLength()- diff;
											shiftLength -= lengthDiff <= selectionLength ? lengthDiff : selectionLength;
										} else if (isSelectioinEndOverlaps(existingBlock, selectionEnd)) {
											final int lengthDiff = selectionEnd - existingBlock.getOffset();
											if (isSelectioinEndOverlaps(existingBlock, selectionStart)) {
												shiftOffset -= lengthDiff;
											}
											shiftLength -= lengthDiff <= selectionLength ? lengthDiff : selectionLength;
										} else if (isSelectionInside(existingBlock, selectionStart, selectionEnd)) {
											shiftLength -= selectionLength;
											shiftOffset -= selectionStart -  existingBlock.getOffset();
										}
									}
								}
	
								// Calculate the updated text selection
								textSelection = new TextSelection(textSelection.getOffset() + shiftOffset , textSelection.getLength() + shiftLength);
								shiftOffset = shiftLength = 0;
					
								// Add new block comments in case we need it
								if (isToAdd) {
									for (final int line : lines) {
										final int lineOffset = document.getLineOffset(line);
										final int lineLength = document.getLineLength(line);
										final String lineDelimiter = document.getLineDelimiter(line);
										final var range = new TextSelection(document, lineOffset,
												lineDelimiter != null ? lineLength - lineDelimiter.length() : lineLength);
	
										addBlockComment(document, range, blockComment, true,  editor);
	
										if (line == first){
											if (range.getOffset() <= textSelection.getOffset()) {
												shiftOffset += blockComment.open.length();
											}
											if (range.getOffset() + range.getLength() 
													< textSelection.getOffset() + textSelection.getLength()) {
												shiftLength += blockComment.close.length();
											}
										} 
										if (line == last && line != first){
											final int thisShiftLength = shiftLength;
											if (range.getOffset() 
													<= textSelection.getOffset() + shiftOffset  + textSelection.getLength() + thisShiftLength) {
												shiftLength += blockComment.open.length();
											}
											if (range.getOffset() + range.getLength() 
													< textSelection.getOffset() + shiftOffset + textSelection.getLength() + thisShiftLength) {
												shiftLength += blockComment.close.length();
											}
										} 
										if (line != first && line != last) {
											shiftLength += blockComment.open.length() + blockComment.close.length();
										}
									}
									
									// Calculate the updated text selection
									textSelection = new TextSelection(textSelection.getOffset() + shiftOffset , textSelection.getLength() + shiftLength);
								}
	
								editor.selectAndReveal(textSelection.getOffset(), textSelection.getLength());
							}
						}
						break;
					}

				case ADD_BLOCK_COMMENT_COMMAND_ID: {
						final var blockComment = commentSupport.getBlockComment();
						if (blockComment != null && !blockComment.open.isEmpty() && !blockComment.close.isEmpty()) {
							final IRegion existingBlock = getBlockComment(document, textSelection, commentSupport);
							if (existingBlock == null) {
								addBlockComment(document, textSelection, blockComment, false, editor);
							}
						}  else {
							// Fallback to using line comment
							final var lineComment = commentSupport.getLineComment();
							if (lineComment != null && !lineComment.isEmpty()) {
								updateLineComment(document, textSelection, lineComment, editor);
							}
						}
						
						break;
					}

				case REMOVE_BLOCK_COMMENT_COMMAND_ID: {
						final var blockComment = commentSupport.getBlockComment();
						if (blockComment != null && !blockComment.open.isEmpty() && !blockComment.close.isEmpty()) {
							final IRegion existingBlock = getBlockComment(document, textSelection, commentSupport);
							if (existingBlock != null) {
								removeBlockComment(document, textSelection, existingBlock, blockComment, editor);
							}
						}  else {
							// Fallback to using line comment
							final var lineComment = commentSupport.getLineComment();
							if (lineComment != null && !lineComment.isEmpty()) {
								updateLineComment(document, textSelection, lineComment, editor);
							}
						}
						break;
					}
				}
			} catch (final BadLocationException e) {
				// Caught by making no changes
			} finally {
				if (target != null) {
					target.endCompoundChange();
				}
			}
		}
		return null;
	}

	private static boolean isBeforeSelection(IRegion region, int selectionStart) {
		final int regionStart = region.getOffset();
		final int regionEnd = regionStart + region.getLength();
		return (regionStart < selectionStart && regionEnd <= selectionStart);
	}
	
	private static boolean isSelectioinStartOverlaps(IRegion region, int selectionStart) {
		final int regionStart = region.getOffset();
		final int regionEnd = regionStart + region.getLength();
		return (selectionStart >= regionStart && selectionStart < regionEnd);
	}

	private static boolean isSelectioinEndOverlaps(IRegion region, int selectionEnd) {
		final int regionStart = region.getOffset();
		final int regionEnd = regionStart + region.getLength();
		return (selectionEnd > regionStart && selectionEnd < regionEnd);
	}
	
	private static boolean isInsideSelection(IRegion region,  int selectionStart, int selectionEnd) {
		final int regionStart = region.getOffset();
		final int regionEnd = regionStart + region.getLength();
		return (selectionStart <= regionStart && selectionEnd >= regionEnd);
	}
	
	private static boolean isSelectionInside(IRegion region, int selectionStart, int selectionEnd) {
		final int regionStart = region.getOffset();
		final int regionEnd = regionStart + region.getLength();
		return (selectionStart >= regionStart && selectionEnd < regionEnd);
	}
	
	private Set<Integer> computeLines(final ITextSelection textSelection, final IDocument document)
			throws BadLocationException {
		final var regions = textSelection instanceof final IMultiTextSelection multiSelection
				? multiSelection.getRegions()
				: new IRegion[] { new Region(textSelection.getOffset(), textSelection.getLength()) };
		final var lines = new HashSet<Integer>();
		for (final var region : regions) {
			final int lineFrom = document.getLineOfOffset(region.getOffset());
			final int lineTo = document.getLineOfOffset(region.getOffset() + region.getLength());
			for (int line = lineFrom; line <= lineTo; line++) {
				lines.add(line);
			}
		}
		return lines;
	}

	/**
	 * Returns true if comment support is valid according the command to do and false otherwise.
	 *
	 * @return true if comment support is valid according the command to do and false otherwise.
	 */
	private boolean isValid(final CommentSupport commentSupport, final Command command) {
		// At least one of line or block comment is to be enabled by the language configuration
		final var lineComment = commentSupport.getLineComment();
		final var blockComment = commentSupport.getBlockComment();
		if ((lineComment == null || lineComment.isEmpty())  
				&& (blockComment == null ||blockComment.open.isEmpty() || blockComment.close.isEmpty())) {
			return false;
		}
		// A command should to be either Toggle Line comment or Add/Remove Block comment
		return (TOGGLE_LINE_COMMENT_COMMAND_ID.equals(command.getId())
				|| ADD_BLOCK_COMMENT_COMMAND_ID.equals(command.getId())
				|| REMOVE_BLOCK_COMMENT_COMMAND_ID.equals(command.getId()));
	}

	/**
	 * Returns the comment support from the given list of content types and null otherwise.
	 *
	 * @return the comment support from the given list of content types and null otherwise.
	 */
	@Nullable
	private CommentSupport getCommentSupport(final IContentType[] contentTypes) {
		final var registry = LanguageConfigurationRegistryManager.getInstance();
		for (final var contentType : contentTypes) {
			if (!registry.shouldComment(contentType)) {
				continue;
			}
			final var commentSupport = registry.getCommentSupport(contentType);
			if (commentSupport != null) {
				return commentSupport;
			}
		}
		return null;
	}

	private void updateLineComment(final IDocument document, final ITextSelection selection, final String comment,
			final ITextEditor editor) throws BadLocationException {
		if (areLinesCommented(document, selection, comment)) {
			removeLineComments(document, selection, comment, editor);
		} else {
			addLineComments(document, selection, comment, editor);
		}
	}

	private boolean areLinesCommented(final IDocument document, final ITextSelection selection, final String comment)
			throws BadLocationException {
		int lineNumber = selection.getStartLine();
		while (lineNumber <= selection.getEndLine()) {
			final var lineRegion = document.getLineInformation(lineNumber);
			if (!document.get(lineRegion.getOffset(), lineRegion.getLength()).trim().startsWith(comment)) {
				return false;
			}
			lineNumber++;
		}
		return true;
	}

	private Set<ITypedRegion> getBlockCommentPartsForLine(final IDocument document, final int line, 
				final CommentSupport commentSupport) throws BadLocationException {
		final var blockComment = commentSupport.getBlockComment();
		if (blockComment == null) {
			return Collections.emptySet();
		}

		TreeSet<ITypedRegion> result = new TreeSet<>((r1, r2) -> r1.getOffset() - r2.getOffset());
		int lineStart = document.getLineOffset(line);
		int lineLength = document.getLineLength(line);
		final String open = blockComment.open;
		final String close = blockComment.close;
		String lineText = document.get(lineStart, lineLength);

		int index = 0;
		while (true ) {
			int indexOpen = lineText.indexOf(open, index);
			int indexClose = lineText.indexOf(close, index);
			
			if (indexOpen != -1 && (indexClose == -1 || indexOpen < indexClose)) {
				result.add(new TypedRegion(lineStart + indexOpen, open.length(), open));
				index = indexOpen + open.length();
			} else if (indexClose != -1) {
				result.add(new TypedRegion(lineStart + indexClose, close.length(), close));
				index = indexClose + close.length();
			} else {
				// No more block comment parts found
				break;
			}
		}
		
		return result;
	}
	
	@Nullable
	private IRegion getBlockComment(final IDocument document, final ITextSelection selection,
			final CommentSupport commentSupport) throws BadLocationException {
		if (selection.getText() == null) {
			return null;
		}
		final var blockComment = commentSupport.getBlockComment();
		if (blockComment == null) {
			return null;
		}
		final String text = document.get();
		final String open = blockComment.open;
		final String close = blockComment.close;
		final int selectionStart = selection.getOffset();
		final int selectionEnd = selectionStart + selection.getLength();
		int openOffset = TextUtils.startIndexOfOffsetTouchingString(text, selectionStart, open);
		if (openOffset == -1) {
			openOffset = text.lastIndexOf(open, selectionStart);
			if (openOffset == -1 || openOffset < document.getLineOffset(selection.getStartLine())) {
				return null;
			}
		}

		int closeOffset = TextUtils.startIndexOfOffsetTouchingString(text, selectionEnd, close);
		if (closeOffset == -1 || closeOffset < openOffset + open.length()) {
			closeOffset = text.indexOf(close, selectionEnd);
			final IRegion endLineRegion = document.getLineInformation(document.getLineOfOffset(selectionEnd));
			if (openOffset == -1 || closeOffset < openOffset + open.length()
					|| closeOffset > endLineRegion.getOffset() + endLineRegion.getLength()) {
				return null;
			}
		}

		// Make sure there isn't a different block closer before the one we found
		int othercloseOffset = text.indexOf(close, openOffset + open.length());
		while (othercloseOffset != -1 && othercloseOffset < closeOffset) {
			final int startOfLineOffset = document.getLineOffset(document.getLineOfOffset(othercloseOffset));
			if (commentSupport.getLineComment() != null && text.substring(startOfLineOffset, othercloseOffset)
					.indexOf(commentSupport.getLineComment()) != -1) {
				return null;
			}
			othercloseOffset = text.indexOf(close, othercloseOffset + close.length());
		}
		return new Region(openOffset, closeOffset - openOffset);
	}

	private void removeLineComments(final IDocument document, final ITextSelection selection, final String comment,
			final ITextEditor editor) throws BadLocationException {
		final String oldText = document.get();
		int deletedChars = 0;
		boolean isStartBeforeComment = false;

		// Filter out the blank lines and lines that are outside of the text selection
		Set<Integer> lines = computeLines(selection, document).stream().filter(l -> 
					(l >= selection.getStartLine() && l <= selection.getEndLine()))
				.collect(Collectors.toSet());

		boolean isFirstLineUpdated = false;
		for (final int lineNumber : lines){
			final int commentOffset = oldText.indexOf(comment, document.getLineOffset(lineNumber) + deletedChars);
			document.replace(commentOffset - deletedChars, comment.length(), "");
			deletedChars += comment.length();
			if (!isFirstLineUpdated) {
				isFirstLineUpdated = true;
				isStartBeforeComment = commentOffset >= selection.getOffset();
			}
		}
		final var newSelection = new TextSelection(
				selection.getOffset() - (isStartBeforeComment ? 0 : comment.length()),
				selection.getLength() - deletedChars + (isStartBeforeComment ? 0 : comment.length()));
		editor.selectAndReveal(newSelection.getOffset(), newSelection.getLength());
	}

	private void addLineComments(final IDocument document, final ITextSelection selection, final String comment,
			final ITextEditor editor) throws BadLocationException {
		int insertedChars = 0;
		
		// Filter out the blank lines and lines that are outside of the text selection
		Set<Integer> lines = computeLines(selection, document).stream().filter(l -> 
					(l >= selection.getStartLine() && l <= selection.getEndLine()))
				.collect(Collectors.toSet());

		boolean isFirstLineUpdated = false;
		for (final int lineNumber : lines) {
			document.replace(document.getLineOffset(lineNumber), 0, comment);
			if (isFirstLineUpdated) {
				insertedChars += comment.length();
			} else {
				isFirstLineUpdated = true;
			}
		}
		final var newSelection = new TextSelection(selection.getOffset() + comment.length(),
				selection.getLength() + insertedChars);
		editor.selectAndReveal(newSelection.getOffset(), newSelection.getLength());
	}

	private  void  removeBlockComment(final IDocument document, final ITextSelection selection,
			final IRegion existingBlock, final CharacterPair blockComment, boolean skipSelection,  final ITextEditor editor)
			throws BadLocationException {
		final int openOffset = existingBlock.getOffset();
		final int openLength = blockComment.open.length();
		final int closeOffset = existingBlock.getOffset() + existingBlock.getLength();
		final int closeLength = blockComment.close.length();
		document.replace(openOffset, openLength, "");
		document.replace(closeOffset - openLength, closeLength, "");

		int offsetFix = openLength;
		int lengthFix = 0;
		if (selection.getOffset() < openOffset + openLength) {
			offsetFix = selection.getOffset() - openOffset;
			lengthFix = openLength - offsetFix;
		}
		if (selection.getOffset() + selection.getLength() > closeOffset) {
			lengthFix += selection.getOffset() + selection.getLength() - closeOffset;
		}
		final var newSelection = new TextSelection(selection.getOffset() - offsetFix,
				selection.getLength() - lengthFix);
		if (!skipSelection) {
			editor.selectAndReveal(newSelection.getOffset(), newSelection.getLength());
		}
	}

	private void addBlockComment(final IDocument document, final ITextSelection selection,
			final CharacterPair blockComment, boolean skipSelection, final ITextEditor editor) throws BadLocationException {
		document.replace(selection.getOffset(), 0, blockComment.open);
		document.replace(selection.getOffset() + selection.getLength() + blockComment.open.length(), 0,
				blockComment.close);
		
		final var newSelection = new TextSelection(selection.getOffset() + blockComment.open.length(),
				selection.getLength());
		if (!skipSelection) {
			editor.selectAndReveal(newSelection.getOffset(), newSelection.getLength());
		}
	}
}