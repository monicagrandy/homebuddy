FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /loadtests

COPY requirements-loadtest.txt /tmp/requirements-loadtest.txt

RUN pip install --upgrade pip \
    && pip install -r /tmp/requirements-loadtest.txt

ENTRYPOINT ["locust", "-f", "/loadtests/locustfile.py", "--host", "http://backend:8000"]
