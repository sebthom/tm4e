# TM4E User Guide

This guide is for Eclipse users who work with editors powered by TM4E. It explains what TM4E does in the UI, how to install and configure it, and how to troubleshoot common problems.


## Table of contents

1. [Getting TM4E in Eclipse](#getting-tm4e-in-eclipse)
1. [What TM4E Provides in the UI](#what-tm4e-provides-in-the-ui)
1. [Language Pack and Additional Grammars](#language-pack-and-additional-grammars)
1. [Configuring TM4E](#configuring-tm4e)
1. [Using Custom Grammars, Language Configurations, and Themes](#using-custom-grammars-language-configurations-and-themes)
1. [Troubleshooting for End Users](#troubleshooting-for-end-users)
1. [Further Reading](#further-reading)


## Getting TM4E in Eclipse

In most Eclipse installations TM4E is installed automatically as a dependency of language tooling (for example, Wild Web Developer or other language-specific features).
You usually do not need to install it explicitly, but you can install it from the TM4E release update site if necessary.

To check whether TM4E is present, choose `Window > Preferences` from Eclipse's main menu.
In the preferences dialog, look for the `TextMate` pages, such as `Grammar`, `Themes`, and `Language Configuration`.
If these pages exist, TM4E is installed and contributing to the IDE.


### Stable and snapshot builds

TM4E offers both **stable** and **snapshot** builds.
Stable builds are published on the [releases update site](https://download.eclipse.org/tm4e/releases/latest/) and are recommended for daily use.
Snapshot builds are published on the [snapshots update site](https://download.eclipse.org/tm4e/snapshots/) and are intended for testing upcoming changes.


## What TM4E Provides in the UI

TM4E brings [TextMate](https://en.wikipedia.org/wiki/TextMate#Language_grammars)-based syntax highlighting and a set of language-configuration driven editor features to Eclipse.

### 1) Syntax highlighting

For supported file types, TM4E uses TextMate grammars (files that describe how to tokenize a language) to identify tokens and scopes and then applies colors using the active TextMate theme.
This works in both the [Generic Editor](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/editors_genericeditor.htm) and in custom editors that are wired to TM4E.

Some editors also combine TM4E with language server features; in that case a few behaviors (such as folding or code actions) may come from either TM4E or the language server, depending on configuration.

![TypeScript Editor Screenshot](img/typescript_editor.png)

### 2) Language-configuration based editor behavior

Language-configuration files enable additional editor behavior for a language. These behaviors include:

- auto-closing brackets
- matching bracket highlighting\
  ![Bracket Matching](img/bracket_matching.png)
- on-enter indentation and comment continuation
- toggling line and block comments
- folding (based on indentation and optional folding markers) in editors that use TM4E's folding support

These behaviors are applied on top of whatever the underlying editor already provides.
TM4E can add these behaviors to simple text editors, or refine them when an editor or language server already offers partial support.

### 3) Custom code templates and code proposals

TM4E offers support for defining and proposing custom code templates for the languages available through a TextMate grammar.
To configure templates, open `Window > Preferences > TextMate > Templates` in Eclipse.

Each template is registered to a context type, i.e. a language or grammar (technically a TextMate scope).
In addition to available grammars, TM4E offers two special context types for comments.
Templates registered for these context types can be used in all languages having such comments in their grammar.

For example, users can register custom C/C++ or JavaScript statements, but also generally usable comment texts like a Copyright notice, a license, or a TODO comment with the user's name.
Having a TM4E-based editor open, they'll get that code snippets suggested via code completion triggered by Ctrl + Space.

The two generic context types for comments are:

- Comment (any comment that is not a documentation comment, i.e. line comments and standard block comments like code between `/*` and `*/` in Java / C++)
- Documentation comment (a comment that is used for code documentation, e.g. javadoc comments in Java, i.e. code between `/**` and `*/`)

![Custom Code Proposal](img/code_template_proposal.png)

### 4) Diagnostic tools (token hover)

For advanced users, some editors expose a TextMate token hover that shows the token scopes and partition information at the caret location.

- This is mainly a diagnostics tool for plugin and grammar developers.
- It can also help you confirm why a particular region is colored or behaves in a certain way.

![TextMate Token Hover Preference](img/tm_token_hover_preference.png)

![TextMate Token Hover](img/tm_token_hover.png)

For details on how to use the token hover and other diagnostics when developing plugins, see the [TM4E Adopter Guide](adopter-guide.md).


## Language Pack and Additional Grammars

The [TM4E Language Pack](https://github.com/eclipse-tm4e/tm4e/blob/main/org.eclipse.tm4e.language_pack/README.md) feature bundles 50+ TextMate grammars and language configurations so that many common languages work out of the box once TM4E is installed.
Individual Eclipse tools may also ship their own grammars that plug into TM4E.

If you need additional grammars beyond what TM4E and its language pack provide, you can install third-party plugins that contribute more grammars or themes.
One example is the [Extra Syntax Highlighting](https://marketplace.eclipse.org/content/extra-syntax-highlighting) plugin, which offers many additional TextMate grammars.
After installing such a plugin you manage the new grammars and themes through the same TextMate preference pages described below.


## Configuring TM4E

Choose `Window > Preferences` from Eclipse's main menu to open the preferences dialog.
Expand `TextMate` to find the following pages:

1. `Window > Preferences > TextMate` provides an overview and general switches.
1. `Window > Preferences > TextMate > Grammar` lets you import and remove grammars
   and assign them to Eclipse content types (the way Eclipse classifies files).
   For each grammar, you can also view its details and injections, choose a theme, and preview the result.\
   ![Grammar Preferences](img/grammar_preferences.png)

1. `Window > Preferences > TextMate > Language Configuration` lets you configure editing features
   and add language-configuration files:
   - You can enable or disable auto closing brackets, matching bracket highlighting, and on-enter actions individually.
   - You can associate extra `language-configuration.json` files from the workspace or file system with specific content types.

   TM4E consumes VS Code-style [language-configuration.json](https://code.visualstudio.com/api/language-extensions/language-configuration-guide) files.

   ![Language Configuration Preferences](img/langcfg_preferences.png)

1. `Window > Preferences > TextMate > Task Tags` lets you define tags in comments (such as `TODO` or `FIXME`)
   that should be treated as tasks or problems, and configure how they are marked in the workspace.\
   ![Task Tags Preferences](img/task_tags_preferences.png)

1. `Window > Preferences > TextMate > Templates` lets you specify custom code templates for available TextMate grammars (languages).
   These will be used in code proposals triggered by Ctrl + Space.\
   ![Templates Preferences](img/templates_preferences.png)

1. `Window > Preferences > TextMate > Themes` lets you choose a built-in theme or one provided by a plugin.
   You can also import theme files and set the default theme for light and dark modes.
   To switch themes from an editor, use its context menu:
   `Language and Theme > Theme for This Language`.

   ![Themes Preferences](img/themes_preferences.png)


## Using Custom Grammars, Language Configurations, and Themes

You can add grammars for syntax highlighting, language configurations for editing rules, and themes for colors.
Open Eclipse's preferences dialog by choosing `Window > Preferences` from the main menu.

Choose whether to set a workspace default or select a language for one file:

| Choice | Affects | When it takes effect |
| --- | --- | --- |
| Workspace default | Matching files without their own language choice | After reopening the editors |
| Language for one file | One workspace file and all its open editors | Immediately |

A file's saved language choice takes priority over the workspace default.

### Using a custom grammar as the workspace default

Eclipse uses content types to classify files.
An association between an imported grammar and a content type is called a **binding**.

1. In Eclipse, open `Window > Preferences > TextMate > Grammar`.
2. Import a grammar file from disk, then select it in the grammar list.
3. Open its `Content type` tab and use `Add...` to choose a content type.
   Repeat this step if the grammar should apply to more content types.
4. Apply the preferences, then reopen affected editors to use the new grammar and editing rules.

Each content type can have one selected grammar.
For matching files without their own language choice, this grammar overrides the grammar provided by a plugin.

**To restore automatic selection:** Remove the binding, apply the preferences, and reopen the affected editors.

#### Example: use Mumps for `.m` files

The TM4E Language Pack associates `.m` files with Objective-C.
Mumps uses the same extension, so Mumps files may receive Objective-C highlighting.
The steps below show how to import a Mumps grammar and use it as the workspace default for `.m` files.

1. In Eclipse, open `Window > Preferences > General > Content Types`.
   Create a Mumps content type with `Text` as its parent.
   Add `m` as a file extension for this content type.
2. Import the Mumps grammar and bind it to the Mumps content type using the steps above.
3. If you have a Mumps language-configuration file, open `Window > Preferences > TextMate > Language Configuration`
   and associate the file with the same content type.
4. Apply the preferences and reopen affected editors.

This makes Mumps the workspace default for `.m` files, including Objective-C files.
TM4E does not automatically distinguish between the two languages.
Use [a file-specific language choice](#choosing-a-language-for-one-file) for files that need a different language.

### Choosing a language for one file

Use a file-specific choice when files with the same extension need different languages.
For example, `routine.m` can use Mumps while `AppDelegate.m` uses Objective-C.

Eclipse must recognize the file as text to load TM4E's editor features.
For an unknown extension, open `Window > Preferences > General > Content Types` in Eclipse.
Associate the extension with a content type based on `Text`.

1. Open the file in an editor that uses TM4E, such as the Generic Editor.
2. Right-click in the editor and select `Language and Theme > Choose Language...`.
3. Choose the language and click `OK`.
   The list includes imported grammars and grammars provided by plugins.

After you choose a language:

- **Immediate update:** All open editors for the file use the new language.
  Your unsaved text, selection, and undo history are preserved.
- **Saved choice:** The choice is stored in Eclipse's workspace metadata and survives a restart.
  It does not change the file contents.
  Later changes to workspace grammar bindings do not replace it.
- **Editing rules:** Both syntax highlighting and editing rules follow the selected language.
  Entries marked `syntax highlighting only` have no associated content type for editing rules.
- **Menu label:** The menu shows the saved language name, for example `Choose Language (Mumps)...`.

**To return to the default:** Select `Language and Theme > Reset Language to Default` in the editor's context menu.
This action appears only when the file has a saved choice.
TM4E immediately returns to the workspace binding or the usual automatic selection.
The same fallback applies if the chosen grammar or content type is no longer installed.

### Adding editing rules for a language

Language-configuration files provide rules for features such as brackets, indentation, comments, and folding.
Add one when a plugin does not provide a configuration for the language, or when you want to use your own rules.

1. In Eclipse, open `Window > Preferences > TextMate > Language Configuration`.
2. Associate the language-configuration file with the language's content type.
   If you added a grammar binding, use the same content type for both.
3. Apply the preferences.

TM4E uses the selected language's content type for both highlighting and editing rules.
If that type has no language configuration, TM4E does not use editing rules from another matching language.

### Importing and choosing themes

The `Window > Preferences > TextMate > Themes` page lets you choose built-in Light and Dark themes
or themes provided by plugins.
To import another theme:

1. In Eclipse, open `Window > Preferences > TextMate > Themes`.
2. Click `New...` and choose a TextMate theme file from disk.
3. Optionally, mark it as the default for Light or Dark mode.

To change the theme for the language currently used by the editor, right-click in the editor and select
`Language and Theme > Theme for This Language`.
The choice applies to that language throughout the workspace.
TM4E keeps separate choices for light and dark Eclipse themes.

If you are interested in authoring themes or grammars yourself, see the Plugin Developer Guide for extension point and authoring details.

### Advanced: how workspace bindings are resolved

A binding also applies to content types based on the type you selected.
When several bindings match, TM4E:

1. Checks the matching content types in Eclipse's order.
2. For each type, checks its own binding first, then its parent types, starting with the direct parent.
3. Uses the first binding whose grammar can be loaded.

**Imported grammars with the same scope:** If several imported files have the same value in the `Scope` column,
TM4E uses the first import.
To use another file, remove the earlier imports with that scope before adding its binding.
Different content types cannot select different imported files with the same scope.

## Troubleshooting for End Users

- **Syntax highlighting does not appear**:
  First check that TM4E is installed (the TextMate preference pages should exist).
  In Eclipse, open `Window > Preferences > TextMate > Grammar`.
  Check that a grammar is associated with the file's content type and that it is enabled.

- **Bracket behavior or on-enter actions do not work**:
  In Eclipse, open `Window > Preferences > TextMate > Language Configuration`.
  Check that the corresponding features are enabled.
  It is also useful to check whether another editor or language server is overriding the behavior for that file type.

- **Editor does not appear to use TM4E**:
  Try opening the same file with the Generic Editor.
  If the Generic Editor shows TM4E-based highlighting while a custom editor does not, the custom editor may not be wired to TM4E or may rely solely on its own partitioning or language server integration.

- **Collecting diagnostics for bug reports**:
  To capture text events and generate a Java test skeleton, add `org.eclipse.tm4e.ui/debug/log/GenerateTest=true` to a debug options file (usually named `debug.options`) and start Eclipse with `-debug /path/to/debug.options`; the test skeleton is written to the error output stream when you close an editor that uses TM4E.
  If you need TM4E to fail fast when no grammar is found for a document (for example when isolating a bug), you can additionally set `org.eclipse.tm4e.ui/debug/log/ThrowError=true`, which causes an exception to be thrown instead of silently disabling TM4E for that document.


## Further Reading

For additional background on what TM4E provides in Eclipse and how it fits into the IDE, see the Eclipse newsletter article "TM4E" (June 2018): https://www.eclipse.org/community/eclipse_newsletter/2018/june/tm4e.php

If you plan to implement your own TM4E-based editor or customize integration at the plugin level, switch to the [TM4E Adopter Guide](adopter-guide.md), which is targeted specifically at plugin adopters and plug-in authors.
