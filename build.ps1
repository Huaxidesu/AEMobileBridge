$ErrorActionPreference = "Stop"

$api = $env:BC_API_JAR
if (-not $api) { $api = "D:\Game\Minecraft\Server\BC\HexaCord-v148.jar" }
if (-not (Test-Path -LiteralPath $api)) {
  throw "BC API jar not found: $api (set BC_API_JAR)"
}
if (-not $env:JAVA_HOME) {
  $env:JAVA_HOME = "D:/Library/DevelopmentKit/Java/Zulu/zulu8"
}

if (Test-Path -LiteralPath "build") { Remove-Item -LiteralPath "build" -Recurse -Force }
New-Item -ItemType Directory -Force build\classes | Out-Null

$ErrorActionPreference = "Continue"
& "$env:JAVA_HOME\bin\javac.exe" -source 8 -target 8 -encoding UTF-8 -cp $api `
  -d build\classes src\main\java\dev\youtiao\aemobile\bridge\*.java 2>&1 | Out-Null
$code = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($code -ne 0) { throw "javac failed" }

& "$env:JAVA_HOME\bin\jar.exe" cf AEMobileBridge.jar -C build\classes . -C src\main\resources .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Output "built: AEMobileBridge.jar"
