# -*- mode: makefile -*-

# Build an executable JAR
build:
	lein uberjar
	echo '#!/usr/bin/env -S java -jar' > bin/genko
	cat target/genko-0.1.0-SNAPSHOT-standalone.jar >> bin/genko
	chmod +x bin/genko
