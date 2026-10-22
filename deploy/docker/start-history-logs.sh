#!/bin/sh
set -eu
cd /opt/dinky
classpath='/opt/dinky:/opt/dinky/config:/opt/dinky/lib/*:/opt/dinky/extends/*:/opt/dinky/extends/flink1.18/dinky/*:/opt/dinky/extends/flink1.18/flink/*:/opt/dinky/extends/flink1.18/*:/opt/dinky/customJar/*'
if [ -n "${DINKY_EXTRA_CLASSPATH:-}" ]; then
    classpath="$DINKY_EXTRA_CLASSPATH:$classpath"
fi
exec java -Ddinky.root.path=/opt/dinky -Ddinky.logs.path=/opt/dinky/logs \
    -Dlog4j2.isThreadContextMapInheritable=true -cp "$classpath" org.dinky.Dinky "$@"
