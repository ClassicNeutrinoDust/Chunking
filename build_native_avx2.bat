@echo off
setlocal enabledelayedexpansion

set "ROOT_DIR=%~dp0"
if "%ROOT_DIR:~-1%"=="\" set "ROOT_DIR=%ROOT_DIR:~0,-1%"
if not exist "%ROOT_DIR%\src\main\native\chunking_avx2.cpp" if exist "%ROOT_DIR%\Fabric\MC-26.2\src\main\native\chunking_avx2.cpp" set "ROOT_DIR=%ROOT_DIR%\Fabric\MC-26.2"

set "SRC=%ROOT_DIR%\src\main\native\chunking_avx2.cpp"
set "OUT_DIR=%ROOT_DIR%\src\main\resources\natives\windows-x64"
set "OUT_DLL=%OUT_DIR%\chunking_avx2.dll"
set "TMP_BUILD=%TEMP%\chunking_avx2_build_%RANDOM%"

if not exist "%SRC%" (
    echo [ERROR] Source not found: "%SRC%"
    exit /b 1
)
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

where cl.exe >nul 2>&1 && goto :build_msvc

set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if exist "%VSWHERE%" (
    for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_PATH=%%i"
    if defined VS_PATH if exist "!VS_PATH!\VC\Auxiliary\Build\vcvars64.bat" (
        call "!VS_PATH!\VC\Auxiliary\Build\vcvars64.bat" >nul
        where cl.exe >nul 2>&1 && goto :build_msvc
    )
)

where clang++ >nul 2>&1 && goto :build_clang
where g++ >nul 2>&1 && goto :build_mingw

echo [ERROR] No C++ compiler found (MSVC cl.exe, clang++, or MinGW g++).
exit /b 1

:build_msvc
mkdir "%TMP_BUILD%" >nul 2>&1
cl.exe /nologo /LD /O2 /Oi /Ot /arch:AVX2 /fp:precise /EHsc /MD ^
    /Fo:"%TMP_BUILD%\chunking_avx2.obj" /Fe:"%OUT_DLL%" "%SRC%" ^
    /link /IMPLIB:"%TMP_BUILD%\chunking_avx2.lib" /OUT:"%OUT_DLL%"
set "RC=%errorlevel%"
rmdir /s /q "%TMP_BUILD%" >nul 2>&1
goto :done

:build_clang
clang++ -shared -O3 -mavx2 -ffp-contract=off -fno-fast-math -std=c++17 -o "%OUT_DLL%" "%SRC%"
set "RC=%errorlevel%"
goto :done

:build_mingw
g++ -shared -O3 -mavx2 -ffp-contract=off -fno-fast-math -std=c++17 -static-libgcc -static-libstdc++ -Wl,--export-all-symbols -o "%OUT_DLL%" "%SRC%"
set "RC=%errorlevel%"

:done
if not "%RC%"=="0" (
    echo [ERROR] Build failed with code %RC%.
    exit /b %RC%
)
for %%F in ("%OUT_DLL%") do echo [Chunking AVX2] Built "%OUT_DLL%" (%%~zF bytes).
exit /b 0
