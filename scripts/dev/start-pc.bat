@echo off
chcp 65001 >nul
cd /d "%~dp0..\.."

rem 跨库维护账号（由 sql/db-maintenance-user.sql 创建）
if "%DB_MAINT_USERNAME%"=="" set DB_MAINT_USERNAME=jqgg_maint_user
if "%DB_MAINT_PASSWORD%"=="" set DB_MAINT_PASSWORD=

echo 启动公路广告牌租赁定位系统（PC 端）...
echo   前台: http://localhost:8080/
echo   后台: http://localhost:8080/admin
rem 明确告诉应用“项目根目录”，避免从其他工作目录启动时把上传文件写错位置
java -Dapp.home="%cd%" -jar deploy\app.jar
pause
