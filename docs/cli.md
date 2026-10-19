# Command-line interface

The `aisolate` CLI turns a project into a running airlock: it creates the project from a template, signs you in,
builds the project and deploys it into its space. It runs on macOS and Windows. [Develop an airlock](develop.md)
covers the project itself: its code, rules and `aisolate.yaml`.

## Install

### macOS

Open `aisolate-cli-installer.pkg`. It installs `/usr/local/bin/aisolate`, which is on the default `PATH`.

The package is not signed yet: if macOS refuses to open it, open it from Finder with Control-click, **Open**.

### Windows

Run `aisolate-cli-installer.exe`. It installs for your user only, without administrator rights:

- `aisolate.exe` into `%LOCALAPPDATA%\Programs\AIsolateCli` by default;
- that folder at the end of your user `Path`, unless you clear the component **Add AIsolate CLI to user PATH**;
- an uninstaller, listed under **Apps** as **AIsolate CLI**, which also removes the `Path` entry.

The installer is not signed yet, so SmartScreen may warn about it: select **More info**, then **Run anyway**.
Open a new terminal after the installation so that it reads the new `Path`.

### Check the installation

```bash
aisolate --version
```

## Before the first build

A project is Python by default and builds with [uv](https://docs.astral.sh/uv/), which brings Python 3.13 and
the project's dependencies. On macOS, `python3.13` on the `PATH` also works. On Windows, install uv: a Python
installation alone is not found. A Java project (`--language=java`) builds with its own Gradle wrapper, which
provisions JDK 25.

## Commands

| Command | What it does |
|---|---|
| `aisolate create -s=<space> -t=<template>` | Creates a project from a template, offline ([Create a project](develop.md#create-a-project)). |
| `aisolate login` | Signs you in to the project's AIsolate in your browser. |
| `aisolate logout` | Signs you out of the project's AIsolate. |
| `aisolate build` | Builds the project: a Python project's zip, or a Java project's jar. |
| `aisolate deploy` | Signs you in when there is no session, builds, and deploys the project as its airlock. |

`aisolate <command> --help` lists each command's options. The commands work the same in a macOS terminal,
Windows Terminal, PowerShell and the Command Prompt.

## Where the CLI keeps your session

`aisolate login` keeps one session per AIsolate endpoint, never a password:

| System | File |
|---|---|
| macOS | `~/.aisolate/session.json`, readable by you only |
| Windows | `%USERPROFILE%\.aisolate\session.json`, in your user profile |

`AISOLATE_HOME` moves the folder.

## Terminal output

On a terminal that shows color, the CLI marks each line with a colored symbol, and the sign-in choice is a link.
Without color, the sign-in URL follows the choice on its own line, to copy. A Windows console that does not use
UTF-8 shows ASCII symbols (`*`, `+`, `x`, `>`) instead of the Unicode ones.

## Build the CLI yourself

The installers are native images built with GraalVM 25 (`native-image`). A native image is built on the system
it runs on: build the Windows one on Windows.

```bash
# macOS: apps/cli/build/aisolate-cli-installer.pkg
GRAALVM_HOME=<GraalVM 25 home> ./gradlew :apps:cli:createMacosInstaller
```

```powershell
# Windows, with the Visual Studio Build Tools (C++): apps\cli\build\aisolate.exe
$env:GRAALVM_HOME = "<GraalVM 25 home>"
.\gradlew.bat :apps:cli:renameRunnerWindows
```

The Windows installer then packages that exe with NSIS, on any system with `makensis` on the `PATH`:

```bash
# apps/cli/build/aisolate-cli-installer.exe
./gradlew :apps:cli:createWindowsInstaller -PwindowsExe=<path to aisolate.exe>
```

On Windows with NSIS, `.\gradlew.bat :apps:cli:createWindowsInstaller` does both steps.

Without GraalVM, `./gradlew :apps:cli:installDist` (`.\gradlew.bat` on Windows) builds a CLI that runs on JDK 25:
put `apps/cli/build/install/aisolate/bin` on your `PATH`.

The CI builds no native image. Its job `cli-windows-installer`, run by hand, packages an exe built on a Windows
machine: set `WINDOWS_EXE_URL`, where the runner can download it, and `WINDOWS_EXE_SHA256`. The installer is the
job's artifact. The CLI bundles the SDKs of the checkout it was built from; rebuild it after pulling.
