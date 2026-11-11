param(
    [string] $ProjectRoot = (Join-Path $PSScriptRoot '../..'),
    [string] $JdkHome = 'C:/Program Files/Zulu/zulu-25',
    [string] $ClasspathFile = 'run/visual-examples/runtime-classpath.txt',
    [switch] $RefreshClasspath,
    [switch] $SkipInstall
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$examplePath = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$classpathPath = if ([System.IO.Path]::IsPathRooted($ClasspathFile)) { $ClasspathFile } else { Join-Path $projectPath $ClasspathFile }
if ($RefreshClasspath -or -not (Test-Path -LiteralPath $classpathPath -PathType Leaf)) {
    $defaultClasspathPath = Join-Path $projectPath 'run/visual-examples/runtime-classpath.txt'
    if ([System.IO.Path]::GetFullPath($classpathPath) -ne [System.IO.Path]::GetFullPath($defaultClasspathPath)) {
        throw "Missing supplied development classpath: $classpathPath. Use the default path or provide a current export."
    }
    Push-Location -LiteralPath $projectPath
    try {
        & (Join-Path $projectPath 'gradlew.bat') '-I' (Join-Path $projectPath 'run/test-runtime.gradle') '-I' (Join-Path $projectPath 'run/export-classpath.gradle') 'peRunExportClasspath' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Current runtime classpath export failed with exit code $LASTEXITCODE. No addon was installed." }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $classpathPath -PathType Leaf)) { throw "Missing current development classpath: $classpathPath" }
$classpathEntries = @((Get-Content -Raw -LiteralPath $classpathPath -Encoding utf8) -split '[;\r\n]+' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($classpathEntries.Count -eq 0) { throw "Development classpath is empty: $classpathPath" }
foreach ($entry in $classpathEntries) {
    if (-not (Test-Path -LiteralPath $entry)) { throw "Missing development classpath entry: $entry" }
}
$classpathText = $classpathEntries -join [System.IO.Path]::PathSeparator
$javacPath = Join-Path $JdkHome 'bin/javac.exe'
$jarPath = Join-Path $JdkHome 'bin/jar.exe'
if (-not (Test-Path -LiteralPath $javacPath)) { throw "Java 25 javac was not found: $javacPath" }
$sourceRoot = Join-Path $examplePath 'src/main/java'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' | Sort-Object FullName)
if ($sources.Count -eq 0) { throw 'No addon Java sources were found.' }
$buildPath = Join-Path $examplePath ('build/compile-' + [Guid]::NewGuid().ToString('N'))
$classesPath = Join-Path $buildPath 'classes'
New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
$emptySourcePath = Join-Path $buildPath 'empty-sourcepath'
New-Item -ItemType Directory -Path $emptySourcePath -Force | Out-Null
$argumentsPath = Join-Path $buildPath 'javac.args'
$arguments = @('--release', '25', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', ('"' + $emptySourcePath.Replace('\', '/') + '"'), '-classpath', ('"' + $classpathText.Replace('\', '/') + '"'), '-d', ('"' + $classesPath.Replace('\', '/') + '"'))
$arguments += $sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
$arguments | Set-Content -LiteralPath $argumentsPath -Encoding utf8
& $javacPath '-J-Duser.language=en' '-J-Dfile.encoding=UTF-8' "@$argumentsPath"
if ($LASTEXITCODE -ne 0) { throw "Example javac failed with exit code $LASTEXITCODE. No addon was installed." }
$manifestPath = Join-Path $buildPath 'MANIFEST.MF'
@('Manifest-Version: 1.0', 'Implementation-Title: PE Visual Examples', 'Implementation-Version: 1.0.0-dev', '') |
    Set-Content -LiteralPath $manifestPath -Encoding ascii
$temporaryJar = Join-Path $buildPath 'pe_visual_examples-1.0.0-dev.jar'
$resourcesPath = Join-Path $examplePath 'src/main/resources'
& $jarPath --create --file $temporaryJar --manifest $manifestPath -C $classesPath . -C $resourcesPath .
if ($LASTEXITCODE -ne 0) { throw "Example jar creation failed with exit code $LASTEXITCODE. No addon was installed." }
$modsPath = Join-Path $projectPath 'run/mods'
New-Item -ItemType Directory -Path $modsPath -Force | Out-Null
$targetJar = Join-Path $modsPath 'pe_visual_examples-1.0.0-dev.jar'
$installed = $false
$installationError = $null
Add-Type -AssemblyName System.IO.Compression.FileSystem
if (-not $SkipInstall) {
    try {
        foreach ($existingJar in Get-ChildItem -LiteralPath $modsPath -Filter '*.jar') {
            $archive = [System.IO.Compression.ZipFile]::OpenRead($existingJar.FullName)
            try {
                $metadata = $archive.GetEntry('mcmod.info')
                if ($null -eq $metadata) { continue }
                $reader = [System.IO.StreamReader]::new($metadata.Open())
                try { $entries = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
                if (@($entries | Where-Object { $_.modid -eq 'pe_visual_examples' }).Count -gt 0 -and $existingJar.FullName -ne $targetJar) {
                    throw "A second pe_visual_examples addon already exists: $($existingJar.FullName). Review it before installing another copy."
                }
            } finally { $archive.Dispose() }
        }
        if (Test-Path -LiteralPath $targetJar) {
            # A running client can retain the addon jar. Do not replace an occupied target.
            $exclusiveCheck = [System.IO.File]::Open($targetJar, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $exclusiveCheck.Dispose()
            $archive = [System.IO.Compression.ZipFile]::OpenRead($targetJar)
            try {
                $metadata = $archive.GetEntry('mcmod.info')
                if ($null -eq $metadata) { throw 'Refusing to overwrite an unknown jar without example metadata.' }
                $reader = [System.IO.StreamReader]::new($metadata.Open())
                try { $entries = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
                if (@($entries | Where-Object { $_.modid -eq 'pe_visual_examples' }).Count -ne 1) { throw 'Refusing to overwrite a jar with different mod identity.' }
            } finally { $archive.Dispose() }
        }
        [System.IO.File]::Copy($temporaryJar, $targetJar, $true)
        $installed = $true
    } catch [System.IO.IOException] {
        $installationError = $_.Exception.Message
        Write-Warning "Addon compiled; target is unavailable. Generated jar retained at: $temporaryJar. $installationError"
    } catch [System.UnauthorizedAccessException] {
        $installationError = $_.Exception.Message
        Write-Warning "Addon compiled; target is inaccessible. Generated jar retained at: $temporaryJar. $installationError"
    }
}
$record = [ordered]@{
    modid = 'pe_visual_examples'; artifact = $temporaryJar
    target = $targetJar; installed = $installed; installationSkipped = [bool] $SkipInstall; installationError = $installationError
    classpath = $classpathPath
    javac = $javacPath; javacArguments = $argumentsPath
    sourceCount = $sources.Count; sources = @($sources.FullName)
}
$record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $examplePath 'build-result.json') -Encoding utf8
$sources.FullName | Set-Content -LiteralPath (Join-Path $examplePath 'java-sources.txt') -Encoding utf8
Write-Output "Built development addon: $temporaryJar"
Write-Output "Installed to run/mods: $installed"
Write-Output 'No Minecraft, server, FML or Bootstrap process was started.'
