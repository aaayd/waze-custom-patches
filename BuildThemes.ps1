param([string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk", [string]$Jdk = 'C:\Program Files\Android\Android Studio\jbr')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$env:JAVA_HOME = $Jdk
$desktop = Join-Path $PSScriptRoot 'tools\morphe-desktop.jar'
if ((Get-FileHash $desktop -Algorithm SHA256).Hash -ne '36E20D7A18F655FB5829AE50AADD61217E2208536C0741DF5F7799300F758F56') { throw 'Unexpected Morphe Desktop dependency.' }
$androidJar = "$AndroidSdk\platforms\android-36\android.jar"
$d8 = "$AndroidSdk\build-tools\36.0.0\d8.bat"
New-Item -ItemType Directory -Force build\themes-dex,build\themes-extension-classes,build\themes-extension-dex,build\themes-bundle\extensions,build\themes-bundle\installer,dist | Out-Null
if ((Get-FileHash dist\waze-aa-installer-1.0.0.apk -Algorithm SHA256).Hash -ne 'E5D442454418EFD4FF438B3ED3F6BFA5A3AEE78F52616625CB25CE0EC8855B48') { throw 'Unexpected companion installer APK.' }
Copy-Item dist\waze-aa-installer-1.0.0.apk build\themes-bundle\installer\waze-aa-installer.apk
& "$Jdk\bin\javac.exe" --release 11 -cp $androidJar -d build\themes-extension-classes theme-extension\src\local\wazemaps\themes\*.java
if ($LASTEXITCODE) { throw 'Theme extension compilation failed.' }
& "$Jdk\bin\jar.exe" cf build\themes-extension.jar -C build\themes-extension-classes .
if ($LASTEXITCODE) { throw 'Theme extension archive failed.' }
& $d8 --release --min-api 29 --lib $androidJar --output build\themes-extension-dex build\themes-extension.jar
if ($LASTEXITCODE) { throw 'Theme extension DEX failed.' }
Copy-Item build\themes-extension-dex\classes.dex build\themes-bundle\extensions\theme-selector.dex
New-Item -ItemType Directory -Force build\themes-badge-classes,build\themes-badge-dex | Out-Null
& "$Jdk\bin\javac.exe" --release 11 -cp $androidJar -d build\themes-badge-classes badge-extension\src\local\wazemaps\badges\BadgeSelector.java
if ($LASTEXITCODE) { throw 'Badge extension compilation failed.' }
& "$Jdk\bin\jar.exe" cf build\themes-badge-extension.jar -C build\themes-badge-classes .
if ($LASTEXITCODE) { throw 'Badge extension archive failed.' }
& $d8 --release --min-api 29 --lib $androidJar --output build\themes-badge-dex build\themes-badge-extension.jar
if ($LASTEXITCODE) { throw 'Badge extension DEX failed.' }
Copy-Item build\themes-badge-dex\classes.dex build\themes-bundle\extensions\badge-selector.dex
& .\gradlew.bat themesJar --console=plain
if ($LASTEXITCODE) { throw 'Theme patch compilation failed.' }
& $d8 --release --min-api 26 --lib $androidJar --classpath $desktop --output build\themes-dex build\libs\waze-theme-selector-1.8.0.jar
if ($LASTEXITCODE) { throw 'Theme patch DEX failed.' }
Copy-Item build\libs\waze-theme-selector-1.8.0.jar dist\waze-theme-selector-1.8.0.mpp
& "$Jdk\bin\jar.exe" uf dist\waze-theme-selector-1.8.0.mpp -C build\themes-dex classes.dex -C build\themes-bundle extensions -C build\themes-bundle installer
if ($LASTEXITCODE) { throw 'Theme patch packaging failed.' }
& "$Jdk\bin\java.exe" -jar $desktop list-patches --patches dist\waze-theme-selector-1.8.0.mpp -pv
if ($LASTEXITCODE) { throw 'Morphe could not load the theme patch.' }
