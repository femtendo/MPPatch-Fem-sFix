Name "MPPatch"
SetCompressor /SOLID /FINAL lzma
OutFile ..\..\target\mppatch-installer-unmodified.exe
CRCCheck off ; We will be altering the final binary in a way that invalidates the CRC

; String-search helper for detecting the unattended (/S) install switch.
!include "StrFunc.nsh"
${StrStr}

; Mutex code, from https://nsis.sourceforge.io/Allow_only_one_installer_instance
!define INSTALLERMUTEXNAME "MPPatch NSIS Wrapper / 24d1f759-689d-4707-8fb9-3508574253e7"
!macro SingleInstanceMutex
    System::Call 'KERNEL32::CreateMutex(p0, i1, t"${INSTALLERMUTEXNAME}")?e'
    Pop $0
    IntCmpU $0 183 "" launch launch ; ERROR_ALREADY_EXISTS?
        MessageBox MB_ICONSTOP "MPPatch Installer is already running!"
        Abort
    launch:
!macroend
; End mutex code

; Set to 1 when a truly unattended (/S) install is requested, in which case we
; drive the headless mppatch-cli.exe (same preflight -> install code path as
; the GUI) instead of opening the interactive window.
Var MPPATCH_UNATTENDED

Function .onInit
    SetSilent silent
    !insertmacro SingleInstanceMutex
    ; Detect the unattended silent switch on the command line. $CMDLINE is the
    ; full command line passed to the installer; /S selects silent/unattended.
    ${StrStr} $0 $CMDLINE "/S"
    StrCmp $0 "" notUnattended isUnattended
    isUnattended:
        StrCpy $MPPATCH_UNATTENDED 1
        Goto done
    notUnattended:
        StrCpy $MPPATCH_UNATTENDED 0
    done:
FunctionEnd

Function un.onInit
    !insertmacro SingleInstanceMutex
FunctionEnd

Section "Extract and execute wrapped installer"
    RMDir /r $TEMP\MPPatchInstaller
    SetOutPath $TEMP\MPPatchInstaller

    ; Both the GUI (mppatch-installer.exe) and the headless CLI (mppatch-cli.exe)
    ; land here:  File ..\..\target\native-image-win32\*
    File ..\..\target\native-image-win32\*

    System::Call 'Kernel32::SetEnvironmentVariable(t, t)i ("NSIS_LAUNCH_MARKER", "018c6bba-54e0-7cf2-b16a-7b6abb9215e0").r0'
    System::Call 'Kernel32::SetEnvironmentVariable(t, t)i ("NSIS_LAUNCH_EXE", "$EXEPATH").r0'
    System::Call 'Kernel32::SetEnvironmentVariable(t, t)i ("NSIS_LAUNCH_TEMPDIR", "$TEMP\MPPatchInstaller").r0'

    ; Unattended (/S) install -> headless CLI, keeping the exact same install
    ; code path as the interactive GUI flow (preflight -> patch install).
    ; The NSIS /D= install-dir switch (must be the LAST command-line argument,
    ; and is passed without quotes) is read by NSIS into $INSTDIR and mapped to
    ; the CLI's --path argument.
    StrCmp $MPPATCH_UNATTENDED 1 0 guiLaunch
        StrCmp $INSTDIR "" noPath withPath
        withPath:
            ExecWait '"$OUTDIR\mppatch-cli.exe" install --path "$INSTDIR"'
            Goto cleanup
        noPath:
            ExecWait '"$OUTDIR\mppatch-cli.exe" install'
            Goto cleanup
    guiLaunch:
        ExecWait '"$OUTDIR\mppatch-installer.exe"'

    cleanup:
    SetOutPath $TEMP
    RMDir /r $TEMP\MPPatchInstaller
SectionEnd
