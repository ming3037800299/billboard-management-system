# Push to GitHub via FClash local proxy 7890
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

git config --local http.proxy  http://127.0.0.1:7890
git config --local https.proxy http://127.0.0.1:7890

Write-Output "Remote: $(git remote get-url origin)"
Write-Output "Commits: $(git rev-list --count HEAD)"

git push -u origin main
if ($LASTEXITCODE -ne 0) { throw "push main failed" }

git push -u origin develop
if ($LASTEXITCODE -ne 0) { throw "push develop failed" }

Write-Output "PUSH DONE"
