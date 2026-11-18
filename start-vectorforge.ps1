param(
    [ValidateSet('', 'shard1', 'shard2', 'shard3', 'coordinator', 'frontend')]
    [string]$Service = ''
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$FrontendRoot = Join-Path $ProjectRoot 'frontend'
$Classes = Join-Path $ProjectRoot '.dist\javac-classes'
$RemoteShards = 'http://localhost:9101,http://localhost:9102,http://localhost:9103'

Set-Location -LiteralPath $ProjectRoot

if ($Service) {
    switch ($Service) {
        'shard1' {
            & java --add-modules jdk.httpserver,jdk.incubator.vector -cp $Classes `
                com.vectorforge.shardnode.ShardNodeGateway 9101 'data\process-shard-1'
        }
        'shard2' {
            & java --add-modules jdk.httpserver,jdk.incubator.vector -cp $Classes `
                com.vectorforge.shardnode.ShardNodeGateway 9102 'data\process-shard-2'
        }
        'shard3' {
            & java --add-modules jdk.httpserver,jdk.incubator.vector -cp $Classes `
                com.vectorforge.shardnode.ShardNodeGateway 9103 'data\process-shard-3'
        }
        'coordinator' {
            & java '--add-modules' 'jdk.httpserver,jdk.incubator.vector' `
                "-Dvectorforge.remoteShards=$RemoteShards" `
                -cp $Classes com.vectorforge.api.http.HttpGateway
        }
        'frontend' {
            & npm --prefix $FrontendRoot run dev -- --host 0.0.0.0 --port 5173
        }
    }
    exit $LASTEXITCODE
}

function Get-HttpStatus([string]$Uri) {
    try {
        return [int](Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2).StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

function Test-PortListening([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Stop-VectorForgeProcess([string]$MainClass, [string]$RequiredArgument = '') {
    $matches = @(Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($MainClass) -and
        (-not $RequiredArgument -or $_.CommandLine.Contains($RequiredArgument))
    })
    if ($matches.Count -gt 0) {
        $matches | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
}

function Start-ServiceWindow([string]$Name) {
    $script = $PSCommandPath
    $arguments = "-NoExit -NoProfile -ExecutionPolicy Bypass -File `"$script`" -Service $Name"
    Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WorkingDirectory $ProjectRoot | Out-Null
    Write-Host "Started $Name in a new PowerShell window." -ForegroundColor Green
}

function Ensure-Shard([int]$Port, [string]$ServiceName, [string]$ShardPath) {
    $base = "http://localhost:$Port"
    $healthy = (Get-HttpStatus "$base/stats") -eq 200 -and (Get-HttpStatus "$base/clear") -eq 405
    if ($healthy) {
        $existing = @(Get-CimInstance Win32_Process | Where-Object {
            $_.CommandLine -and $_.CommandLine.Contains('com.vectorforge.shardnode.ShardNodeGateway') -and
            $_.CommandLine.Contains(" $Port ") -and $_.CommandLine.Contains($ShardPath)
        })
        if ($existing.Count -gt 0) {
            Write-Host "Shard on port $Port is already running." -ForegroundColor DarkGreen
            return
        }
        Stop-VectorForgeProcess 'com.vectorforge.shardnode.ShardNodeGateway'
    }
    elseif (Test-PortListening $Port) {
        Stop-VectorForgeProcess 'com.vectorforge.shardnode.ShardNodeGateway'
        if (Test-PortListening $Port) {
            throw "Port $Port is occupied by another application. Stop it, then rerun this launcher."
        }
    }
    Start-ServiceWindow $ServiceName
}

function Ensure-Coordinator {
    $healthy = (Get-HttpStatus 'http://localhost:8080/v1/cluster/nodes') -eq 200 -and
        (Get-HttpStatus 'http://localhost:8080/v1/cluster/clear') -eq 405
    if ($healthy) {
        $existing = @(Get-CimInstance Win32_Process | Where-Object {
            $_.CommandLine -and $_.CommandLine.Contains('com.vectorforge.api.http.HttpGateway') -and
            $_.CommandLine.Contains('vectorforge.remoteShards=http://localhost:9101')
        })
        if ($existing.Count -gt 0) {
            Write-Host 'Coordinator is already connected to the three remote shards.' -ForegroundColor DarkGreen
            return
        }
        Stop-VectorForgeProcess 'com.vectorforge.api.http.HttpGateway'
    }
    elseif (Test-PortListening 8080) {
        Stop-VectorForgeProcess 'com.vectorforge.api.http.HttpGateway'
        if (Test-PortListening 8080) {
            throw 'Port 8080 is occupied by another application. Stop it, then rerun this launcher.'
        }
    }
    Start-ServiceWindow 'coordinator'
}

function Ensure-Frontend {
    try {
        $response = Invoke-WebRequest -Uri 'http://localhost:5173/' -UseBasicParsing -TimeoutSec 4
        if ($response.StatusCode -eq 200 -and $response.Content -match 'VectorForge \| Operations') {
            Write-Host 'Frontend is already serving on port 5173.' -ForegroundColor DarkGreen
            return
        }
    }
    catch {
        # No healthy frontend is responding yet; check the port before starting Vite.
    }
    if (Test-PortListening 5173) {
        throw 'Port 5173 is occupied by another application. Stop it, then rerun this launcher.'
    }
    if (-not (Test-Path (Join-Path $FrontendRoot 'node_modules\vite'))) {
        Write-Host 'Installing frontend packages...' -ForegroundColor Yellow
        & npm --prefix $FrontendRoot install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    Start-ServiceWindow 'frontend'
}

Write-Host 'VectorForge one-click startup' -ForegroundColor Cyan

if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'JDK 21 or newer is required. javac was not found on PATH.'
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java was not found on PATH.'
}
if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'Node.js and npm are required to run the browser frontend.'
}

$modulePaths = @('api', 'cluster', 'shard-node', 'storage-engine', 'vector-index', 'benchmark', 'client-sdk')
$sources = @(
    Get-ChildItem -Path $modulePaths -Recurse -Filter '*.java' -File |
        Where-Object { $_.FullName -match '\\src\\main\\java\\' } |
        ForEach-Object { $_.FullName }
)
if ($sources.Count -eq 0) { throw 'No Java source files were found.' }
New-Item -ItemType Directory -Force $Classes | Out-Null
Write-Host "Compiling $($sources.Count) Java source files..." -ForegroundColor Yellow
& javac --add-modules jdk.httpserver,jdk.incubator.vector -d $Classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

Ensure-Shard 9101 'shard1' 'data\process-shard-1'
Ensure-Shard 9102 'shard2' 'data\process-shard-2'
Ensure-Shard 9103 'shard3' 'data\process-shard-3'
Ensure-Coordinator
Ensure-Frontend

Write-Host ''
Write-Host 'VectorForge startup commands launched.' -ForegroundColor Green
Write-Host 'Frontend:    http://localhost:5173' -ForegroundColor Cyan
Write-Host 'Coordinator: http://localhost:8080' -ForegroundColor Cyan
Write-Host 'Shards:      http://localhost:9101, :9102, :9103' -ForegroundColor Cyan
Start-Process 'http://localhost:5173/'