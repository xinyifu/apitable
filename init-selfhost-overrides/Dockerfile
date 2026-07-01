FROM alpine:3.20

RUN apk add --no-cache mariadb-client

COPY apply-overrides.sh /usr/local/bin/apply-overrides.sh
COPY sql /overrides/sql

RUN sed -i 's/\r$//' /usr/local/bin/apply-overrides.sh && chmod +x /usr/local/bin/apply-overrides.sh

ENTRYPOINT ["apply-overrides.sh"]
