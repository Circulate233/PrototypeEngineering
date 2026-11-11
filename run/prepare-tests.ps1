param(
    [string] $ProjectRoot = (Join-Path $PSScriptRoot '..'),
    [string] $JdkHome = 'C:/Program Files/Zulu/zulu-25',
    [Alias('WithBloom')] [switch] $Bloom,
    [switch] $SkipInstall
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
$runtimeScript = Join-Path $projectPath 'run/test-runtime.gradle'
$exportScript = Join-Path $projectPath 'run/export-classpath.gradle'
$gradleArguments = @('-I', $runtimeScript, '-I', $exportScript, 'peRunExportClasspath', '--console=plain')
if ($Bloom) { $gradleArguments += '-PpeRunBloom=true' }
Push-Location -LiteralPath $projectPath
try {
    & (Join-Path $projectPath 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Run classpath preparation failed with exit code $LASTEXITCODE" }
    & (Join-Path $projectPath 'run/visual-examples/build.ps1') -ProjectRoot $projectPath -JdkHome $JdkHome -SkipInstall:$SkipInstall
    $buildRecord = Get-Content -LiteralPath (Join-Path $projectPath 'run/visual-examples/build-result.json') -Raw -Encoding utf8 | ConvertFrom-Json
    if (-not $SkipInstall -and -not $buildRecord.installed) {
        throw "Addon compiled but could not be installed: $($buildRecord.installationError). Generated jar: $($buildRecord.artifact). Exit the client and prepare again."
    }
} finally { Pop-Location }
Write-Output 'Prepared current PE classes, CT2 runtime classpath and the run-only addon. No game or server was started.'
