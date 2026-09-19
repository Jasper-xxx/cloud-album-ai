param(
    [ValidateSet('backend','ai','frontend','all')][string]$Service='all',
    [switch]$Foreground
)
$ErrorActionPreference='Stop'
if($Foreground -and $Service -eq 'all'){throw 'Foreground mode requires one service: backend, ai, or frontend. Use a separate terminal for each.'}
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
# Both applications read the shared root .env themselves, just as they do when
# started manually. Do not override it with the obsolete temporary JSON secrets.
$localEnvPath=Join-Path $taskRoot '.env'
if($Service -ne 'frontend' -and !(Test-Path -LiteralPath $localEnvPath)) {throw 'Configure the repository root .env with the shared service credentials before starting local services.'}
$env:AI_SERVICE_HOST='127.0.0.1'
$env:PYTHONIOENCODING='utf-8'
$launched=@{}
if($Service -in @('backend','all')) {
    if(Get-NetTCPConnection -State Listen -LocalPort 8088 -ErrorAction SilentlyContinue){throw 'Port 8088 is already in use; inspect the running backend before restarting.'}
    $jar=Join-Path $taskRoot 'backend/target/xzp-0.0.1-SNAPSHOT.jar'
    $arguments=@('-jar',$jar,'--app.background-maintenance-enabled=false','--app.file.cleanup-enabled=false','--app.file.gc.enabled=false','--app.file.reconcile.enabled=false','--app.async.task.mq.enabled=false')
    if($Foreground){
        Push-Location (Join-Path $taskRoot 'backend')
        try { & java.exe @arguments; $serviceExitCode=$LASTEXITCODE } finally { Pop-Location }
        exit $serviceExitCode
    }
    $process=Start-Process -FilePath 'java.exe' -ArgumentList $arguments -WorkingDirectory (Join-Path $taskRoot 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'tmp/agent-backend.log') -RedirectStandardError (Join-Path $taskRoot 'tmp/agent-backend.err.log')
    $launched.backend=$process.Id
}
if($Service -in @('ai','all')) {
    if(Get-NetTCPConnection -State Listen -LocalPort 5000 -ErrorAction SilentlyContinue){throw 'Port 5000 is already in use; inspect the running AI service before restarting.'}
    if($Foreground){
        Push-Location (Join-Path $taskRoot 'ai-service')
        try { & 'D:/pythonProject/RAGProject/venv/Scripts/python.exe' run.py; $serviceExitCode=$LASTEXITCODE } finally { Pop-Location }
        exit $serviceExitCode
    }
    $process=Start-Process -FilePath 'D:/pythonProject/RAGProject/venv/Scripts/python.exe' -ArgumentList 'run.py' -WorkingDirectory (Join-Path $taskRoot 'ai-service') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'tmp/agent-ai.log') -RedirectStandardError (Join-Path $taskRoot 'tmp/agent-ai.err.log')
    $launched.ai=$process.Id
}
if($Service -in @('frontend','all')) {
    if(Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue){throw 'Port 8080 is already in use; inspect the running frontend before restarting.'}
    if($Foreground){
        Push-Location (Join-Path $taskRoot 'frontend')
        try { & node.exe node_modules/vite/bin/vite.js; $serviceExitCode=$LASTEXITCODE } finally { Pop-Location }
        exit $serviceExitCode
    }
    $process=Start-Process -FilePath 'node.exe' -ArgumentList 'node_modules/vite/bin/vite.js' -WorkingDirectory (Join-Path $taskRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $taskRoot 'tmp/agent-frontend.log') -RedirectStandardError (Join-Path $taskRoot 'tmp/agent-frontend.err.log')
    $launched.frontend=$process.Id
}
$launched | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRoot "tmp/agent-$Service-pids.json")
$launched
