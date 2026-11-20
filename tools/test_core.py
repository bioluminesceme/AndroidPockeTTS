"""Run Kotlin reader tests using a local Maven-layout compiler cache."""

import argparse
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_android import ROOT, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven-cache", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    coordinates = [("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.3.10"),
                   ("org.jetbrains.kotlin", "kotlin-stdlib", "2.3.10"),
                   ("org.jetbrains.kotlin", "kotlin-script-runtime", "2.3.10"),
                   ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                   ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", "2.3.10"),
                   ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
                   ("org.jetbrains", "annotations", "13.0")]
    jars = [args.maven_cache.resolve() / group.replace(".", "/") / name / version / f"{name}-{version}.jar"
            for group, name, version in coordinates]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(jar)
    output = ROOT / "android/build/core-tests.jar"
    output.parent.mkdir(parents=True, exist_ok=True)
    modules = ("AudioOrder", "ThermalReading", "TextFile", "PlaybackText", "BookImport", "ReaderState", "PhraseSearch",
               "VoiceTrash", "PlaybackSettings", "VoiceFavorites", "ActivityWorkSchedule")
    sources = [ROOT / "android/app/src/main/java/nl/user/pockettest" / f"{name}.kt" for name in modules]
    sources.extend(sorted((ROOT / "android/core-tests").glob("*.kt")))
    environment = os.environ.copy()
    environment.pop("VIRTUAL_ENV", None)
    java = str(args.java_home.resolve() / "bin/java.exe")
    run([java, "-cp", os.pathsep.join(map(str, jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-Werror",
         "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", str(jars[1]), "-d", str(output),
         *map(str, sources)], environment)
    run([java, "-Djavax.xml.accessExternalDTD=", "-cp", str(output) + os.pathsep + str(jars[1]),
         "nl.user.pockettest.AudioOrderTestsKt"], environment)
    print("PASS: Kotlin reader tests; exit code 0 and no warnings")


if __name__ == "__main__":
    main()
