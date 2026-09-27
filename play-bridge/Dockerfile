FROM python:3.12-slim
ARG GPLAYDL_COMMIT=7c3d87f20a5f61f6cfa7446683bb4c698bfa2396
RUN pip install --no-cache-dir "https://github.com/rehmatworks/gplaydl/archive/${GPLAYDL_COMMIT}.tar.gz"
WORKDIR /app
COPY server.py /app/server.py
RUN useradd --system --uid 10001 --home /data playbridge && mkdir -p /data && chown playbridge:playbridge /data
USER playbridge
EXPOSE 8787
CMD ["python", "/app/server.py"]
