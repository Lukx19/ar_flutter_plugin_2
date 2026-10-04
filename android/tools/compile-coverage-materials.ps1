[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$MatcPath
)

$ErrorActionPreference = 'Stop'
$compiler = (Resolve-Path -LiteralPath $MatcPath).Path
# Filament 1.72.1's matc reports the binary material format, not its patch version.
$format = (& $compiler --version | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $format -ne '72') {
    throw "Coverage materials require Filament material format 72; compiler reported '$format'."
}
$main = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../src/main')).Path
foreach ($name in @('coverage_points', 'coverage_cubes')) {
    $source = Join-Path $main "materials/$name.mat"
    $output = Join-Path $main "assets/materials/$name.filamat"
    & $compiler --platform mobile --api opengl --api vulkan --output $output $source
    if ($LASTEXITCODE -ne 0) { throw "Material compilation failed: $name" }
}
