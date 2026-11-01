#!/usr/bin/env python3
"""Isolated javac/JVM smoke, never Gradle. Outputs and downloaded dependencies stay in --output."""
import argparse
import hashlib
from pathlib import Path
import struct
import subprocess
import urllib.request


parser = argparse.ArgumentParser()
parser.add_argument("--source-root", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--android-jar", type=Path, required=True)
parser.add_argument("--artifact-jar", type=Path, help="Test an extracted release classes.jar instead of compiling sources")
parser.add_argument("--mockable-android-jar", type=Path, help="Also run real session flows using AGP's return-default-values Android JAR")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
manifest = []


def dependency(group, artifact, version):
    relative = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"
    target = args.output / "dependencies" / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/" + relative, target)
    manifest.append(f"{hashlib.sha256(target.read_bytes()).hexdigest()}  {relative}")
    return target


def run(command):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), check=True)


def classpath(paths):
    return ":".join(map(str, paths))


annotations = dependency("org.jetbrains", "annotations", "13.0")
# AndroidX artifacts are supplied by the existing Android build cache, not Maven Central.
androidx = list((Path.home() / ".gradle/caches/modules-2/files-2.1/androidx.annotation/annotation/1.1.0").glob("**/*.jar"))
if len(androidx) != 1:
    raise SystemExit("Expected cached androidx.annotation:annotation:1.1.0")
kotlin = dependency("org.jetbrains.kotlin", "kotlin-stdlib", "1.3.41")
compile_dependencies = [dependency("com.squareup.okhttp3", "okhttp", "4.1.0"),
                        dependency("com.squareup.okio", "okio", "2.2.2"), kotlin,
                        annotations, androidx[0], args.android_jar]
if args.artifact_jar:
    import zipfile
    classes = args.artifact_jar
    with zipfile.ZipFile(classes) as archive:
        majors = {struct.unpack(">H", archive.read(name)[6:8])[0]
                  for name in archive.namelist() if name.endswith(".class")}
else:
    classes = args.output / "classes"
    classes.mkdir(exist_ok=True)
    sources = sorted((args.source_root / "app/src/main/java").rglob("*.java"))
    run([args.java_home / "bin/javac", "-source", "7", "-target", "7", "-classpath",
         classpath(compile_dependencies), "-d", classes, *sources])
    majors = {struct.unpack(">H", path.read_bytes()[6:8])[0] for path in classes.rglob("*.class")}
if majors != {51}:
    raise SystemExit(f"Unexpected production class versions: {majors}")
print("PASS production class majors: 51", flush=True)
tests = args.output / "probe-classes"
tests.mkdir(exist_ok=True)
run([args.java_home / "bin/javac", "-source", "8", "-target", "8", "-classpath",
     classpath([classes, *compile_dependencies]), "-d", tests,
     Path(__file__).with_name("RuntimeCompatibilityProbe.java")])
if args.mockable_android_jar:
    json = dependency("org.json", "json", "20231013")
    run([args.java_home / "bin/javac", "-source", "8", "-target", "8", "-classpath",
         classpath([classes, json, *compile_dependencies]), "-d", tests,
         Path(__file__).with_name("HistoricalSessionProbe.java"),
         args.source_root / "app/src/test/java/com/supertokens/session/TestPreferences.java",
         args.source_root / "app/src/test/java/android/util/Base64.java"])
for okhttp, okio, typed, owned in [
        ("3.3.0", "1.8.0", False, False),
        ("3.10.0", "1.14.0", False, False),
        ("3.11.0", "1.14.0", True, False),
        ("3.12.13", "1.15.0", True, False),
        ("3.13.0", "1.17.2", True, False),
        ("3.14.0", "1.17.2", True, True),
        ("4.1.0", "2.2.2", True, True)]:
    runtime = [tests, classes, dependency("com.squareup.okhttp3", "okhttp", okhttp),
               dependency("com.squareup.okio", "okio", okio), annotations, args.android_jar]
    if okhttp.startswith("4."):
        runtime.append(kotlin)
    print("Runtime:", okhttp, "Okio:", okio, flush=True)
    run([args.java_home / "bin/java", "-Xverify:all", "-classpath", classpath(runtime),
         "com.supertokens.session.RuntimeCompatibilityProbe", str(typed).lower(), str(owned).lower()])
    if args.mockable_android_jar:
        session_runtime = [path for path in runtime if path != args.android_jar]
        session_runtime.extend([json, args.mockable_android_jar])
        run([args.java_home / "bin/java", "-Xverify:all", "-classpath", classpath(session_runtime),
             "com.supertokens.session.HistoricalSessionProbe"])
(args.output / "dependencies.sha256").write_text("\n".join(sorted(set(manifest))) + "\n")
