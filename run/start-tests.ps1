param(
    [string] $ProjectRoot = (Join-Path $PSScriptRoot '..'),
    [string] $JdkHome = 'C:/Program Files/Zulu/zulu-25',
    [Alias('WithBloom')] [switch] $Bloom,
    [switch] $SkipPrepare
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
if (-not $SkipPrepare) {
    & (Join-Path $projectPath 'run/prepare-tests.ps1') -ProjectRoot $projectPath -JdkHome $JdkHome -Bloom:$Bloom
}
$gradleArguments = @('-I', (Join-Path $projectPath 'run/test-runtime.gradle'), 'runClient', '--console=plain')
if ($Bloom) { $gradleArguments += '-PpeRunBloom=true' }
Write-Output 'Launching the development client because start-tests.ps1 was explicitly invoked. Restart the client fully after addon or script updates.'
Push-Location -LiteralPath $projectPath
try {
    & (Join-Path $projectPath 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Development client exited with code $LASTEXITCODE" }
} finally { Pop-Location }
