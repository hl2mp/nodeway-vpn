<#
.SYNOPSIS
    Builds the olcrtc client for Android and places it into jniLibs.

.DESCRIPTION
    The file must be named libolcrtc.so: only lib*.so files land in
    nativeLibraryDir, which is the one location Android still allows exec from
    (SELinux label apk_data_file). Exec from the app home directory has been
    blocked since Android 10.

    olcrtc sources are used as-is, no patches: the stock CLI is built and it
    takes a YAML config as its only argument.

.EXAMPLE
    .\tools\build-olcrtc.ps1
    .\tools\build-olcrtc.ps1 -Abis arm64-v8a,x86_64
#>
[CmdletBinding()]
param(
    [string] $Abis = 'arm64-v8a,armeabi-v7a,x86_64,x86',
    [string] $SourceDir = '../olcrtc',
    [string] $OutputDir = 'app/src/main/jniLibs'
)

# Kept ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 as ANSI unless
# the file carries a BOM, and Cyrillic comments turn into parser errors.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root $SourceDir
$output = Join-Path $root $OutputDir

# GOARCH/GOARM per ABI, plus the NDK clang prefix.
$targets = @{
    'arm64-v8a'   = @{ Arch = 'arm64'; Prefix = 'aarch64-linux-android24-clang'; Env = @{} }
    'armeabi-v7a' = @{ Arch = 'arm';   Prefix = 'armv7a-linux-androideabi24-clang'; Env = @{ GOARM = '7' } }
    'x86_64'      = @{ Arch = 'amd64'; Prefix = 'x86_64-linux-android24-clang'; Env = @{} }
    'x86'         = @{ Arch = '386';   Prefix = 'i686-linux-android24-clang'; Env = @{ GO386 = 'softfloat' } }
}

$ndkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk'
if (-not (Test-Path $ndkRoot)) { throw "NDK not found in $ndkRoot" }

$ndk = Get-ChildItem $ndkRoot -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'toolchains\llvm\prebuilt') } |
    Sort-Object Name -Descending |
    Select-Object -First 1
if (-not $ndk) { throw 'No NDK with a prebuilt toolchain' }

$binDir = Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin'

if (-not (Get-Command go -ErrorAction SilentlyContinue)) { throw 'Go not found in PATH' }
Push-Location $source

try {
    foreach ($abi in $Abis.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }) {
        $target = $targets[$abi]
        if (-not $target) { throw "Unknown ABI: $abi" }

        $cc = Join-Path $binDir ($target.Prefix + '.cmd')
        $cxx = Join-Path $binDir ($target.Prefix + '++.cmd')
        if (-not (Test-Path $cc)) { throw "Missing compiler: $cc" }

        $dir = Join-Path $output $abi
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $binary = Join-Path $dir 'libolcrtc.so'

        # -checklinkname=0 is mandatory: without it the link step fails with
        # "anet: invalid reference to net.zoneCache". libXray uses the same flag.
        Write-Host "Building $abi -> $binary"

        $env:CGO_ENABLED = '1'
        $env:GOOS = 'android'
        $env:GOARCH = $target.Arch
        $env:CC = $cc
        $env:CXX = $cxx
        foreach ($key in $target.Env.Keys) { Set-Item -Path "env:$key" -Value $target.Env[$key] }

        go build '-ldflags=-s -w -checklinkname=0' -o $binary ./cmd/olcrtc
        if ($LASTEXITCODE -ne 0) { throw "Build failed for $abi" }

        $mb = [math]::Round((Get-Item $binary).Length / 1MB, 1)
        Write-Host "  done: $mb MB"
    }
} finally {
    Pop-Location
}