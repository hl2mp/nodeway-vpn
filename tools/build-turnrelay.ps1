
<#
.SYNOPSIS
    Builds turnrelay-proxy for all Android ABIs.

.DESCRIPTION
    Builds turnrelay-proxy using Go and Android NDK.
    Supports arm64-v8a, armeabi-v7a, x86_64 and x86.

    Output files are executable Go binaries, not JNI shared libraries.
#>

[CmdletBinding()]
param(
    [string] $Abis = 'arm64-v8a,armeabi-v7a,x86_64,x86',
    [string] $SourceDir = '../turnrelay',
    [string] $OutputDir = 'app/src/main/jniLibs',
    [string] $NdkDir = '',
    [int] $ApiLevel = 21
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root $SourceDir
$output = Join-Path $root $OutputDir

# Check Go
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw 'Go not found in PATH'
}

# Check turnrelay source
if (-not (Test-Path (Join-Path $source 'go.mod'))) {
    throw "turnrelay source not found: $source"
}

if (-not (Test-Path (Join-Path $source 'cmd\turnrelay-proxy'))) {
    throw 'turnrelay-proxy source directory not found'
}

# Find Android NDK automatically
if (-not $NdkDir) {
    if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
        $NdkDir = $env:ANDROID_NDK_HOME
    }
    elseif ($env:ANDROID_NDK_ROOT -and (Test-Path $env:ANDROID_NDK_ROOT)) {
        $NdkDir = $env:ANDROID_NDK_ROOT
    }
    else {
        $sdk = $env:ANDROID_SDK_ROOT
        if (-not $sdk) {
            $sdk = $env:ANDROID_HOME
        }
        if (-not $sdk) {
            $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        }

        $ndkRoot = Join-Path $sdk 'ndk'

        if (Test-Path $ndkRoot) {
            $ndk = Get-ChildItem $ndkRoot -Directory |
                Sort-Object Name -Descending |
                Select-Object -First 1

            if ($ndk) {
                $NdkDir = $ndk.FullName
            }
        }
    }
}

if (-not $NdkDir -or -not (Test-Path $NdkDir)) {
    throw 'Android NDK not found. Specify -NdkDir.'
}

# Windows NDK Clang
$clang = Join-Path $NdkDir `
    'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'

if (-not (Test-Path $clang)) {
    throw "NDK clang not found: $clang"
}

Write-Host "Using NDK: $NdkDir"
Write-Host "Using Clang: $clang"

# Android ABI -> Go architecture + NDK target triple
$targets = @{
    'arm64-v8a' = @{
        Arch = 'arm64'
        Triple = 'aarch64-linux-android'
        Env = @{}
    }

    'armeabi-v7a' = @{
        Arch = 'arm'
        Triple = 'armv7a-linux-androideabi'
        Env = @{ GOARM = '7' }
    }

    'x86_64' = @{
        Arch = 'amd64'
        Triple = 'x86_64-linux-android'
        Env = @{}
    }

    'x86' = @{
        Arch = '386'
        Triple = 'i686-linux-android'
        Env = @{ GO386 = 'softfloat' }
    }
}

# Save original environment
$envNames = @(
    'CGO_ENABLED',
    'GOOS',
    'GOARCH',
    'GOARM',
    'GO386',
    'CC'
)

$oldEnv = @{}

foreach ($name in $envNames) {
    $oldEnv[$name] = [Environment]::GetEnvironmentVariable(
        $name, 'Process'
    )
}

Push-Location $source

try {
    foreach ($abi in $Abis.Split(',') |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }) {

        $target = $targets[$abi]

        if (-not $target) {
            throw "Unknown ABI: $abi"
        }

        $dir = Join-Path $output $abi

        New-Item -ItemType Directory -Force -Path $dir |
            Out-Null

        $binary = Join-Path $dir 'libturnrelay-proxy.so'

        Write-Host ''
        Write-Host "========================================"
        Write-Host "Building turnrelay-proxy for $abi"
        Write-Host "========================================"

        # Configure Go
        $env:GOOS = 'android'
        $env:GOARCH = $target.Arch
        $env:CGO_ENABLED = '1'

        Remove-Item Env:GOARM -ErrorAction SilentlyContinue
        Remove-Item Env:GO386 -ErrorAction SilentlyContinue

        foreach ($key in $target.Env.Keys) {
            Set-Item -Path "env:$key" -Value $target.Env[$key]
        }

        # Configure Android NDK Clang
        $env:CC = "`"$clang`" --target=$($target.Triple)$ApiLevel"

        Write-Host "GOARCH: $env:GOARCH"
        Write-Host "CC: $env:CC"
        Write-Host "Output: $binary"

        # Build
        go build -trimpath `
            '-ldflags=-s -w -checklinkname=0 -linkmode=external' `
            -o $binary `
            ./cmd/turnrelay-proxy

        if ($LASTEXITCODE -ne 0) {
            throw "Build failed for $abi"
        }

        $mb = [math]::Round(
            (Get-Item $binary).Length / 1MB, 1
        )

        Write-Host "SUCCESS: $abi ($mb MB)" -ForegroundColor Green
    }

    Write-Host ''
    Write-Host 'All requested builds completed!' -ForegroundColor Green
}
finally {
    Pop-Location

    # Restore environment
    foreach ($name in $envNames) {
        if ($null -eq $oldEnv[$name]) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
        else {
            Set-Item -Path "env:$name" -Value $oldEnv[$name]
        }
    }
}
