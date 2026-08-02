#!/bin/sh
# Offline build: javac + jar (no Maven needed). Java 17+.
set -e
cd "$(dirname "$0")"
rm -rf build dist && mkdir -p build/classes build/resources/ui dist
javac -d build/classes $(find turviforge-core turviforge-cli turviforge-testdata -name '*.java')
cp turviforge-ui/src/app.js turviforge-ui/src/app.css turviforge-ui/vendor/echarts.min.js build/resources/ui/
cp -r build/resources/ui build/classes/
printf 'Main-Class: io.forge.turviforge.cli.Main\n' > build/MANIFEST.MF
jar cfm dist/turviforge-cli.jar build/MANIFEST.MF -C build/classes .
echo "built dist/turviforge-cli.jar"
