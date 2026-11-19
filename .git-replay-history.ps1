# Replay git history based on each file's real last-write time.
# One-off script; safe to delete after execution.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

$buckets = Get-Content "$env:TEMP\jqgg_buckets.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$messages = Get-Content (Join-Path $root '.git-messages.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$keys = @($buckets.PSObject.Properties.Name | Sort-Object)

foreach ($key in $keys) {
  $files = @($buckets.$key)
  git reset -q
  foreach ($f in $files) { git add -- $f }
  $msg = [string]$messages.$key
  if ([string]::IsNullOrWhiteSpace($msg)) { $msg = "chore: update files at $key" }
  $date = "$key`:00:00 +0800"
  $env:GIT_AUTHOR_DATE = $date
  $env:GIT_COMMITTER_DATE = $date
  git commit --no-verify -m $msg -q
  if ($LASTEXITCODE -ne 0) { throw "commit failed: $key" }
  Write-Output ("committed {0}  {1} files" -f $key, $files.Count)
}
Remove-Item Env:\GIT_AUTHOR_DATE, Env:\GIT_COMMITTER_DATE -ErrorAction SilentlyContinue
Write-Output ("DONE total={0}" -f (git rev-list --count HEAD))
