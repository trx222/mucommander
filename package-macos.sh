#!/bin/sh
# macOS-only (arm64) packaging, extracted from release.sh.
# Builds dist/macos-arm64/trolCommander.app and dist/trolCommander-arm64-$VERSION.dmg
set -e

VERSION='1.0.0'
JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
JPACKAGE="$JAVA_HOME/bin/jpackage"

OUT_PATH=dist
TMP_OUT_PATH_MAC=dist/macos/jar
TMP_RES_PATH=dist/resources

MAIN_CLASS="com.mucommander.TrolCommander"
CLASS_LOADER="com.mucommander.commons.file.AbstractFileClassLoader"
APP_NAME="trolCommander"
ARCH="arm64"

mkdir -p "$TMP_OUT_PATH_MAC" "$TMP_RES_PATH"

# ------- macos jar: strip non-mac native libs -------
cp build/libs/trolcommander-$VERSION.jar $TMP_OUT_PATH_MAC/trolcommander-macosx.jar
zip -d $TMP_OUT_PATH_MAC/trolcommander-macosx.jar "Windows-amd64/*" "Windows-x86/*" "Linux-amd64/*" "Linux-i386/*" "win/*" "linux/*" "jtermios/freebsd/*" "jtermios/linux/*" "jtermios/solaris/*" "jtermios/windows/*" || true
zip -d $TMP_OUT_PATH_MAC/trolcommander-macosx.jar "com/sun/jna/freebsd-amd64/*" "com/sun/jna/freebsd-i386/*" "com/sun/jna/linux-amd64/*" "com/sun/jna/linux-arm/*" "com/sun/jna/linux-i386/*" "com/sun/jna/linux-ia64/*" "com/sun/jna/linux-ppc/*" "com/sun/jna/linux-ppc64/*" "com/sun/jna/platform/win32/*" "com/sun/jna/platform/wince/*" "com/sun/jna/sunos-amd64/*" "com/sun/jna/sunos-sparc/*" "com/sun/jna/sunos-sparcv9/*" "com/sun/jna/sunos-x86/*" "com/sun/jna/w32ce-arm/*" "com/sun/jna/win32/*" "com/sun/jna/win32-amd64/*" "com/sun/jna/win32-x86/*" || true

JVM_OPENS="--add-opens java.desktop/javax.swing.plaf.basic=ALL-UNNAMED\
 --add-opens java.base/java.io=ALL-UNNAMED\
 --add-opens java.base/java.net=ALL-UNNAMED\
 --add-opens java.transaction.xa/javax.transaction.xa=ALL-UNNAMED\
 --add-opens java.management/javax.management=ALL-UNNAMED\
 --add-opens java.rmi/java.rmi=ALL-UNNAMED\
 --add-opens java.security.jgss/org.ietf.jgss=ALL-UNNAMED\
 --add-opens java.sql/java.sql=ALL-UNNAMED\
 --add-opens java.base/sun.net.www.protocol.http=ALL-UNNAMED\
 --add-opens java.base/sun.net.www.protocol.https=ALL-UNNAMED\
 --add-opens java.compiler/javax.lang.model.element=ALL-UNNAMED"

JVM_OPENS_APPLE="--add-opens java.desktop/com.apple.eawt=ALL-UNNAMED\
 --add-opens java.desktop/com.apple.laf=ALL-UNNAMED\
 --add-opens java.desktop/com.apple.eio=ALL-UNNAMED\
 --add-opens java.desktop/com.apple.laf.AquaLookAndFeel=ALL-UNNAMED"

JVM_PARAMS="-Xmx128m -Xms128m -Dfile.encoding=UTF-8 -XX:ReservedCodeCacheSize=64m -XX:+IgnoreUnrecognizedVMOptions"
JVM_LOGING="-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider"
JVM_OPTS="$JVM_OPENS $JVM_OPENS_APPLE $JVM_PARAMS $JVM_LOGING -Djava.system.class.loader=$CLASS_LOADER -Dlog.stdout=false"

cp res/package/osx/icon.icns $TMP_RES_PATH/trolCommander.icns

rm -rf "${OUT_PATH}/macos-$ARCH"
"$JPACKAGE" --input "$TMP_OUT_PATH_MAC/" \
         --name $APP_NAME \
         --app-version $VERSION \
         --main-jar trolcommander-macosx.jar \
         --main-class $MAIN_CLASS \
         --resource-dir "$TMP_RES_PATH" \
         --java-options "$JVM_OPTS" \
         --type app-image \
         --dest "${OUT_PATH}/macos-$ARCH"

rm -f $OUT_PATH/trolCommander-$VERSION.dmg
"$JPACKAGE" --input "$TMP_OUT_PATH_MAC/" \
         --name $APP_NAME \
         --app-version $VERSION \
         --main-jar trolcommander-macosx.jar \
         --main-class $MAIN_CLASS \
         --resource-dir "$TMP_RES_PATH" \
         --java-options "$JVM_OPTS" \
         --type dmg \
         --dest $OUT_PATH

mv $OUT_PATH/trolCommander-$VERSION.dmg $OUT_PATH/trolCommander-$ARCH-$VERSION.dmg

echo "OK: ${OUT_PATH}/macos-$ARCH/$APP_NAME.app"
echo "OK: $OUT_PATH/trolCommander-$ARCH-$VERSION.dmg"
