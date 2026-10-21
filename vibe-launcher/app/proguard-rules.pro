# R8 rules for the release build. Nothing here looks classes up by name: the activity, receiver and
# screensaver in the manifest and the views in the layouts are kept by the rules the build makes itself.

# Line numbers in stack traces (with the build's mapping.txt, the real names too).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
