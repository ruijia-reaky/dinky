FROM flink:1.18.1-scala_2.12-java11
USER root
RUN mv /opt/flink/bin/flink-console.sh /opt/flink/bin/flink-console-original.sh \
    && rm -f /opt/flink/lib/flink-table-planner-loader-*.jar
COPY flink-run-logs.sh /opt/flink/bin/flink-console.sh
COPY runtime-libs/ /opt/flink/lib/
COPY usrlib/ /opt/flink/usrlib/
COPY log4j-console.properties /opt/flink/conf/log4j-console.properties
COPY log4j-console.properties /opt/flink/conf/log4j.properties
RUN sed -i 's/\r$//' /opt/flink/bin/flink-console.sh \
    && chmod 755 /opt/flink/bin/flink-console.sh \
    && chmod -R a+rX /opt/flink/usrlib
USER flink
