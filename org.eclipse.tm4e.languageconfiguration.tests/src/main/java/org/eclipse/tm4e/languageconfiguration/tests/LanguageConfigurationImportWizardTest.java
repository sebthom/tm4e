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
import static org.eclipse.tm4e.core.internal.utils.NullSafetyHelper.castNonNull;

import java.nio.file.Files;
import java.util.UUID;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.tm4e.languageconfiguration.LanguageConfigurationPlugin;
import org.eclipse.tm4e.languageconfiguration.internal.registry.ILanguageConfigurationRegistryManager;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationDefinition;
import org.eclipse.tm4e.languageconfiguration.internal.registry.LanguageConfigurationRegistryManager;
import org.eclipse.tm4e.registry.GrammarDefinition;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies that configuration import warnings describe the selected target independently of workspace grammar bindings.
 */
class LanguageConfigurationImportWizardTest {

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void replacementWarningChecksTheSelectedType(final boolean configurationOnChild) throws Exception {
		final var typeManager = Platform.getContentTypeManager();
		final var parent = typeManager.addContentType("org.eclipse.tm4e.tests.parent." + UUID.randomUUID(), "Parent test",
				typeManager.getContentType("org.eclipse.core.runtime.text"));
		final var child = typeManager.addContentType("org.eclipse.tm4e.tests.child." + UUID.randomUUID(), "Child test", parent);
		final var grammarFile = Files.createTempFile("configuration-warning-", ".tmLanguage.json");
		final var configurationFile = Files.createTempFile("configuration-warning-", ".language-configuration.json");
		final var grammar = new GrammarDefinition("source.warning-" + UUID.randomUUID(), grammarFile.toString());
		final var grammarRegistry = TMEclipseRegistryPlugin.getGrammarRegistryManager();
		try {
			Files.writeString(grammarFile, "{\"scopeName\":\"" + grammar.getScope().getName() + "\",\"patterns\":[]}");
			Files.writeString(configurationFile, "{\"brackets\":[[\"(\",\")\"]]}");
			final var grammarSession = grammarRegistry.newEditSession();
			grammarSession.registerGrammarDefinition(grammar);
			grammarSession.setUserGrammarBinding(parent, grammar);
			grammarSession.save();
			assertThat(grammarRegistry.getEffectiveContentTypes(child)).containsExactly(parent);

			final var configurations = LanguageConfigurationRegistryManager.getInstance().newEditSession();
			// Opposite parent/child states cover both a missed replacement warning and a false warning about the parent.
			configurations.registerLanguageConfigurationDefinition(new LanguageConfigurationDefinition(
					configurationOnChild ? child : parent, configurationFile.toString()));
			// Use the real wizard without exporting its implementation package or adding an API only for tests.
			final var wizard = (Wizard) castNonNull(Platform.getBundle(LanguageConfigurationPlugin.PLUGIN_ID))
					.loadClass("org.eclipse.tm4e.languageconfiguration.internal.wizards.LanguageConfigurationImportWizard")
					.getConstructor(ILanguageConfigurationRegistryManager.EditSession.class, boolean.class)
					.newInstance(configurations, false);
			final var shell = new Shell();
			try {
				wizard.addPages();
				final var page = (WizardPage) wizard.getPages()[0];
				page.createControl(shell);
				final var fileInput = page.getClass().getDeclaredField("fileText");
				fileInput.setAccessible(true);
				((Text) fileInput.get(page)).setText(configurationFile.toString());
				final var typeInput = page.getClass().getDeclaredField("contentTypeText");
				typeInput.setAccessible(true);
				((Text) typeInput.get(page)).setText(child.getId());
				assertThat(page.getErrorMessage()).isNull();
				assertThat(page.getMessageType()).isEqualTo(configurationOnChild ? IMessageProvider.WARNING : IMessageProvider.NONE);
				// Replacing a configuration is allowed; the warning must not prevent importing for the exact selected type.
				assertThat(page.isPageComplete()).isTrue();
				assertThat(wizard.performFinish()).isTrue();
				assertThat(configurations.getDefinitions()).filteredOn(definition -> definition.getContentType().equals(child))
						.hasSize(1);
			} finally {
				wizard.dispose();
				shell.dispose();
			}
		} finally {
			final var cleanup = grammarRegistry.newEditSession();
			cleanup.unregisterGrammarDefinition(grammar);
			cleanup.save();
			typeManager.removeContentType(child.getId());
			typeManager.removeContentType(parent.getId());
			Files.deleteIfExists(configurationFile);
			Files.deleteIfExists(grammarFile);
		}
	}
}
