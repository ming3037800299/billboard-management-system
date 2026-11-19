# 推送到 GitHub（走 FClash 本地代理 7890）
# 用法：powershell -ExecutionPolicy Bypass -File push-to-github.ps1
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# 确保代理生效
git config --local http.proxy  http://127.0.0.1:7890
git config --local https.proxy http://127.0.0.1:7890

Write-Output "Remote: $(git remote get-url origin)"
Write-Output "Commits: $(git rev-list --count HEAD)"
Write-Output ""

git push -u origin main
if ($LASTEXITCODE -ne 0) { throw "main 推送失败" }

git push -u origin develop
if ($LASTEXITCODE -ne 0) { throw "develop 推送失败" }

Write-Output ""
Write-Output "推送完成：https://github.com/ming3037800299/billboard-management-system"
