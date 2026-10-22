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
  - `frontend` for Streamlit
  - `backend` for FastAPI
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

Example:

```caddyfile
homebuddy.yourdomain.com {
    encode gzip zstd
    reverse_proxy frontend:8501
}
```

### API subdomain for the mobile apps

The native apps call the backend directly at `api.homebuddy.chat`. The backend still publishes no port of its own; Caddy is the only way in.

1. Add a second `A` record, `api`, pointing at the same static IP. Caddy requests its HTTPS certificate automatically on the next start.
2. The `api.homebuddy.chat` block in the Caddyfile:
   - limits request bodies (60 MB for `/documents*`, 256 KB otherwise)
   - streams answers without buffering
3. `docker-compose.aws.yml` runs the backend with `ENVIRONMENT=production` (hides `/docs` and `/openapi.json`) and `ALLOWED_HOSTS=api.homebuddy.chat,backend`. Override `ALLOWED_HOSTS` in `.env` if your domain differs.
4. Per-user limits default to 10 questions and 10 document uploads per minute. Change them with `RATE_LIMIT_QUERIES_PER_MINUTE` and `RATE_LIMIT_DOCUMENTS_PER_MINUTE`.

Check the Caddyfile before restarting Caddy:

```bash
docker compose -f docker-compose.aws.yml run --rm caddy caddy validate --config /etc/caddy/Caddyfile
```

After deploying, from your laptop:

```bash
curl -s https://api.homebuddy.chat/health
curl -s -o /dev/null -w "%{http_code}\n" https://api.homebuddy.chat/docs
```

The first should return JSON and the second `404`.

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

### Android app client

The Android app signs in with its own **public** app client in the same user pool, so the same account works on the web and on phones. In the user pool, add an app client:

1. App type: **Public client** (no client secret; a secret can't be kept on a phone).
2. OAuth grant: **Authorization code** only. PKCE is used automatically for public clients.
3. Scopes: `openid`, `email`, `profile`.
4. Callback URL `homebuddy://auth/callback` and sign-out URL `homebuddy://auth/signout`.
5. Token lifetimes: access and ID tokens 1 hour; refresh token 30–365 days. This is how long a phone stays signed in without opening the app.
6. Turn on token revocation.

Add the new client id to the backend's `.env` (comma-separate several, e.g. when iOS arrives):

```env
COGNITO_MOBILE_APP_CLIENT_IDS=<android-client-id>
```

Every signed-in phone gets its own tokens, so one account can be signed in on several devices at once.

### Account deletion

Google Play requires apps to let people delete their account both in the app and on the web. `DELETE /me` removes the user's data, then deletes their Cognito user, which needs AWS credentials:

1. In IAM, create a user (or access key) whose only permission is `cognito-idp:AdminDeleteUser` on your user pool's ARN.
2. Add its key to the server's `.env` as `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. `COGNITO_USER_POOL_ID` and `COGNITO_REGION` must be set too.
3. The web link for the Play Console's Data safety form is `https://homebuddy.chat/?page=delete-account`. After sign-in it opens My Profile, where "Delete account…" lives.

Without the key, the data is still deleted but the Cognito sign-in remains, and the backend logs a warning.

### Privacy before going public

Do these before publishing the apps:

1. **Encrypt stored documents.** Generate a key and add it to `.env` as `DOCUMENT_ENCRYPTION_KEY`, then encrypt files saved earlier:

   ```bash
   docker compose -f docker-compose.aws.yml exec backend python -c "from backend.encryption import generate_key; print(generate_key())"
   docker compose -f docker-compose.aws.yml up -d backend
   docker compose -f docker-compose.aws.yml exec backend python scripts/encrypt_documents.py --apply
   ```

   Back the key up with your other secrets. Without it, stored files can't be read back; search keeps working because it uses the extracted text.
2. **Decide what LangSmith sees.** It records full questions and answers by default. To keep monitoring without storing content, add `LANGSMITH_HIDE_INPUTS=true` and `LANGSMITH_HIDE_OUTPUTS=true` to `.env`, or turn tracing off with `LANGCHAIN_TRACING_V2=false`. Update the Privacy Policy to match.
3. **Review the policy pages.** `deploy/site/privacy.html` and `deploy/site/terms.html` are served at `/privacy` and `/terms`. Fill in the contact address and check they still describe what Home Buddy does.

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
docker compose -f docker-compose.aws.yml logs -f frontend
```

## Step 7: Smoke test the deployment

Visit:

```text
https://YOUR_DOMAIN
```

You should see the Streamlit UI through Caddy.

If something fails:

1. Check `docker compose ... ps`
2. Check backend logs
3. Check frontend logs
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

### Database migrations

The backend runs `alembic upgrade head` on startup, so schema changes apply as part of `docker compose up -d --build`.

The first deploy with migrations also repairs the schema left behind by older `create_all` runs: it adds `ON DELETE` rules to foreign keys and a unique constraint on documents `(household_id, entry_id)`. Before that deploy:

1. Take a Lightsail snapshot.
2. Check for duplicate document ids, which would stop the migration with a clear error (use your `POSTGRES_USER` and `POSTGRES_DB` if you changed them):

```bash
docker compose -f docker-compose.aws.yml exec db psql -U homebuddy -d home_buddy -c \
  "SELECT household_id, entry_id, COUNT(*) FROM documents GROUP BY 1, 2 HAVING COUNT(*) > 1;"
```

3. After the deploy, confirm the version:

```bash
docker compose -f docker-compose.aws.yml exec backend alembic current
```

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
