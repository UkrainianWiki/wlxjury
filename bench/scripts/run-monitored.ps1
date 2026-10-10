#requires -Version 7.0
# Full suite against the runbook's server, with 10-second host power/RAM/disk monitoring.
# Example (from the repo, with BENCH_SCRATCH exported):
# pwsh -NoProfile -File bench/scripts/run-monitored.ps1 -Label p3-prod-innodb -Dist prod -Snapshot v53-innodb
# -AllowLowMemory records an explicitly approved exception; it never changes server limits.
# A power change, three samples below 768 MiB free, or <2 GiB disk aborts and discards the run.
# All seven reports, HTTP outcomes, distribution counts, job completion, effective buffers,
# resource limits and successful Flyway history are checked before marking it validated.
param(
  [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Label,
  [Parameter(Mandatory)][ValidatePattern('^[a-z0-9-]+$')][string]$Dist,
  [Parameter(Mandatory)][ValidateSet('v53-innodb','v53-myisam','v53-innodb-trim')][string]$Snapshot,
  [ValidatePattern('^(-|[a-z0-9-]+)$')][string]$Cnf = '-',
  [ValidatePattern('^\d+(\.\d+)*$')][string]$ExpectedFlywayVersion,
  [switch]$AllowLowMemory
)
$ErrorActionPreference = 'Stop'
$benchRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if (-not $env:BENCH_SCRATCH) { throw 'Set BENCH_SCRATCH to a directory outside the repository' }
$benchScratch = [IO.Path]::GetFullPath($env:BENCH_SCRATCH)
if ($benchScratch.Equals($benchRepo, [StringComparison]::OrdinalIgnoreCase) -or $benchScratch.StartsWith($benchRepo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'BENCH_SCRATCH must be outside the repository' }
$benchBash = 'C:\Program Files\Git\bin\bash.exe'
if (-not (Test-Path -LiteralPath $benchBash)) { throw 'Git Bash is required at C:\Program Files\Git\bin\bash.exe' }
$env:MSYS_NO_PATHCONV = '1'
$benchShellRepo = "'" + $benchRepo.Replace('\','/').Replace("'", "'\''") + "'"
function Invoke-BenchBash([string]$Command) {
  & $benchBash -lc ('set -euo pipefail; cd ' + $benchShellRepo + '; ' + $Command)
  if ($LASTEXITCODE -ne 0) { throw "Git Bash failed ($LASTEXITCODE)" }
}
if (-not $AllowLowMemory -and (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB -lt 8) { throw 'Less than 8 GiB free host RAM; an explicit approved exception requires -AllowLowMemory' }
    $benchOut = Join-Path $benchScratch ('results\' + $Label)
    if (Test-Path -LiteralPath (Join-Path $benchOut 'run.log')) { throw "Existing result: $benchOut" }
    if ((Get-CimInstance Win32_Battery).BatteryStatus -ne 2) { throw 'Not on mains power' }
    if ((Get-PSDrive C).Free -lt 10GB) { throw 'Less than 10 GiB free disk' }
    [IO.Directory]::CreateDirectory($benchOut) | Out-Null
    $benchConditions = [ordered]@{
      startedUtc = [DateTimeOffset]::UtcNow.ToString('o'); machine = $env:COMPUTERNAME
      freeHostGiB = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB, 3)
      freeDiskGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 3)
      userApprovedRamException = if ($AllowLowMemory) { 'Explicit -AllowLowMemory exception to 8 GiB free-host-RAM prerequisite' } else { $null }
      minimumFreeHostMiBAbort = 768; sustainedLowMemorySamples = 3; monitorSeconds = 10
      dist = $Dist; snapshot = $Snapshot; cnf = $Cnf
    }
    $benchConditions | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $benchOut 'conditions.json') -Encoding utf8
    'utc,battery_status,free_host_mib,free_disk_gib' | Set-Content -LiteralPath (Join-Path $benchOut 'host-samples.csv') -Encoding utf8
    $benchCommand = 'set -euo pipefail; cd ' + $benchShellRepo + '; source bench/scripts/suite.sh; exec bench/scripts/run.sh LABEL DIST SNAP CNF "${SUITE[@]}"'
    $benchCommand = $benchCommand.Replace('LABEL', $Label).Replace('DIST', $Dist).Replace('SNAP', $Snapshot).Replace('CNF', $Cnf)
    $benchPsi = [Diagnostics.ProcessStartInfo]::new($benchBash)
    $benchPsi.ArgumentList.Add('-lc'); $benchPsi.ArgumentList.Add($benchCommand)
    $benchPsi.UseShellExecute = $false; $benchPsi.CreateNoWindow = $true
    $benchPsi.RedirectStandardOutput = $true; $benchPsi.RedirectStandardError = $true
    $benchProcess = [Diagnostics.Process]::Start($benchPsi)
    $benchStdout = $benchProcess.StandardOutput.ReadToEndAsync()
    $benchStderr = $benchProcess.StandardError.ReadToEndAsync()
    $benchLowSamples = 0; $benchTicks = 0; $benchAbort = $null
    try {
      while (-not $benchProcess.WaitForExit(10000)) {
        $benchPower = (Get-CimInstance Win32_Battery).BatteryStatus
        $benchFreeMiB = [math]::Round((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024)
        $benchDiskGiB = [math]::Round((Get-PSDrive C).Free / 1GB, 3)
        "$([DateTimeOffset]::UtcNow.ToString('o')),$benchPower,$benchFreeMiB,$benchDiskGiB" | Add-Content -LiteralPath (Join-Path $benchOut 'host-samples.csv') -Encoding utf8
        if ($benchFreeMiB -lt 768) { $benchLowSamples++ } else { $benchLowSamples = 0 }
        if ($benchPower -ne 2) { $benchAbort = 'power' }
        if ($benchLowSamples -ge 3) { $benchAbort = 'low-memory' }
        if ($benchDiskGiB -lt 2) { $benchAbort = 'low-disk' }
        $benchTicks++
        if ($benchTicks % 6 -eq 0) {
          $benchLatest = if (Test-Path -LiteralPath (Join-Path $benchOut 'run.log')) { Get-Content -LiteralPath (Join-Path $benchOut 'run.log') -Tail 1 } else { 'starting' }
          Write-Output "$Label freeHostMiB=$benchFreeMiB freeDiskGiB=$benchDiskGiB power=$benchPower | $benchLatest"
        }
        if ($benchAbort) { throw "Run aborted: $benchAbort" }
      }
      $benchStdout.Result | Set-Content -LiteralPath (Join-Path $benchOut 'controller-stdout.log') -Encoding utf8
      $benchStderr.Result | Set-Content -LiteralPath (Join-Path $benchOut 'controller-stderr.log') -Encoding utf8
      if ($benchProcess.ExitCode -ne 0) { $benchAbort = 'runner-failed'; throw "Run exited $($benchProcess.ExitCode)" }
      $benchLog = Get-Content -LiteralPath (Join-Path $benchOut 'run.log') -Raw
      if ([regex]::Matches($benchLog, 'simulation .*: ok in').Count -ne 7 -or $benchLog.Contains('warm-up') -and $benchLog.Contains('warm-up ') -and $benchLog.Contains('failed (see warmup')) {
        $benchAbort = 'invalid-suite'; throw 'Suite incomplete or warm-up failed'
      }
      $benchStatsFiles = @(Get-ChildItem -LiteralPath $benchOut -Filter stats.json -Recurse)
      if ($benchStatsFiles.Count -ne 7) { $benchAbort = 'missing-stats'; throw 'Expected seven Gatling reports' }
      foreach ($benchStatsFile in $benchStatsFiles) {
        $benchStats = Get-Content -LiteralPath $benchStatsFile.FullName -Raw | ConvertFrom-Json
        if ([int]$benchStats.stats.numberOfRequests.ko -ne 0) { $benchAbort = 'gatling-ko'; throw "Failed requests: $($benchStatsFile.FullName)" }
      }
      $benchDistributionReport = @(Get-ChildItem -LiteralPath $benchOut -Filter distribution-memory.txt -Recurse)
      $benchConcurrentReport = @(Get-ChildItem -LiteralPath $benchOut -Filter concurrent-distribution.txt -Recurse)
      if ($benchDistributionReport.Count -ne 1 -or $benchConcurrentReport.Count -ne 1) { $benchAbort = 'missing-job-reports'; throw 'Missing round-distribution reports' }
      $benchDistributionText = Get-Content -LiteralPath $benchDistributionReport[0].FullName -Raw
      $benchConcurrentText = Get-Content -LiteralPath $benchConcurrentReport[0].FullName -Raw
      if ([regex]::Matches($benchDistributionText, "step='").Count -ne 4 -or [regex]::Matches($benchDistributionText, 'status=done ok=true').Count -ne 4 -or [regex]::Matches($benchConcurrentText, 'phase=concurrent admin-job=').Count -ne 10 -or -not $benchConcurrentText.Contains('phase=concurrent all-done') -or $benchConcurrentText -match 'status=(failed|cancelled|queued|running)') {
        $benchAbort = 'job-outcomes'; throw 'Incomplete jobs or distribution row-count checks failed'
      }
      $benchExpectedVersion = if ($ExpectedFlywayVersion) { $ExpectedFlywayVersion } else { switch ($Dist) { 'prod' { '53' }; 'pr183v551' { '59' }; 'bgdistv551' { '60' }; default { throw 'New dist names require -ExpectedFlywayVersion' } } }
      $benchHistory = @(Get-Content -LiteralPath (Join-Path $benchOut 'flyway.txt') | ForEach-Object { ,($_ -split "`t") })
      if ($benchHistory[-1][1] -ne $benchExpectedVersion -or @($benchHistory | Where-Object { $_[4] -ne '1' }).Count -gt 0) {
        $benchAbort = 'flyway'; throw "Expected successful Flyway through V$benchExpectedVersion"
      }
      $benchVariables = @{}
      Get-Content -LiteralPath (Join-Path $benchOut 'variables.txt') | ForEach-Object { $benchParts = $_ -split "`t", 2; $benchVariables[$benchParts[0]] = $benchParts[1] }
      $benchExpectedPool = 128MB
      $benchExpectedKey = 128MB
      if ($Cnf -ne '-') {
        foreach ($benchLine in Get-Content -LiteralPath (Join-Path $benchRepo "bench\cnf\$Cnf.cnf")) {
          if ($benchLine -match '^\s*(innodb_buffer_pool_size|key_buffer_size)\s*=\s*(\d+)([KMG])?\s*(?:#.*)?$') {
            $benchConfigKey = $Matches[1]
            $benchConfigFactor = 1
            if ($Matches[3]) { $benchConfigFactor = switch ($Matches[3]) { K { 1KB }; M { 1MB }; G { 1GB } } }
            $benchConfigValue = [long]$Matches[2] * $benchConfigFactor
            if ($benchConfigKey -eq 'innodb_buffer_pool_size') { $benchExpectedPool = $benchConfigValue } else { $benchExpectedKey = $benchConfigValue }
          }
        }
      }
      if ([long]$benchVariables.innodb_buffer_pool_size -ne $benchExpectedPool -or [long]$benchVariables.key_buffer_size -ne $benchExpectedKey) {
        $benchAbort = 'config'; throw 'Effective database buffer sizes do not match the intended point'
      }
      $benchContainer = @(Get-Content -LiteralPath (Join-Path $benchOut 'container-start.json') -Raw | ConvertFrom-Json)[0]
      if ($benchContainer.HostConfig.CpusetCpus -ne '0,1' -or $benchContainer.HostConfig.CpuQuota -ne 0 -or $benchContainer.HostConfig.Memory -ne 4900MB -or $benchContainer.HostConfig.MemorySwap -ne 6900MB) {
        $benchAbort = 'container-limits'; throw 'Container resource limits do not match the runbook'
      }
      $benchConditions.completedUtc = [DateTimeOffset]::UtcNow.ToString('o')
      $benchConditions.validated = $true
      $benchConditions | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $benchOut 'conditions.json') -Encoding utf8
      & node (Join-Path $benchRepo 'bench\scripts\summarize.js') $benchOut | Set-Content -LiteralPath (Join-Path $benchOut 'summary.txt') -Encoding utf8
      if ($LASTEXITCODE -ne 0) { throw 'Summarizer failed' }
      Write-Output "COMPLETE $Label"
      Get-Content -LiteralPath (Join-Path $benchOut 'run.log') -Tail 5
    } catch {
      if (-not $benchAbort) { $benchAbort = 'controller-error' }
      throw
    } finally {
      if (-not $benchProcess.HasExited) { $benchProcess.Kill($true); $benchProcess.WaitForExit() }
      if ($benchAbort) {
        Invoke-BenchBash 'bench/scripts/bench.sh stop'
        $benchDiscard = Join-Path $benchScratch ('results\' + $Label + '-DISCARDED-' + $benchAbort + '-' + [DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))
        $benchResultsPrefix = [IO.Path]::GetFullPath((Join-Path $benchScratch 'results')) + [IO.Path]::DirectorySeparatorChar
        if (-not [IO.Path]::GetFullPath($benchOut).StartsWith($benchResultsPrefix, [StringComparison]::OrdinalIgnoreCase) -or -not [IO.Path]::GetFullPath($benchDiscard).StartsWith($benchResultsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
          throw 'Discard paths escaped the benchmark results directory'
        }
        Move-Item -LiteralPath $benchOut -Destination $benchDiscard
        Write-Output "DISCARDED $benchDiscard"
      }
    }
    if ($Label -eq 'p5-pr183-trim') { Record-BenchPhase 5 @('p3-pr183-innodb','p5-pr183-trim') }
