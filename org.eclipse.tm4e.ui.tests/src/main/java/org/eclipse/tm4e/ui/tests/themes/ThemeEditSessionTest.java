/**
 * Copyright (c) 2026 Vegard IT GmbH and others.
 * This program and the accompanying materials are made available under the terms of
 * the Eclipse Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0/
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * - Sebastian Thomschke (Vegard IT) - initial implementation
 */
package org.eclipse.tm4e.ui.tests.themes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.internal.preferences.PreferenceConstants;
import org.eclipse.tm4e.ui.internal.themes.Theme;
import org.eclipse.tm4e.ui.internal.themes.ThemeManager;
import org.eclipse.tm4e.ui.themes.ITheme;
import org.eclipse.tm4e.ui.themes.IThemeManager;
import org.eclipse.tm4e.ui.themes.ThemeAssociation;
import org.eclipse.tm4e.ui.themes.ThemeIdConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.service.prefs.BackingStoreException;

/** Verifies theme save rollback, preference notifications and overlapping sessions without changing the shared theme manager. */
class ThemeEditSessionTest {

	private static final List<String> THEME_KEYS = List.of(PreferenceConstants.THEMES, PreferenceConstants.THEME_ASSOCIATIONS,
			PreferenceConstants.DEFAULT_DARK_THEME, PreferenceConstants.DEFAULT_LIGHT_THEME);

	private final IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(TMUIPlugin.PLUGIN_ID);
	private final Map<String, @Nullable String> savedPreferences = new HashMap<>();
	private final List<Path> files = new ArrayList<>();

	@BeforeEach
	void setUp() {
		for (final var key : THEME_KEYS) {
			savedPreferences.put(key, prefs.get(key, null));
			prefs.remove(key);
		}
	}

	@AfterEach
	void cleanUp() throws Exception {
		for (final var entry : savedPreferences.entrySet()) {
			final var value = entry.getValue();
			if (value == null)
				prefs.remove(entry.getKey());
			else
				prefs.put(entry.getKey(), value);
		}
		prefs.flush();
		for (final var file : files) {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void failedSaveRestoresThemesPreferencesAndNotificationsAndCanBeRetried() throws Exception {
		assertFailedSaveCanBeRetried(true);
	}

	@Test
	void failedSaveRestoresAbsentThemePreferences() throws Exception {
		assertFailedSaveCanBeRetried(false);
	}

	private void assertFailedSaveCanBeRetried(final boolean keepPreviousPreferences) throws Exception {
		final var manager = loadManager();
		final var scope = "source.theme-save-test";
		if (keepPreviousPreferences) {
			final var initial = manager.newEditSession();
			for (final var dark : List.of(false, true)) {
				final var theme = createTheme(dark);
				initial.registerTheme(theme);
				initial.registerThemeAssociation(new ThemeAssociation(theme.getId(), scope, dark));
				initial.setDefaultTheme(theme.getId(), dark);
			}
			initial.save();
		}
		final var previousThemes = manager.getThemes();
		final var previousAssociations = manager.getAllThemeAssociations();
		final var previousSelection = selectedThemes(manager, scope);
		final var previousPreferences = new HashMap<String, @Nullable String>();
		for (final var key : THEME_KEYS) {
			previousPreferences.put(key, prefs.get(key, null));
		}

		final var session = manager.newEditSession();
		for (final var theme : previousThemes) {
			if (theme.getPluginId() == null)
				session.unregisterTheme(theme);
		}
		for (final var association : previousAssociations) {
			if (association.getPluginId() == null)
				session.unregisterThemeAssociation(association);
		}
		for (final var dark : List.of(false, true)) {
			final var theme = createTheme(dark);
			session.registerTheme(theme);
			session.registerThemeAssociation(new ThemeAssociation(theme.getId(), scope, dark));
			session.setDefaultTheme(theme.getId(), dark);
		}
		final var pendingSelection = selectedThemes(session, scope);
		final var observedSelections = new ArrayList<List<String>>();
		// Editors resolve themes from the live manager inside the preference callback, before save() returns.
		final IPreferenceChangeListener listener = event -> {
			if (THEME_KEYS.contains(event.getKey()))
				observedSelections.add(selectedThemes(manager, scope));
		};
		prefs.addPreferenceChangeListener(listener);
		final var markerKey = "tm4e.test.themeSaveFailure." + UUID.randomUUID();
		final var preferenceFile = Platform.getStateLocation(Platform.getBundle("org.eclipse.core.runtime"))
				.append(".settings").append(TMUIPlugin.PLUGIN_ID + ".prefs").toFile().toPath();
		try {
			// Ensure a backing file exists even when all four theme preferences are absent.
			prefs.put(markerKey, "test");
			prefs.flush();
			final var previousContents = Files.readString(preferenceFile);
			// Eclipse replaces an existing preference file through .bak. A directory blocks the write on all platforms.
			final var blocker = Files.createDirectory(preferenceFile.resolveSibling(preferenceFile.getFileName() + ".bak"));
			try {
				assertThatThrownBy(session::save).isInstanceOf(BackingStoreException.class)
						.satisfies(ex -> assertThat(ex.getSuppressed()).singleElement().isInstanceOf(BackingStoreException.class));
				assertThat(manager.getThemes()).containsExactly(previousThemes);
				assertThat(manager.getAllThemeAssociations()).containsExactlyInAnyOrder(previousAssociations);
				assertThat(selectedThemes(manager, scope)).isEqualTo(previousSelection);
				for (final var key : THEME_KEYS) {
					assertThat(prefs.get(key, null)).as(key).isEqualTo(previousPreferences.get(key));
				}
				assertThat(Files.readString(preferenceFile)).isEqualTo(previousContents);
				assertThat(observedSelections).isNotEmpty();
				assertThat(observedSelections.getLast()).isEqualTo(previousSelection);
			} finally {
				Files.delete(blocker);
			}
			// A retry must merge with changes saved after the failure, not publish the session's old snapshot.
			final var otherTheme = createTheme(false);
			final var otherSession = manager.newEditSession();
			otherSession.registerTheme(otherTheme);
			otherSession.save();
			observedSelections.clear();
			session.save();
			assertThat(selectedThemes(manager, scope)).isEqualTo(pendingSelection);
			assertThat(manager.getThemeById(otherTheme.getId())).isSameAs(otherTheme);
			assertThat(observedSelections).isNotEmpty()
					.allSatisfy(selection -> assertThat(selection).isEqualTo(pendingSelection));
			final var reloaded = loadManager();
			assertThat(selectedThemes(reloaded, scope)).isEqualTo(pendingSelection);
			assertThat(reloaded.getThemes()).extracting(ITheme::getId).containsExactlyElementsOf(
					List.of(manager.getThemes()).stream().map(ITheme::getId).toList());
			assertThat(reloaded.getAllThemeAssociations()).containsExactlyInAnyOrder(manager.getAllThemeAssociations());
			observedSelections.clear();
			session.save();
			assertThat(observedSelections).isEmpty();
		} finally {
			prefs.removePreferenceChangeListener(listener);
			prefs.remove(markerKey);
			prefs.flush();
		}
	}

	@Test
	void savedSessionDoesNotOverwriteLaterDefaultThemeChangesWhenReused() throws Exception {
		assertReusedSessionPreservesDefaults(true);
	}

	@Test
	void resetSessionDoesNotOverwriteLaterDefaultThemeChangesWhenReused() throws Exception {
		assertReusedSessionPreservesDefaults(false);
	}

	private void assertReusedSessionPreservesDefaults(final boolean saveFirst) throws Exception {
		final var manager = loadManager();
		final var session = manager.newEditSession();
		session.setDefaultTheme(ThemeIdConstants.Monokai, true);
		session.setDefaultTheme(ThemeIdConstants.EclipseLight, false);
		if (saveFirst)
			session.save();
		else
			session.reset();
		final var otherSession = manager.newEditSession();
		otherSession.setDefaultTheme(ThemeIdConstants.Dark, true);
		otherSession.setDefaultTheme(ThemeIdConstants.Light, false);
		otherSession.save();
		session.registerTheme(createTheme(false));
		session.save();
		assertThat(manager.getDefaultTheme(true).getId()).isEqualTo(ThemeIdConstants.Dark);
		assertThat(manager.getDefaultTheme(false).getId()).isEqualTo(ThemeIdConstants.Light);
		final var reloaded = loadManager();
		assertThat(reloaded.getDefaultTheme(true).getId()).isEqualTo(ThemeIdConstants.Dark);
		assertThat(reloaded.getDefaultTheme(false).getId()).isEqualTo(ThemeIdConstants.Light);
	}

	private static List<String> selectedThemes(final IThemeManager manager, final String scope) {
		return List.of(manager.getDefaultTheme(false).getId(), manager.getDefaultTheme(true).getId(),
				manager.getThemeForScope(scope, false).getId(), manager.getThemeForScope(scope, true).getId());
	}

	private ITheme createTheme(final boolean dark) throws Exception {
		final var id = "tm4e-theme-test-" + UUID.randomUUID();
		final var path = Files.createTempFile(id, ".css");
		files.add(path);
		Files.writeString(path, ".keyword { color: #ff0000; }");
		return new Theme(id, path.toString(), id, dark);
	}

	private static IThemeManager loadManager() throws Exception {
		// Use real contribution/preference loading without replacing the singleton used by other UI tests.
		final var constructor = ThemeManager.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		final var manager = constructor.newInstance();
		final var load = ThemeManager.class.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(manager);
		return manager;
	}
}
