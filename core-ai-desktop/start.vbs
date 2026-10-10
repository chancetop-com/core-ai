' Silent launcher: starts core-ai-desktop with no console window at all.
' Double-click this file, or pin it to the taskbar.
' Logs use a per-launch name: a stale file handle (e.g. from a killed run) must never
' be able to lock the launcher out of writing its logs.
Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = "D:\core-ai\core-ai-desktop"
shell.Run "cmd /c npm start > ""%TEMP%\coreai-desktop-%RANDOM%.out.log"" 2> ""%TEMP%\coreai-desktop-%RANDOM%.err.log""", 0, False
