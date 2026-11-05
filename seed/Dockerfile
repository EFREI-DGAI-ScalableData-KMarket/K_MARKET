FROM python:3.13-slim

WORKDIR /seed
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY seed.py showcase.json ./

ENTRYPOINT ["python", "seed.py"]
