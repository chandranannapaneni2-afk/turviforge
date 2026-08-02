#!/bin/sh
# Offline build: javac + jar (no Maven needed). Java 17+.
set -e
cd "$(dirname "$0")"
rm -rf build dist && mkdir -p build/classes build/resources/ui dist
javac -d build/classes $(find reportforge-core reportforge-cli reportforge-testdata -name '*.java')
cp reportforge-ui/src/app.js reportforge-ui/src/app.css reportforge-ui/vendor/echarts.min.js build/resources/ui/
cp -r build/resources/ui build/classes/
printf 'Main-Class: io.forge.reportforge.cli.Main\n' > build/MANIFEST.MF
jar cfm dist/reportforge-cli.jar build/MANIFEST.MF -C build/classes .
echo "built dist/reportforge-cli.jar"
