#!/bin/sh
# Compile the pure-Java maths and run the laptop tests. Needs a JDK (Android Studio's bundled one works).
set -e
cd "$(dirname "$0")/.."
JBIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
mkdir -p tests/out
"${JBIN}javac" -d tests/out android/app/src/main/java/com/locus/trio/TrioMath.java android/app/src/main/java/com/locus/trio/TrioMesh.java tests/TrioTest.java
"${JBIN}java" -cp tests/out TrioTest
