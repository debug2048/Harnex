; Optional native CLI PATH integration for the Tauri v2 NSIS installer.
; The Rust launcher owns registry parsing, exact de-duplication/removal and
; WM_SETTINGCHANGE broadcasting. The marker ensures uninstall only removes a
; value which this installer actually added.

!macro NSIS_HOOK_POSTINSTALL
  ; Tauri includes installer hooks before its MUI_LANGUAGE declarations. A
  ; custom LangString declared at include time can therefore resolve to an
  ; empty string at runtime. Select explicit text after $LANGUAGE is set.
  StrCpy $R8 "Add the Harnex command-line tool to the system PATH?"
  StrCpy $R9 "Harnex was installed, but PATH could not be updated. Retry from an elevated terminal: harnex path install --machine"
  StrCmp $LANGUAGE ${LANG_SIMPCHINESE} harnex_path_chinese harnex_path_language_done
harnex_path_chinese:
  StrCpy $R8 "是否将 Harnex 命令行工具添加到系统 PATH？"
  StrCpy $R9 "Harnex 已安装，但无法更新 PATH。请在管理员终端重试：harnex path install --machine"
harnex_path_language_done:
  ; Silent installs must remain non-interactive and leave PATH unchanged.
  IfSilent harnex_path_done harnex_path_prompt
harnex_path_prompt:
  MessageBox MB_YESNO|MB_ICONQUESTION "$R8" IDNO harnex_path_done
  nsExec::ExecToLog '"$INSTDIR\resources\bin\harnex.exe" path install --machine --installer'
  Pop $0
  StrCmp $0 "0" harnex_path_success harnex_path_failed
harnex_path_success:
  Goto harnex_path_done
harnex_path_failed:
  MessageBox MB_OK|MB_ICONEXCLAMATION "$R9"
harnex_path_done:
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ReadRegDWORD $0 HKLM "Software\Harnex" "CliPathInstalled"
  StrCmp $0 1 0 harnex_path_uninstall_done
  IfFileExists "$INSTDIR\resources\bin\harnex.exe" 0 harnex_path_uninstall_done
  nsExec::ExecToLog '"$INSTDIR\resources\bin\harnex.exe" path uninstall --machine'
  Pop $1
  StrCmp $1 "0" harnex_path_uninstall_marker harnex_path_uninstall_done
harnex_path_uninstall_marker:
  DeleteRegValue HKLM "Software\Harnex" "CliPathInstalled"
  DeleteRegKey /ifempty HKLM "Software\Harnex"
harnex_path_uninstall_done:
!macroend
