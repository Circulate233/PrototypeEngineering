param(
    [string] $ClasspathFile = (Join-Path $PSScriptRoot '../visual-examples/runtime-classpath.txt'),
    [string] $AddonClasses,
    [string] $EvidenceDirectory = (Join-Path $PSScriptRoot 'results'),
    [string] $ProjectRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $JdkHome = 'C:/Program Files/Zulu/zulu-25'
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
if (-not $AddonClasses) {
    $buildRecord = Get-Content -Raw -LiteralPath (Join-Path $projectPath 'run/visual-examples/build-result.json') -Encoding utf8 | ConvertFrom-Json
    $AddonClasses = Join-Path (Split-Path -Parent $buildRecord.artifact) 'classes'
}
$addonPath = (Resolve-Path -LiteralPath $AddonClasses).Path
$classpathPath = (Resolve-Path -LiteralPath $ClasspathFile).Path
$evidencePath = [System.IO.Path]::GetFullPath($EvidenceDirectory)
$entries = @((Get-Content -Raw -LiteralPath $classpathPath -Encoding utf8) -split '[;\r\n]+' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
foreach ($entry in $entries) { if (-not (Test-Path -LiteralPath $entry)) { throw "Missing actual classpath entry: $entry" } }
$classpath = $addonPath + [System.IO.Path]::PathSeparator + ($entries -join [System.IO.Path]::PathSeparator)
$classes = Join-Path $evidencePath 'verifier-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$javac = Join-Path $JdkHome 'bin/javac.exe'
$java = Join-Path $JdkHome 'bin/java.exe'
$source = Join-Path $PSScriptRoot 'ScriptVerifier.java'
$arguments = @('--release','25','-encoding','UTF-8','-proc:none','-classpath',('"'+$classpath.Replace('\','/')+'"'),'-d',('"'+$classes.Replace('\','/')+'"'),('"'+$source.Replace('\','/')+'"'))
$argumentFile = Join-Path $evidencePath 'verifier-javac.args'
$arguments | Set-Content -LiteralPath $argumentFile -Encoding utf8
& $javac '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' "@$argumentFile" 2>&1 | Tee-Object -FilePath (Join-Path $evidencePath 'verifier-javac.log')
if ($LASTEXITCODE -ne 0) { throw "Verifier javac failed: $LASTEXITCODE" }
$runtimeClasspath = $classes + [System.IO.Path]::PathSeparator + $classpath
$report = Join-Path $evidencePath 'script-verifier-result.json'
Push-Location -LiteralPath $projectPath
try {
    & $java '-Duser.language=en' '-Dfile.encoding=UTF-8' '-cp' $runtimeClasspath 'pe.run.verification.ScriptVerifier' $projectPath $report 2>&1 |
        Tee-Object -FilePath (Join-Path $evidencePath 'script-verifier.log')
    $verifierExit = $LASTEXITCODE
} finally { Pop-Location }
if ($verifierExit -ne 0) { throw "Script verifier failed with exit $verifierExit; retain report and raw log in $evidencePath" }
Write-Output "Standalone verification report: $report"
Write-Output 'Compiled real SDK scripts and executed core API cases; no Minecraft/FML bootstrap or World command was started.'
