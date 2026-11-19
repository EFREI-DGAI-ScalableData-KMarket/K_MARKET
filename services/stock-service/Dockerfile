FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ./app ./app

# opentelemetry-instrument active les traces (FastAPI, httpx, psycopg, logging) sans modifier le code.
# Configuration par les variables OTEL_* de docker-compose.yml.
CMD ["opentelemetry-instrument", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]