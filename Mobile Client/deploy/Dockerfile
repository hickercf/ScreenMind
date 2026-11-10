FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY ["Cloud Agent/pyproject.toml", "/opt/cloud/pyproject.toml"]
COPY ["Cloud Agent/screenmind", "/opt/cloud/screenmind"]
COPY ["Mobile Client/backend/requirements.txt", "/app/requirements.txt"]
RUN pip install --no-cache-dir -r requirements.txt /opt/cloud \
    && useradd --uid 10001 --create-home appuser \
    && mkdir /data && chown appuser:appuser /data
COPY ["Mobile Client/backend", "/app/backend"]
USER appuser
EXPOSE 8765
CMD ["python", "-m", "uvicorn", "backend.main:app", "--host", "0.0.0.0", "--port", "8765", "--workers", "1", "--no-proxy-headers"]
