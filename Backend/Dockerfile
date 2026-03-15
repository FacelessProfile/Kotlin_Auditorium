FROM python:3.10-slim
RUN apt-get update && apt-get install -y \
    libpq-dev \
    gcc \
    cron \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN pip install --no-cache-dir requests beautifulsoup4 psycopg2-binary python-dotenv
COPY . .

COPY crontab /etc/cron.d/sibsutis-cron
RUN chmod 0644 /etc/cron.d/sibsutis-cron && \
    crontab /etc/cron.d/sibsutis-cron

RUN touch /var/log/cron.log
RUN printf "#!/bin/sh\n\
echo 'Starting initial sync...'\n\
python /app/parser.py\n\
echo 'Initial sync done. Starting cron...'\n\
cron -f\n" > /usr/local/bin/entrypoint.sh

RUN chmod +x /usr/local/bin/entrypoint.sh

CMD ["/usr/local/bin/entrypoint.sh"]
