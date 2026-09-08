!include "MUI2.nsh"

!ifndef AISOLATE_EXE_PATH
  !define AISOLATE_EXE_PATH "aisolate.exe"
!endif

!define MUI_COMPONENTSPAGE_NODESC
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\AIsolateCli"

Name "AIsolate CLI"
OutFile "aisolate-cli-installer.exe"
; Per user, no elevation: the PATH entry below is this user's, so the files are too. Elevated
; over the shoulder, an admin install would write the admin's PATH instead.
InstallDir "$LOCALAPPDATA\Programs\AIsolateCli"
RequestExecutionLevel user
Unicode True
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_LICENSE "LICENSE"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "AIsolate CLI" SecMain
    SectionIn RO
    ; No clean-up first: the one exe is overwritten, and the directory is the user's choice.
    SetOutPath "$INSTDIR"
    File "${AISOLATE_EXE_PATH}"
    WriteUninstaller "$INSTDIR\uninstall.exe"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "AIsolate CLI"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "Reportix"
    WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
    WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1
SectionEnd

Section "Add AIsolate CLI to user PATH (recommended)" SecAddPath
    ; PowerShell, not ReadRegStr/WriteRegExpandStr: NSIS strings stop at 1024 characters, so a
    ; longer PATH would read back empty or cut and be written over. SetEnvironmentVariable also
    ; announces the change, so new terminals see it without logging off. The directory goes in
    ; through the environment: an apostrophe in it (C:\Users\O'Brien) would end a quoted string.
    System::Call 'Kernel32::SetEnvironmentVariable(t, t)i ("AISOLATE_DIR", "$INSTDIR").r0'
    nsExec::ExecToLog `powershell -NoProfile -NonInteractive -Command "$$d = $$env:AISOLATE_DIR; $$p = [Environment]::GetEnvironmentVariable('Path', 'User'); if (-not (($$p -split ';') -contains $$d)) { [Environment]::SetEnvironmentVariable('Path', ((@($$p, $$d) | Where-Object { $$_ }) -join ';'), 'User') }"`
    Pop $0
    StrCmp $0 "0" +2
        DetailPrint "Could not add $INSTDIR to your PATH (exit $0); add it by hand."
SectionEnd

Section "Uninstall"
    System::Call 'Kernel32::SetEnvironmentVariable(t, t)i ("AISOLATE_DIR", "$INSTDIR").r0'
    nsExec::ExecToLog `powershell -NoProfile -NonInteractive -Command "$$d = $$env:AISOLATE_DIR; $$p = [Environment]::GetEnvironmentVariable('Path', 'User'); if ($$p) { [Environment]::SetEnvironmentVariable('Path', ((($$p -split ';') | Where-Object { $$_ -and $$_ -ne $$d }) -join ';'), 'User') }"`
    Pop $0
    StrCmp $0 "0" +2
        DetailPrint "Could not remove $INSTDIR from your PATH (exit $0); remove it by hand."
    Delete "$INSTDIR\aisolate.exe"
    Delete "$INSTDIR\uninstall.exe"
    RMDir "$INSTDIR"
    DeleteRegKey HKCU "${UNINSTALL_KEY}"
SectionEnd
