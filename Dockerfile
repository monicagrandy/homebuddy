FROM python:3.12-slim

ARG PRESIDIO_SPACY_MODEL=en_core_web_sm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PRESIDIO_SPACY_MODEL=${PRESIDIO_SPACY_MODEL}

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY requirements-lock.txt ./requirements-lock.txt

RUN pip install --upgrade pip \
    && pip install -r requirements-lock.txt \
    && python -m spacy download ${PRESIDIO_SPACY_MODEL}

COPY . .

RUN useradd --create-home --shell /bin/bash appuser \
    && chown -R appuser:appuser /app

USER appuser

EXPOSE 8000 8501
