@echo off
rem Starts the vendor portal on this computer: http://localhost:8090  (stop with Ctrl+C)
rem Settings come from application-dev.properties and the .env file in this folder (see .env.example):
rem the vendor_portal database on your local MySQL, Mailjet for sign-in codes, Paystack test key.
rem Running VendorPortalApplication from IntelliJ does exactly the same.
setlocal
cd /d "%~dp0"

if not exist ".env" echo   No .env file here: copy .env.example to .env and fill it in.

set "MVN=mvn"
where mvn >nul 2>nul || set "MVN=%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.5-bin\2adeog8mj13csp1uusqnc1f2mo\apache-maven-3.9.5\bin\mvn.cmd"

echo.
echo   Vendor portal: http://localhost:8090
echo   Sign in with your email; the code is emailed and also printed in this window as [VENDOR-LOGIN].
echo.
call "%MVN%" spring-boot:run
