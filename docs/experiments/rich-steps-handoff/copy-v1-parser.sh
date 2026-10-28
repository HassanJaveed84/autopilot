#!/bin/sh
# Copies the shipping v1 parser (unchanged apart from the package) next to the prototype, so the
# tests can compare against what 0.2.0 does. Run from this directory, then: gradle test
set -e
APP=../../../app/src/main/java/com/lifeadmin/autopilot/share/applink
mkdir -p src/main/kotlin/orig
sed 's/^package com.lifeadmin.autopilot.share.applink/package orig/; s/ImportLinkParser.PATH/"\/autopilot\/import"/' $APP/HandoffLink.kt > src/main/kotlin/orig/HandoffLink.kt
sed 's/^package com.lifeadmin.autopilot.share.applink/package orig/' $APP/ImportLink.kt > src/main/kotlin/orig/ImportLink.kt
