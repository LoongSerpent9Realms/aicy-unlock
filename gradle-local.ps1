$ErrorActionPreference = "Stop"
$dist = "C:\Users\24951\.gradle\wrapper\dists\gradle-9.3.1-bin\23ovyewtku6u96viwx3xl3oks\gradle-9.3.1"
$lib = Join-Path $dist "lib"
$launcher = Join-Path $lib "gradle-launcher-9.3.1.jar"
if (-not (Test-Path $launcher)) { throw "launcher not found: $launcher" }

$tmpDir = Join-Path $env:TEMP "gradle-mf"
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

# The Gradle classpath blows past both the Windows command-line limit and the
# 72-byte manifest line limit, so the launcher reads the classpath from a file
# and builds a URLClassLoader at runtime.
$jars = Get-ChildItem $lib -Filter *.jar -Recurse | Select-Object -ExpandProperty FullName
$cpFile = Join-Path $tmpDir "cp.txt"
[System.IO.File]::WriteAllLines($cpFile, $jars, (New-Object System.Text.UTF8Encoding($false)))

$javaSrc = Join-Path $tmpDir "GradleBoot.java"
$lines = @(
    "import java.io.*;",
    "import java.net.*;",
    "import java.util.*;",
    "public class GradleBoot {",
    "  public static void main(String[] a) throws Exception {",
    "    List<URL> urls = new ArrayList<URL>();",
    "    BufferedReader r = new BufferedReader(new InputStreamReader(",
    "        new FileInputStream(a[0]), ""UTF-8""));",
    "    String line;",
    "    while ((line = r.readLine()) != null) {",
    "      if (line.length() > 0) urls.add(new File(line).toURI().toURL());",
    "    }",
    "    r.close();",
    "    URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]),",
    "        ClassLoader.getSystemClassLoader().getParent());",
    "    Thread.currentThread().setContextClassLoader(cl);",
    "    Class<?> m = cl.loadClass(""org.gradle.launcher.GradleMain"");",
    "    m.getMethod(""main"", String[].class).invoke(null,",
    "        (Object) Arrays.copyOfRange(a, 1, a.length));",
    "    System.exit(0);",
    "  }",
    "}"
)
[System.IO.File]::WriteAllLines($javaSrc, $lines, (New-Object System.Text.UTF8Encoding($false)))

$jdkBin = "C:\Program Files\Android\Android Studio\jbr\bin"
& "$jdkBin\javac.exe" -d $tmpDir $javaSrc
if (-not (Test-Path (Join-Path $tmpDir "GradleBoot.class"))) { throw "javac failed" }

$argList = @(
    "-Xmx2g",
    "-Dorg.gradle.jvmargs=-Xmx2g",
    "-Dhttp.nonProxyHosts=localhost|127.*|[::1]|*",
    "-Dhttps.nonProxyHosts=localhost|127.*|[::1]|*",
    "-cp", $tmpDir,
    "GradleBoot",
    $cpFile
) + $args

$out = Join-Path $env:TEMP "gl_out.txt"
$err = Join-Path $env:TEMP "gl_err.txt"
$proc = Start-Process -FilePath "$jdkBin\java.exe" -ArgumentList $argList -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $out -RedirectStandardError $err
Get-Content $out -Tail 45 -ErrorAction SilentlyContinue
Get-Content $err -Tail 25 -ErrorAction SilentlyContinue
exit $proc.ExitCode