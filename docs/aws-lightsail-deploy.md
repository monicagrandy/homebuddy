# HomeBuddy Deployment Guide for AWS Lightsail

This is the lowest-friction AWS path for HomeBuddy as of July 30, 2026.

HomeBuddy now assumes Cognito is always enabled, so the first usable deployment should be treated as a domain + HTTPS + Cognito setup, not an IP-only smoke test.

Why this path:

- HomeBuddy is a multi-process Python app with Streamlit, FastAPI, and Postgres + pgvector.
- A single AWS VM is the cheapest reasonable place to start.
- Lightsail keeps pricing predictable and is simpler than piecing together EC2, RDS, load balancers, and networking on day one.

## Recommended starter architecture

- 1 Lightsail Linux instance
- Docker Compose running:
  - `backend` for FastAPI
  - `frontend` for Streamlit, which is switched off: it only starts with `--profile web-app` (see "The public demo" below)
  - `db` using PostgreSQL with `pgvector`
  - `caddy` as the reverse proxy
- 1 static IP
- 1 custom domain for HTTPS and Cognito callback URLs

## Expected monthly cost

Rough starter budget:

- Lightsail 2 GB instance: about `$12/month`
- Lightsail static IP: included with the instance plan
- Cognito: effectively `$0` for a small portfolio app if you stay inside the free tier
- OpenAI usage: variable, and likely your biggest ongoing cost

Avoid these at the beginning:

- Lightsail managed database: starts around `$15/month`
- Lightsail load balancer: `$18/month`
- RDS: better durability, but more cost and more moving parts than you need for a first portfolio deploy

## Why not App Runner?

AWS says App Runner stopped accepting new customers on April 30, 2026. For a brand-new AWS deployment, I would not build on it now.

## Sizing recommendation

Use the `2 GB` Lightsail plan first.

Why not the `1 GB` plan:

- Streamlit
- FastAPI
- Postgres
- spaCy / Presidio safety checks

All on one box is likely too tight at `1 GB`, even if it boots.

## Before you touch AWS

Make sure your local `.env` has these values set:

- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `OPENAI_WORKFLOW_MODEL`
- `TESTING_OPENAI_MODEL`
- `OPENAI_MAX_RETRIES`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `COGNITO_APP_CLIENT_ID`
- `COGNITO_ISSUER`
- `COGNITO_JWKS_URL`
- `COGNITO_DOMAIN`
- `COGNITO_REDIRECT_URI`
- `COGNITO_LOGOUT_REDIRECT_URI`
- `COGNITO_ALLOWED_GROUPS`
- `PRESIDIO_SPACY_MODEL`
- `WARM_RUNTIME_ON_STARTUP`

Good starter values:

```env
OPENAI_MODEL=gpt-4o
OPENAI_WORKFLOW_MODEL=gpt-4o-mini
TESTING_OPENAI_MODEL=gpt-4o-mini
OPENAI_MAX_RETRIES=2
POSTGRES_DB=home_buddy
POSTGRES_USER=homebuddy
POSTGRES_PASSWORD=replace-this-with-a-long-random-password
PRESIDIO_SPACY_MODEL=en_core_web_sm
WARM_RUNTIME_ON_STARTUP=true
LANGCHAIN_TRACING_V2=false
```

## Step 1: Create the Lightsail instance

In the AWS console:

1. Open Amazon Lightsail.
2. Create a new instance.
3. Choose a Linux/Unix blueprint.
4. Pick Ubuntu 24.04 LTS or another current Ubuntu LTS release.
5. Choose the `2 GB` plan.
6. Name it something like `homebuddy-prod`.

Then:

1. Create and attach a static IP.
2. Open the networking tab.
3. Allow inbound traffic on:
   - `22` for SSH
   - `80` for HTTP
   - `443` for HTTPS

## Step 2: Connect to the server

You can use the Lightsail browser terminal or SSH from your machine.

Once connected, install Docker Engine and the Compose plugin. The cleanest path is Docker's official Ubuntu apt-repository install flow.

After Docker is installed:

```bash
sudo usermod -aG docker $USER
newgrp docker
docker --version
docker compose version
```

## Step 3: Copy the project onto the server

Use whichever method is easiest for you:

- `git clone` from your repo host
- upload a tarball
- copy the project with `scp` or your editor

Once the code is on the server:

```bash
cd home-buddy
cp .env.example .env
```

Edit `.env` and fill in real values, especially:

- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `OPENAI_WORKFLOW_MODEL`
- `TESTING_OPENAI_MODEL`
- `OPENAI_MAX_RETRIES`
- `POSTGRES_DB`
- `POSTGRES_USER`
- `POSTGRES_PASSWORD`
- `COGNITO_APP_CLIENT_ID`
- `COGNITO_ISSUER`
- `COGNITO_JWKS_URL`
- `COGNITO_DOMAIN`
- `COGNITO_REDIRECT_URI`
- `COGNITO_LOGOUT_REDIRECT_URI`

## Step 4: Add a real domain before first sign-in

Because HomeBuddy requires Cognito sign-in, set up the domain before your first real login flow.

1. Buy or use a domain you already own.
2. Point an `A` record at the Lightsail static IP.
3. Update [`deploy/Caddyfile`](../deploy/Caddyfile) so the site label is your real domain instead of `:80`.

The `homebuddy.chat` block serves the public demo: the static page in `deploy/site` and the backend's `/demo` routes.

### The public demo

homebuddy.chat is a demo page with no sign-in. Visitors ask the real agent about a few public appliance manuals and see the mobile app is coming soon. The signed-in Streamlit app is switched off but kept in the repo.

1. Caddy serves `deploy/site/index.html` at `/` and forwards `/demo/*` to the backend, unbuffered and uncompressed so answers stream. `FORWARDED_ALLOW_IPS=*` gives the backend the visitor's IP from Caddy.
2. Index the manuals into a demo household. It has no members, so only the `/demo` routes can read it:

   ```bash
   docker compose -f docker-compose.aws.yml exec backend python scripts/load_demo_documents.py --create-household --apply
   ```

   Put the id it prints in `.env` as `DEMO_HOUSEHOLD_ID`, then restart the backend with `docker compose -f docker-compose.aws.yml up -d backend`. Later runs of `python scripts/load_demo_documents.py --apply` only add manuals that are missing.
3. Visitors get 3 questions a minute and 20 a day, and the whole demo stops at 300 a day (UTC). Change them with `DEMO_QUERIES_PER_MINUTE`, `DEMO_QUERIES_PER_VISITOR_PER_DAY` and `DEMO_QUERIES_PER_DAY`. The counts live in memory, so a restart resets them.
4. To bring back the signed-in web app, start it with `docker compose -f docker-compose.aws.yml --profile web-app up -d` and switch the Caddyfile back to `reverse_proxy frontend:8501` (the comment at the end of the `homebuddy.chat` block shows how).

## Step 5: Configure Cognito before launch

HomeBuddy requires Cognito for sign-in.

In Cognito:

1. Create a user pool.
2. Create a web app client.
3. Disable self-service sign-up if you want a private beta.
4. Create a group such as `beta_testers` and add your invited users to it.
5. Set the callback URL to your deployed HTTPS URL.
6. Set the logout URL to your deployed HTTPS URL.
7. Capture these values:
   - `COGNITO_REGION`
   - `COGNITO_USER_POOL_ID`
   - `COGNITO_APP_CLIENT_ID`
   - `COGNITO_DOMAIN`

Then compute:

- `COGNITO_ISSUER=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>`
- `COGNITO_JWKS_URL=https://cognito-idp.<region>.amazonaws.com/<user-pool-id>/.well-known/jwks.json`

Update `.env` with those values:

```env
COGNITO_REDIRECT_URI=https://homebuddy.yourdomain.com
COGNITO_LOGOUT_REDIRECT_URI=https://homebuddy.yourdomain.com
COGNITO_ALLOWED_GROUPS=beta-testers
```

## Step 6: Start HomeBuddy

From the repo root on the server:

```bash
docker compose -f docker-compose.aws.yml up -d --build
```

Useful follow-up commands:

```bash
docker compose -f docker-compose.aws.yml ps
docker compose -f docker-compose.aws.yml logs -f
docker compose -f docker-compose.aws.yml logs -f backend
docker compose -f docker-compose.aws.yml logs -f caddy
```

## Step 7: Smoke test the deployment

Visit:

```text
https://YOUR_DOMAIN
```

You should see the demo page with the manuals on its shelf, and a question should stream an answer. `https://YOUR_DOMAIN/demo/documents` should list the manuals.

If something fails:

1. Check `docker compose ... ps`
2. Check backend logs
3. Check Caddy logs
4. Check the database container logs

If you updated DNS or Cognito values after the first launch, restart:

```bash
docker compose -f docker-compose.aws.yml up -d
```

Caddy will handle HTTPS automatically after DNS is in place.

## Operational basics

To update the app later:

```bash
git pull
docker compose -f docker-compose.aws.yml up -d --build
```

To stop it:

```bash
docker compose -f docker-compose.aws.yml down
```

To stop it and also remove the database volume:

```bash
docker compose -f docker-compose.aws.yml down -v
```

Be careful with `-v`. That deletes your local Postgres data for this deployment.

## Backup advice

For a portfolio project, keep it simple:

- enable Lightsail instance snapshots
- take a snapshot before major changes
- optionally run `pg_dump` on a schedule later

## Cost guardrails

- Start with one Lightsail instance only
- Do not add a load balancer
- Do not add a managed database yet
- Use a cheaper OpenAI model like `gpt-4o-mini` first
- Set up an AWS Budget alert on day one
- Shut down or delete test instances you no longer use

## When to upgrade the architecture

Move beyond this setup if any of these happen:

- memory pressure on the single instance
- you need higher uptime guarantees
- you want managed backups and failover
- multiple users start using it regularly

At that point, the next step would usually be:

- EC2 or ECS for the app
- RDS PostgreSQL for the database
- S3 for document storage
