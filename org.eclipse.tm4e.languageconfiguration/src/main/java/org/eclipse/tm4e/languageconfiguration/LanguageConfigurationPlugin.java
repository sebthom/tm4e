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
package org.eclipse.tm4e.languageconfiguration;

import java.util.function.Consumer;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.tm4e.ui.internal.utils.FileLanguageSelection;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.eclipse.ui.services.IEvaluationService;
import org.osgi.framework.BundleContext;

/**
 * OSGi Activator for Language Configuration (VSCode language-configuration.json) Eclipse bundle.
 * Refreshes language-dependent command enablement when a file's language changes.
 */
public final class LanguageConfigurationPlugin extends AbstractUIPlugin {

	/** The plug-in ID */
	public static final String PLUGIN_ID = "org.eclipse.tm4e.languageconfiguration"; //$NON-NLS-1$

	/** The shared instance */
	private static volatile @Nullable LanguageConfigurationPlugin plugin;

	private final Consumer<IDocument> languageChangeListener = document -> {
		if (PlatformUI.isWorkbenchRunning()) {
			final var evaluationService = PlatformUI.getWorkbench().getService(IEvaluationService.class);
			if (evaluationService != null) {
				// Command enablement is cached separately from the editor's language configuration.
				evaluationService.requestEvaluation(PLUGIN_ID + ".hasLanguageConfiguration");
			}
		}
	};

	/**
	 * Returns the shared instance
	 *
	 * @return the shared instance
	 */
	public static @Nullable LanguageConfigurationPlugin getDefault() {
		return plugin;
	}

	public static void log(final IStatus status) {
		final var p = plugin;
		if (p != null) {
			p.getLog().log(status);
		} else {
			System.out.println(status);
		}
	}

	public static void logError(final Throwable ex) {
		log(new Status(IStatus.ERROR, PLUGIN_ID, null, ex));
	}

	public static void logError(final String message, final @Nullable Throwable ex) {
		log(new Status(IStatus.ERROR, PLUGIN_ID, message, ex));
	}

	@Override
	public void start(final BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
		FileLanguageSelection.addChangeListener(languageChangeListener);
	}

	@Override
	public void stop(final BundleContext context) throws Exception {
		FileLanguageSelection.removeChangeListener(languageChangeListener);
		plugin = null;
		super.stop(context);
	}
}
