/*******************************************************************************
 * Copyright (c) 2000, 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * - IBM Corporation - initial API and implementation
 * - Nicolaj Hoess <nicohoess@gmail.com> - Editor templates pref page: Allow to sort by column - https://bugs.eclipse.org/203722
 * - Angelo Zerr <angelo.zerr@gmail.com> - Adapt org.eclipse.ui.texteditor.templates.TemplatePreferencePage for TextMate grammar
 * - Sebastian Thomschke (Vegard IT) - major cleanup/refactoring, added table filtering and performDefaults support
 *******************************************************************************/
package org.eclipse.tm4e.ui.internal.preferences;

import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.lateNonNull;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.IGrammarDefinition;
import org.eclipse.tm4e.registry.IGrammarRegistryManager;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.TMUIMessages;
import org.eclipse.tm4e.ui.internal.themes.ThemeManager;
import org.eclipse.tm4e.ui.internal.widgets.GrammarInfoWidget;
import org.eclipse.tm4e.ui.internal.widgets.TMViewer;
import org.eclipse.tm4e.ui.internal.widgets.TableWidget;
import org.eclipse.tm4e.ui.internal.widgets.TableWithControlsWidget;
import org.eclipse.tm4e.ui.internal.widgets.ThemeAssociationsWidget;
import org.eclipse.tm4e.ui.internal.widgets.VerticalSplitPane;
import org.eclipse.tm4e.ui.internal.wizards.TextMateGrammarImportWizard;
import org.eclipse.tm4e.ui.samples.ISample;
import org.eclipse.tm4e.ui.themes.ITheme;
import org.eclipse.tm4e.ui.themes.IThemeAssociation;
import org.eclipse.tm4e.ui.themes.IThemeManager;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.osgi.service.prefs.BackingStoreException;

/**
 * Manages imported TextMate grammars, content-type bindings, and theme associations.
 */
public final class GrammarPreferencePage extends AbstractPreferencePage {

	static final String PAGE_ID = "org.eclipse.tm4e.ui.preferences.GrammarPreferencePage";

	// Managers
	private IGrammarRegistryManager.EditSession grammarManager = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
	private IThemeManager.EditSession themeManager = ThemeManager.getInstance().newEditSession();

	private TableWidget<IGrammarDefinition> grammarsTable = lateNonNull();

	// Grammar info tabs
	private GrammarInfoWidget grammarInfoWidget = lateNonNull();
	private TableWithControlsWidget<IContentType> contentTypesWidget = lateNonNull();
	private ThemeAssociationsWidget themeAssociationsWidget = lateNonNull();
	private TableWithControlsWidget<ITMScope> injectionsWidget = lateNonNull();

	private TMViewer grammarPreview = lateNonNull();

	public GrammarPreferencePage() {
		super(TMUIMessages.GrammarPreferencePage_title, TMUIMessages.GrammarPreferencePage_description);
	}

	@Override
	protected Control createContents(final Composite parent) {
		final var control = new VerticalSplitPane(parent, 1, 1) {

			@Override
			protected void configureUpperArea(final Composite parent) {
				createGrammarsTable(parent);
			}

			@Override
			protected void configureLowerArea(final Composite parent) {
				createGrammarDetailsView(parent);
				createThemePreview(parent);
			}
		}.getControl();

		Dialog.applyDialogFont(control);

		grammarsTable.setInput(grammarManager);

		return control;
	}

	private void createGrammarsTable(final Composite parent) {
		final var tableWithControls = new TableWithControlsWidget<IGrammarDefinition>(parent, null, true) {

			@Override
			protected TableWidget<IGrammarDefinition> createTable(final Composite parent) {
				return new TableWidget<>(parent, false) {

					@Override
					protected void createColumns() {
						createAutoResizeColumn(TMUIMessages.GrammarPreferencePage_column_contentTypes, 1);
						createAutoResizeColumn(TMUIMessages.GrammarPreferencePage_column_scopeName, 2);
						createAutoResizeColumn(TMUIMessages.GrammarPreferencePage_column_source, 0, 1);
					}

					@Override
					protected @Nullable String getColumnText(final IGrammarDefinition def, final int columnIndex) {
						return switch (columnIndex) {
							case 0 -> {
								final var setup = grammarManager.getGrammarContentType(def);
								if (setup != null && Platform.getContentTypeManager().getContentType(setup.id()) == null)
									yield NLS.bind(TMUIMessages.GrammarPreferencePage_pendingType, setup.name());
								final var contentTypes = getContentTypes(def);
								yield contentTypes == null
										? null
										: contentTypes.stream().map(IContentType::getName)
												.distinct()
												.sorted()
												.collect(Collectors.joining(", "));
							}
							case 1 -> def.getScope().getName();
							case 2 -> def.getPluginId() != null ? def.getPluginId() + " > " + def.getPath()
									: new File(def.getPath()).isFile() && new File(def.getPath()).canRead() ? def.getPath()
											: NLS.bind(TMUIMessages.GrammarPreferencePage_sourceUnavailable, def.getPath());
							default -> null;
						};
					}

					@Override
					protected Object[] getElements(final @Nullable Object input) {
						if (input instanceof final IGrammarRegistryManager manager)
							return manager.getDefinitions();
						return super.getElements(input);
					}
				};
			}

			@Override
			protected void createButtons() {
				// Add Grammar
				createButton(TMUIMessages.Button_new, () -> {
					// Open import wizard for TextMate grammar.
					final var wizard = new TextMateGrammarImportWizard(grammarManager, false);
					final var dialog = new WizardDialog(getShell(), wizard);
					if (dialog.open() == Window.OK) {
						// User grammar was saved, refresh the list of grammar and select the created grammar.
						final var created = wizard.getCreatedDefinition();
						table.refresh();
						table.setSelection(true, created);
					}
				});

				// Remove Grammar
				final var removeBtn = createButton(TMUIMessages.Button_remove, () -> {
					final var definition = table.getFirstSelectedElement();
					if (definition != null) {
						grammarManager.unregisterGrammarDefinition(definition);
						table.refresh();
					}
				});
				table.onSelectionChanged(sel -> removeBtn.setEnabled(!sel.isEmpty() && sel.get(0).getPluginId() == null));
			}
		};

		tableWithControls.setLayoutData(GridDataFactory.fillDefaults()
				.align(SWT.FILL, SWT.FILL)
				.grab(true, true)
				.hint(360, convertHeightInCharsToPixels(10))
				.create());

		grammarsTable = tableWithControls.getTable();

		grammarsTable.onSelectionChanged(selectedGrammarDefinitions -> {
			if (!selectedGrammarDefinitions.isEmpty()) {
				selectGrammar(selectedGrammarDefinitions.get(0));
			}
		});
	}

	/**
	 * Create detail grammar content which is filled when a grammar is selected in the grammar list.
	 */
	private void createGrammarDetailsView(final Composite parent) {
		final var folder = new TabFolder(parent, SWT.NONE);

		final var gd = new GridData(GridData.FILL_HORIZONTAL);
		gd.horizontalSpan = 2;
		folder.setLayoutData(gd);

		createGeneralTab(folder);
		createContentTypeTab(folder);
		createInjectionTab(folder);
		createThemeTab(folder);
	}

	/**
	 * Create "General" tab
	 */
	private void createGeneralTab(final TabFolder folder) {
		final var tab = new TabItem(folder, SWT.NONE);
		tab.setText(TMUIMessages.GrammarPreferencePage_tab_general_text);

		final var parent = new Composite(folder, SWT.NONE);
		parent.setLayout(new GridLayout());
		parent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		grammarInfoWidget = new GrammarInfoWidget(parent, SWT.NONE);
		grammarInfoWidget.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		tab.setControl(parent);
	}

	/**
	 * Create "Content type" tab
	 */
	private void createContentTypeTab(final TabFolder folder) {
		final var tab = new TabItem(folder, SWT.NONE);
		tab.setText(TMUIMessages.GrammarPreferencePage_tab_contentType_text);

		final var parent = new Composite(folder, SWT.NONE);
		parent.setLayout(new GridLayout());
		parent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		contentTypesWidget = new TableWithControlsWidget<>(parent, TMUIMessages.ContentTypesBindingWidget_description, false) {

			@Override
			protected TableWidget<IContentType> createTable(final Composite parent) {
				return new TableWidget<>(parent, false) {
					{
						getTable().setHeaderVisible(false);
					}

					@Override
					protected void createColumns() {
						createColumn("", 100, 0);
					}

					@Override
					protected @Nullable String getColumnText(final IContentType contentType, final int columnIndex) {
						return switch (columnIndex) {
							case 0 -> {
								final IGrammarDefinition binding = grammarManager.getUserGrammarBinding(contentType);
								yield contentType.getName() + " (" + contentType.getId() + ")"
										+ (binding == null ? ""
												: NLS.bind(TMUIMessages.ContentTypesBindingWidget_userChoice,
														binding.getScope().getName()));
							}
							default -> null;
						};
					}
				};
			}

			@Override
			protected void createButtons() {
				final Button add = createButton(TMUIMessages.Button_new, GrammarPreferencePage.this::addContentTypeBinding);
				add.setEnabled(false);
				grammarsTable
						.onSelectionChanged(selection -> add.setEnabled(!selection.isEmpty() && selection.get(0).getPluginId() == null));
				final Button remove = createButton(TMUIMessages.Button_remove, () -> {
					final IContentType type = table.getFirstSelectedElement();
					final IGrammarDefinition def = grammarsTable.getFirstSelectedElement();
					if (type != null && def != null) {
						grammarManager.setUserGrammarBinding(type, null);
						// refresh(true) keeps the same grammar selected when binding changes reorder rows.
						grammarsTable.refresh(true);
						// An unchanged selection may not fire the listener that reloads the binding list.
						fillContentTypeTab(def);
					}
				});
				remove.setEnabled(false);
				table.onSelectionChanged(selection -> {
					final IGrammarDefinition def = grammarsTable.getFirstSelectedElement();
					final IGrammarDefinition binding = selection.isEmpty() ? null : grammarManager.getUserGrammarBinding(selection.get(0));
					// Only user bindings can be removed here.
					// Plugin bindings are still needed for automatic selection and embedded languages.
					remove.setEnabled(def != null && binding != null && binding.getScope().equals(def.getScope()));
				});
			}
		};
		contentTypesWidget.setLayoutData(new GridData(GridData.FILL_BOTH));
		final var help = new Label(parent, SWT.WRAP);
		help.setText(TMUIMessages.ContentTypesBindingWidget_help);
		help.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).hint(450, SWT.DEFAULT).create());

		tab.setControl(parent);
	}

	private void addContentTypeBinding() {
		final IGrammarDefinition definition = grammarsTable.getFirstSelectedElement();
		if (definition == null || definition.getPluginId() != null)
			return;
		final var dialog = new ElementListSelectionDialog(getShell(), new LabelProvider() {
			@Override
			public String getText(final Object element) {
				final var type = (IContentType) element;
				return type.getName() + " (" + type.getId() + ")";
			}
		});
		dialog.setTitle(TMUIMessages.ContentTypesBindingWidget_add_title);
		dialog.setMessage(TMUIMessages.ContentTypesBindingWidget_add_message);
		dialog.setMultipleSelection(false);
		final var manager = Platform.getContentTypeManager();
		final IContentType textType = manager.getContentType("org.eclipse.core.runtime.text");
		dialog.setElements(Arrays.stream(manager.getAllContentTypes())
				.filter(type -> textType != null && type.isKindOf(textType))
				.sorted(Comparator.comparing(IContentType::getName).thenComparing(IContentType::getId)).toArray());
		if (dialog.open() != Window.OK || !(dialog.getFirstResult() instanceof final IContentType type))
			return;
		final IGrammarDefinition previous = grammarManager.getUserGrammarBinding(type);
		if (previous != null && !previous.getScope().equals(definition.getScope())
				&& !MessageDialog.openConfirm(getShell(), TMUIMessages.ContentTypesBindingWidget_replace_title,
						NLS.bind(TMUIMessages.ContentTypesBindingWidget_replace_message, previous.getScope().getName(), type.getName())))
			return;
		try {
			grammarManager.setUserGrammarBinding(type, definition);
		} catch (final IllegalArgumentException ex) {
			// The grammar is already imported, so this failure means another import has the same scope.
			// Use a translated message that explains how to resolve the conflict.
			MessageDialog.openError(getShell(), TMUIMessages.ContentTypesBindingWidget_add_error_title,
					NLS.bind(TMUIMessages.ContentTypesBindingWidget_add_error_message, definition.getScope().getName()));
			return;
		}
		// refresh(true) keeps the same grammar selected when binding changes reorder rows.
		grammarsTable.refresh(true);
		// An unchanged selection may not fire the listener that reloads the binding list.
		fillContentTypeTab(definition);
	}

	/**
	 * Create "Injection" tab
	 */
	private void createInjectionTab(final TabFolder folder) {
		final var tab = new TabItem(folder, SWT.NONE);
		tab.setText(TMUIMessages.GrammarPreferencePage_tab_injection_text);

		final var parent = new Composite(folder, SWT.NONE);
		parent.setLayout(new GridLayout());
		parent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		injectionsWidget = new TableWithControlsWidget<>(parent, TMUIMessages.GrammarInjectionsWidget_description, false) {

			@Override
			protected TableWidget<ITMScope> createTable(final Composite parent) {
				return new TableWidget<>(parent, false) {
					{
						getTable().setHeaderVisible(false);
					}

					@Override
					protected void createColumns() {
						createColumn("", 100, 0);
					}

					@Override
					protected @Nullable String getColumnText(final ITMScope injection, final int columnIndex) {
						return switch (columnIndex) {
							case 0 -> injection.getQualifiedName();
							default -> null;
						};
					}
				};
			}
		};
		injectionsWidget.setLayoutData(new GridData(GridData.FILL_BOTH));

		tab.setControl(parent);
	}

	/**
	 * Create "Theme" tab
	 */
	private void createThemeTab(final TabFolder folder) {
		final var tab = new TabItem(folder, SWT.NONE);
		tab.setText(TMUIMessages.GrammarPreferencePage_tab_theme_text);

		final var parent = new Composite(folder, SWT.NONE);
		parent.setLayout(new GridLayout());
		parent.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

		themeAssociationsWidget = new ThemeAssociationsWidget(themeManager, parent);
		final var data = new GridData(GridData.FILL_HORIZONTAL);
		data.horizontalSpan = 2;
		themeAssociationsWidget.setLayoutData(data);
		themeAssociationsWidget.getTable().onSelectionChanged(associations -> {
			if (!associations.isEmpty()) {
				setPreviewTheme(associations.get(0).getThemeId());
			}
		});

		tab.setControl(parent);
	}

	private void setPreviewTheme(final String themeId) {
		final ITheme theme = themeManager.getThemeById(themeId);
		if (theme != null) {
			grammarPreview.setTheme(theme);
		}
	}

	private void selectGrammar(final IGrammarDefinition definition) {
		fillGeneralTab(definition);
		fillContentTypeTab(definition);
		fillThemeTab(definition);
		fillInjectionsTab(definition);
		preview(definition, themeAssociationsWidget.getTable().getFirstSelectedElement());
	}

	private void fillGeneralTab(final IGrammarDefinition definition) {
		final IGrammar grammar = grammarManager.getGrammarForScope(definition.getScope());
		grammarInfoWidget.refresh(grammar);
	}

	private void fillContentTypeTab(final IGrammarDefinition definition) {
		contentTypesWidget.getTable().setInput(getContentTypes(definition));
	}

	private @Nullable Collection<IContentType> getContentTypes(final IGrammarDefinition definition) {
		final var contentTypes = grammarManager.getContentTypesForScope(definition.getScope());
		if (contentTypes == null || definition.getPluginId() != null)
			return contentTypes;

		// Several import rows can share a scope. Show a user binding only on the source file that lookup actually selects.
		return contentTypes.stream().filter(type -> {
			final var binding = grammarManager.getUserGrammarBinding(type);
			return binding != null && binding.getURI().equals(definition.getURI());
		}).toList();
	}

	private void fillInjectionsTab(final IGrammarDefinition definition) {
		final var contributedInjections = grammarManager.getInjections(definition.getScope());
		if (contributedInjections != null) {
			injectionsWidget.getTable().setInput(contributedInjections.stream().map(ITMScope::parse).toArray());
		} else {
			injectionsWidget.getTable().setInput(List.of());
		}
	}

	private void fillThemeTab(final IGrammarDefinition definition) {
		final TableWidget<IThemeAssociation> themeAssociationsTable = themeAssociationsWidget.getTable();
		final IThemeAssociation selectedAssociation = themeAssociationsTable.getFirstSelectedElement();

		themeAssociationsWidget.setGrammarDefinition(definition);

		if (selectedAssociation == null) {
			// ensure that by default a TM theme is selected that matches the Eclipse theme's dark/light mode
			final boolean isDarkEclipse = themeManager.getDefaultTheme().isDark();
			themeAssociationsTable.setSelection(association -> association.isWhenDark() == isDarkEclipse);
		} else {
			// restore theme selection
			themeAssociationsTable.setSelection(association -> association.getThemeId().equals(selectedAssociation.getThemeId()));
		}
	}

	private void createThemePreview(final Composite parent) {
		final var label = new Label(parent, SWT.NONE);
		label.setText(TMUIMessages.GrammarPreferencePage_preview);
		final var data = new GridData();
		data.horizontalSpan = 2;
		label.setLayoutData(data);

		grammarPreview = new TMViewer(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);

		// Don't set caret to 'null' as this causes https://bugs.eclipse.org/293263
		// viewer.getTextWidget().setCaret(null);

		final var control = grammarPreview.getControl();
		control.setLayoutData(GridDataFactory.fillDefaults()
				.align(SWT.FILL, SWT.FILL)
				.grab(true, true)
				.hint(SWT.DEFAULT, convertHeightInCharsToPixels(5))
				.create());
	}

	private void preview(final IGrammarDefinition definition, final @Nullable IThemeAssociation selectedAssociation) {
		// Preview the grammar
		final IGrammar grammar = grammarManager.getGrammarForScope(definition.getScope());
		if (selectedAssociation != null) {
			setPreviewTheme(selectedAssociation.getThemeId());
		}
		grammarPreview.setGrammar(grammar);
		final ISample[] samples = TMUIPlugin.getSampleManager().getSamples(definition.getScope().getName());
		if (samples.length == 0) {
			grammarPreview.setText("");
		} else {
			// TODO: manage list of sample for the given scope.
			grammarPreview.setText(samples[0].getContent());
		}
	}

	@Override
	protected void performDefaults() {
		grammarManager = TMEclipseRegistryPlugin.getGrammarRegistryManager().newEditSession();
		themeManager = ThemeManager.getInstance().newEditSession();
		grammarsTable.setInput(grammarManager);
	}

	@Override
	public boolean performOk() {
		try {
			grammarManager.save();
			themeManager.save();
		} catch (final BackingStoreException ex) {
			TMUIPlugin.logError(ex);
			// Returning false keeps the dialog open; JFace relies on the page to explain the failed save.
			setErrorMessage(ex.getMessage());
			return false;
		}
		// Apply can retry on the same page, without recreating its error state.
		setErrorMessage(null);
		// Imports create content types on save, so the labels and copied binding list are now stale.
		// refresh(true) preserves the selected grammar; refresh() restores the old row index.
		grammarsTable.refresh(true);
		final var selectedGrammar = grammarsTable.getFirstSelectedElement();
		if (selectedGrammar != null)
			fillContentTypeTab(selectedGrammar);
		return super.performOk();
	}
}
