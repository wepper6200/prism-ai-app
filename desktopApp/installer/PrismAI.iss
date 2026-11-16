#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef AppSourceDir
  #error AppSourceDir is required
#endif
#ifndef OutputDirectory
  #error OutputDirectory is required
#endif
#ifndef InstallerAppId
  #define InstallerAppId "ai.prism.desktop"
#endif

#ifndef InstallerAppName
  #define InstallerAppName "Prism AI"
#endif

[Setup]
AppId={#InstallerAppId}
AppName={#InstallerAppName}
AppVersion={#AppVersion}
AppPublisher=Prism AI
DefaultDirName={localappdata}\Programs\Prism AI
DefaultGroupName={#InstallerAppName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir={#OutputDirectory}
OutputBaseFilename=PrismAI-{#AppVersion}-windows-x64-Setup
SetupIconFile=..\icons\prism.ico
UninstallDisplayIcon={app}\PrismAI.exe
Compression=lzma2/fast
SolidCompression=yes
WizardStyle=modern
DisableWelcomePage=no
SetupLogging=yes
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#AppSourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#InstallerAppName}"; Filename: "{app}\PrismAI.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\{#InstallerAppName}"; Filename: "{app}\PrismAI.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\PrismAI.exe"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,Prism AI}"; Flags: nowait postinstall skipifsilent shellexec runasoriginaluser
