import html
import json
import os
import re
import uuid
from urllib.parse import urlencode

import httpx
import streamlit as st
import streamlit.components.v1 as components
from dotenv import load_dotenv

from frontend_auth import claim_auth_code, jwt_is_expired

load_dotenv()

# ---------------------------------------------------------------------------
# API Client
# ---------------------------------------------------------------------------
BACKEND_URL = os.getenv("BACKEND_URL", "http://127.0.0.1:8000")
COGNITO_DOMAIN = os.getenv("COGNITO_DOMAIN", "")
COGNITO_APP_CLIENT_ID = os.getenv("COGNITO_APP_CLIENT_ID", "")
COGNITO_REDIRECT_URI = os.getenv("COGNITO_REDIRECT_URI", "")
COGNITO_LOGOUT_REDIRECT_URI = os.getenv("COGNITO_LOGOUT_REDIRECT_URI", "")
COGNITO_SCOPE = os.getenv("COGNITO_SCOPE", "openid email phone")
COGNITO_ALLOWED_GROUPS = tuple(
    item.strip() for item in os.getenv("COGNITO_ALLOWED_GROUPS", "").split(",") if item.strip()
)
LANGCHAIN_TRACING = os.getenv("LANGCHAIN_TRACING_V2", "true")
BETA_ACCESS_ERROR = "This account is not enabled for the HomeBuddy beta."


class BackendRequestError(RuntimeError):
    def __init__(self, message: str, status_code: int):
        super().__init__(message)
        self.status_code = status_code


def bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


HOME_OPERATIONS_ENABLED = bool_env("HOME_OPERATIONS_ENABLED", False)
AUTH_STORAGE_COMPONENT = components.declare_component(
    "homebuddy_auth_storage",
    path=os.path.join(os.path.dirname(__file__), "frontend", "auth_storage"),
)

# Composer starter questions. Clicking one fills the input rather than sending,
# so the wording stays editable before it goes to the agent.
CHAT_SUGGESTIONS = (
    "Is my dryer still under warranty?",
    "Why is the dishwasher not draining?",
    "When did I last change the HVAC filter?",
)

def esc(value) -> str:
    return html.escape(str(value or ""))


# ---------------------------------------------------------------------------
# Design-system presentation helpers
#
# Icons are inline Lucide paths rather than emoji so they inherit currentColor
# and stay on the design system's 2.75 stroke weight.
# ---------------------------------------------------------------------------

_ICON_PATHS = {
    "home": '<path d="M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8"/><path d="M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    "sparkle": '<path d="M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z"/>',
    "file": '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    "file-min": '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/>',
    "shield": '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="M12 8v4"/><path d="M12 16h.01"/>',
    "check": '<path d="M20 6 9 17l-5-5"/>',
    "chevron": '<path d="m9 18 6-6-6-6"/>',
}


def icon(name: str, size: int = 20, stroke: str = "currentColor", extra: str = "") -> str:
    """Inline SVG icon from the design system's Lucide set."""
    paths = _ICON_PATHS.get(name, "")
    return (
        f'<svg width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" '
        f'stroke="{stroke}" stroke-width="2.75" stroke-linecap="round" '
        f'stroke-linejoin="round" {extra}>{paths}</svg>'
    )


def initials(user: dict | None) -> str:
    """Two-letter monogram for the account avatar, falling back to the email."""
    user = user or {}
    source = (user.get("display_name") or "").strip() or (user.get("email") or "").strip()
    if not source:
        return "HB"
    parts = [part for part in re.split(r"[\s._-]+", source) if part]
    if len(parts) >= 2:
        return (parts[0][0] + parts[1][0]).upper()
    return source[:2].upper()


@st.cache_resource
def get_api_client() -> httpx.Client:
    return httpx.Client(base_url=BACKEND_URL, timeout=120.0)

def _read_backend_error(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except Exception:
        return response.text

    if isinstance(payload, dict) and "detail" in payload:
        return str(payload["detail"])
    return str(payload)


def _format_auth_error(exc: Exception) -> str:
    message = str(exc).strip()
    if not message:
        return "Could not complete Cognito sign-in."
    if message == BETA_ACCESS_ERROR:
        return message
    return f"Could not complete Cognito sign-in: {message}"


def _format_session_restore_error(exc: Exception) -> str:
    message = str(exc).strip()
    if not message:
        return "Your session expired. Please sign in again."
    if message == BETA_ACCESS_ERROR:
        return message
    return f"Your session expired or could not be restored: {message}"


def _request_headers() -> dict[str, str]:
    # Attach the bearer token only when the app is operating in authenticated mode.
    token = st.session_state.get("auth_token")
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


def sync_browser_auth_storage(
    action: str,
    access_token: str | None = None,
) -> dict | None:
    """Read, write, or clear the browser-persisted Cognito access token."""
    return AUTH_STORAGE_COMPONENT(
        action=action,
        access_token=access_token,
        default=None,
        key="homebuddy_auth_storage",
    )

def post_json(path: str, payload: dict) -> dict:
    response = get_api_client().post(path, json=payload, headers=_request_headers())
    if response.is_error:
        raise RuntimeError(_read_backend_error(response))
    return response.json()


def stream_query(payload: dict):
    with get_api_client().stream(
        "POST",
        "/query/stream",
        json=payload,
        headers=_request_headers(),
        timeout=180.0,
    ) as response:
        if response.is_error:
            raise RuntimeError(_read_backend_error(response))

        for line in response.iter_lines():
            if not line:
                continue
            if not line.startswith("data: "):
                continue
            yield json.loads(line[6:])


def put_json(path: str, payload: dict) -> dict:
    response = get_api_client().put(path, json=payload, headers=_request_headers())
    if response.is_error:
        raise RuntimeError(_read_backend_error(response))
    return response.json()


def delete_request(path: str) -> None:
    response = get_api_client().delete(path, headers=_request_headers())
    if response.is_error:
        raise RuntimeError(_read_backend_error(response))


def post_form(path: str, data: dict, files: dict | None = None) -> dict:
    response = get_api_client().post(path, data=data, files=files, headers=_request_headers())
    if response.is_error:
        raise RuntimeError(_read_backend_error(response))
    return response.json()


def get_json(path: str) -> list | dict:
    response = get_api_client().get(path, headers=_request_headers())
    if response.is_error:
        raise BackendRequestError(_read_backend_error(response), response.status_code)
    return response.json()

def load_current_user() -> dict:
    # Resolve the authenticated user through the backend so the UI stays aligned with server-side auth behavior.
    return get_json("/auth/me")


def load_conversation_messages(session_id: str) -> list[dict]:
    return get_json(
        f"/conversations/{session_id}/messages?household_id={st.session_state.active_household_id}"
    )


def clear_conversation_messages(session_id: str) -> None:
    delete_request(
        f"/conversations/{session_id}/messages?household_id={st.session_state.active_household_id}"
    )


def render_streaming_status(messages: list[str] | None = None) -> str:
    """Agent progress card: each completed step lands as its own ticked row."""
    lines = messages or []
    if lines:
        body = "".join(
            f'<div class="hb-status-line">'
            f'<span class="hb-status-tick">{icon("check", 12)}</span>'
            f"<span>{esc(line)}</span>"
            f"</div>"
            for line in lines
        )
    else:
        body = '<div class="hb-chat-status-body">Thinking…</div>'
    return (
        '<div class="hb-msg">'
        f'<div class="hb-msg-avatar">{icon("sparkle", 18)}</div>'
        '<div class="hb-chat-status">'
        '<div class="hb-chat-status-title">Working on it'
        '<span class="hb-dots"><span></span><span></span><span></span></span>'
        "</div>"
        f"{body}"
        "</div>"
        "</div>"
    )


def render_user_bubble(text: str, monogram: str) -> str:
    return (
        '<div class="hb-msg hb-msg-user">'
        f'<div class="hb-bubble-user">{esc(text)}</div>'
        f'<div class="hb-msg-avatar hb-msg-avatar-user">{esc(monogram)}</div>'
        "</div>"
    )


def render_safety_bubble(text: str, steps: list[str]) -> str:
    """Urgent answers get the stop-and-do-this treatment with numbered steps."""
    step_html = "".join(
        f'<div class="hb-step">'
        f'<span class="hb-step-n">{index}</span>'
        f'<span class="hb-step-text">{esc(step)}</span>'
        f"</div>"
        for index, step in enumerate(steps, start=1)
    )
    return (
        '<div class="hb-msg">'
        f'<div class="hb-msg-avatar hb-msg-avatar-safety">{icon("shield", 18)}</div>'
        '<div class="hb-bubble-safety">'
        '<div class="hb-safety-head">Safety first — stop and do this</div>'
        f'<div class="hb-safety-body"><p>{esc(text)}</p>{step_html}</div>'
        "</div>"
        "</div>"
    )


def is_urgent_turn(turn: dict | None) -> bool:
    """Safety styling is driven by the backend's own hazard assessment."""
    if not turn:
        return False
    return bool(turn.get("should_escalate")) or turn.get("urgency_level") in {"critical", "high"}


def build_conversation_pairs(messages: list[dict]) -> list[tuple[str | None, str | None]]:
    """Convert a flat user/assistant message list into turn-based display pairs."""
    pairs: list[tuple[str | None, str | None]] = []
    pending_user_messages: list[str] = []

    for message in messages:
        role = message.get("role")
        content = message.get("content")

        if role == "user":
            pending_user_messages.append(content)
            continue

        if role == "assistant":
            if pending_user_messages:
                pairs.append((pending_user_messages.pop(0), content))
            else:
                pairs.append((None, content))

    for pending_user_message in pending_user_messages:
        pairs.append((pending_user_message, None))

    return pairs


def build_display_conversation_pairs(
    messages: list[dict],
    latest_turn: dict | None,
) -> list[tuple[str | None, str | None]]:
    """Keep the newest completed turn visible even before persisted history catches up."""
    pairs = build_conversation_pairs(messages)
    if not latest_turn:
        return pairs

    latest_pair = (latest_turn.get("question"), latest_turn.get("answer"))
    if not latest_pair[0] or not latest_pair[1]:
        return pairs

    if latest_pair not in pairs:
        pairs.append(latest_pair)

    return pairs


def merge_agent_messages(
    persisted_messages: list[dict],
    local_messages: list[dict],
) -> list[dict]:
    """Preserve the newest local turns while backend persistence catches up."""
    if not local_messages:
        return persisted_messages
    if not persisted_messages:
        return local_messages

    common_prefix = 0
    for persisted, local in zip(persisted_messages, local_messages):
        if persisted == local:
            common_prefix += 1
        else:
            break

    if common_prefix == len(persisted_messages):
        return local_messages
    if common_prefix == len(local_messages):
        return persisted_messages
    return persisted_messages


def exchange_auth_code(code: str) -> dict:
    return post_json("/auth/exchange", {"code": code})


def build_cognito_authorize_url() -> str | None:
    if not COGNITO_DOMAIN or not COGNITO_APP_CLIENT_ID or not COGNITO_REDIRECT_URI:
        return None
    query = urlencode(
        {
            "response_type": "code",
            "client_id": COGNITO_APP_CLIENT_ID,
            "redirect_uri": COGNITO_REDIRECT_URI,
            "scope": COGNITO_SCOPE,
            "prompt": "login",
        }
    )
    return f"{COGNITO_DOMAIN.rstrip('/')}/oauth2/authorize?{query}"


def build_cognito_logout_url() -> str | None:
    if not COGNITO_DOMAIN or not COGNITO_APP_CLIENT_ID or not COGNITO_LOGOUT_REDIRECT_URI:
        return None
    query = urlencode(
        {
            "client_id": COGNITO_APP_CLIENT_ID,
            "logout_uri": COGNITO_LOGOUT_REDIRECT_URI,
        }
    )
    return f"{COGNITO_DOMAIN.rstrip('/')}/logout?{query}"


def clear_auth_query_params() -> None:
    for key in ("code", "state", "error", "error_description"):
        if key in st.query_params:
            del st.query_params[key]

def bootstrap_auth_identity(id_token: str, access_token: str) -> dict:
    return post_json("/auth/bootstrap", {"id_token": id_token, "access_token": access_token})

def handle_cognito_callback() -> None:
    error = st.query_params.get("error")
    if error:
        description = st.query_params.get("error_description") or error
        st.session_state.auth_error = str(description)
        clear_auth_query_params()
        st.rerun()

    code = st.query_params.get("code")
    if not code:
        return

    code = str(code)
    if not claim_auth_code(st.session_state, code):
        clear_auth_query_params()
        return

    # Cognito authorization codes are single-use. Remove the callback URL and
    # claim the code before the first network call so component/widget reruns
    # cannot exchange it a second time.
    clear_auth_query_params()
    try:
        token_payload = exchange_auth_code(code)
        st.session_state.auth_token = token_payload["access_token"]
        st.session_state.access_token = token_payload["access_token"]
        st.session_state.id_token = token_payload["id_token"]
        st.session_state.current_user = bootstrap_auth_identity(
            st.session_state.id_token,
            st.session_state.access_token,
        )
        st.session_state.auth_signed_in = True
        st.session_state.auth_storage_action = "write"
        st.session_state.auth_error = None
        st.rerun()
    except Exception as exc:
        st.session_state.auth_signed_in = False
        st.session_state.auth_token = None
        st.session_state.access_token = None
        st.session_state.id_token = None
        st.session_state.current_user = None
        st.session_state.auth_error = _format_auth_error(exc)
        st.rerun()


def sign_out() -> bool:
    # Clear auth and household-scoped UI state so the next sign-in starts cleanly.
    st.session_state.auth_signed_in = False
    st.session_state.access_token = None
    st.session_state.auth_token = None
    st.session_state.id_token = None
    st.session_state.current_user = None
    st.session_state.active_household_id = None
    st.session_state.active_household_zip = None
    st.session_state.agent_messages = []
    st.session_state.pending_task_draft = None
    st.session_state.edit_task_draft = False
    st.session_state.task_draft_status = None
    st.session_state.task_draft_status_level = None
    st.session_state.pending_case_draft = None
    st.session_state.edit_case_draft = False
    st.session_state.case_draft_status = None
    st.session_state.case_draft_status_level = None
    st.session_state.last_agent_turn = None
    st.session_state.households = []
    st.session_state.auth_error = None
    st.session_state.processed_auth_code = None
    st.session_state.auth_storage_action = "clear"
    st.session_state.logout_after_auth_clear = True
    clear_auth_query_params()
    return True


def render_sign_in_screen() -> None:
    auth_eyebrow = "Private Beta" if COGNITO_ALLOWED_GROUPS else "Welcome Back"
    auth_copy = (
        "HomeBuddy is currently invite-only. Use the Cognito account you were invited with to get back to your saved documents, household activity, and assistant history."
        if COGNITO_ALLOWED_GROUPS
        else "HomeBuddy keeps your saved documents, household activity, and assistant history together so you can come back to the same workspace at any time."
    )

    st.markdown('<div class="hb-auth-shell"></div>', unsafe_allow_html=True)
    copy_col, hero_col = st.columns([1.05, 0.95], gap="large")

    with copy_col:
        with st.container(key="hb_auth_copy"):
            st.markdown(
                f"""
                <div class="hb-brand">
                    <div class="hb-brand-mark">{icon("home", 22)}</div>
                    <div class="hb-brand-name" style="font-size: 1.6rem;">HomeBuddy</div>
                </div>
                <div class="hb-auth-eyebrow">{esc(auth_eyebrow)}</div>
                <div class="hb-auth-title">Sign in and pick up where you left off.</div>
                <div class="hb-auth-copy">{esc(auth_copy)}</div>
                """,
                unsafe_allow_html=True,
            )

            if st.session_state.get("auth_error"):
                st.error(st.session_state.auth_error)

            authorize_url = build_cognito_authorize_url()
            if authorize_url:
                st.markdown(
                    f'<a class="hb-signin-btn" href="{esc(authorize_url)}" target="_self">Sign In</a>',
                    unsafe_allow_html=True,
                )
                st.markdown(
                    '<div class="hb-auth-note">Invited with a different address? Ask your household owner to re-send it.</div>',
                    unsafe_allow_html=True,
                )
            else:
                st.error("Cognito sign-in is not fully configured. Check domain, client id, and redirect URI.")

    with hero_col:
        with st.container(key="hb_auth_hero"):
            features = (
                ("file", "Save manuals, warranties, and receipts for grounded answers"),
                ("shield", "Ask for troubleshooting, safety, and coverage help"),
                ("check", "Turn issues into cases, reminders, and next steps"),
            )
            feature_html = "".join(
                f'<div class="hb-auth-feature">{icon(name, 20, "var(--color-accent)")}'
                f"<span>{esc(label)}</span></div>"
                for name, label in features
            )
            st.markdown(
                f"""
                <div class="hb-auth-eyebrow" style="color: color-mix(in srgb, var(--color-text) 60%, transparent);">Inside HomeBuddy</div>
                <div class="hb-auth-hero-title">Grounded answers, cleaner follow-through, less home chaos.</div>
                {feature_html}
                """,
                unsafe_allow_html=True,
            )


def render_household_setup_screen() -> None:
    user = st.session_state.current_user or {}
    st.markdown('<p class="main-header">Finish Setup</p>', unsafe_allow_html=True)
    st.markdown(
        f'<p class="sub-header">Signed in as {esc(user.get("email")) or "your account"}. Create the household this account will manage.</p>',
        unsafe_allow_html=True,
    )

    with st.form("household_setup_form"):
        setup_left, setup_right = st.columns(2)
        with setup_left:
            household_name = st.text_input("Household name", key="setup_household_name")
            zip_code = st.text_input("Zip code", key="setup_household_zip")
        with setup_right:
            home_type = st.selectbox(
                "Home type",
                options=["House", "Apartment", "Condo", "Townhome", "Other"],
                key="setup_household_type",
            )
            st.caption("This version supports one household per account.")

        finish_setup = st.form_submit_button("Create Household", use_container_width=True)

    if finish_setup:
        if not household_name.strip() or not zip_code.strip():
            st.warning("Enter both a household name and zip code.")
        else:
            try:
                household = post_json(
                    "/households",
                    {
                        "name": household_name.strip(),
                        "zip_code": zip_code.strip(),
                        "home_type": home_type,
                    },
                )
                st.session_state.households = [household]
                st.session_state.active_household_id = household["id"]
                st.session_state.active_household_zip = household["zip_code"]
                st.success(f"Household created: {household['name']}")
                st.rerun()
            except Exception as exc:
                st.error(f"Could not create household: {exc}")


def build_entry_id(display_name: str, existing_entries: list[dict], download_url: str | None = None) -> str:
    if download_url:
        for entry in existing_entries:
            if entry.get("download_url") == download_url:
                return entry["entry_id"]
    else:
        for entry in existing_entries:
            if not entry.get("download_url") and entry.get("display_name") == display_name:
                return entry["entry_id"]

    slug = re.sub(r"[^a-z0-9]+", "-", display_name.lower()).strip("-") or "document"
    existing_ids = {entry["entry_id"] for entry in existing_entries}
    candidate = slug
    suffix = 2

    while candidate in existing_ids:
        candidate = f"{slug}-{suffix}"
        suffix += 1

    return candidate


def remember_indexed_doc(display_name: str, entry_id: str, chunks_indexed: int) -> None:
    for doc in st.session_state.indexed_docs:
        if doc["entry_id"] == entry_id:
            doc["name"] = display_name
            doc["chunks"] = chunks_indexed
            break
    else:
        st.session_state.indexed_docs.append(
            {"name": display_name, "entry_id": entry_id, "chunks": chunks_indexed}
        )

    st.session_state.doc_count = len(st.session_state.indexed_docs)
    st.session_state.active_entry_id = entry_id


def _task_notes_for_save(notes: str | None, schedule_hint: str | None) -> str | None:
    # Preserve the model's relative timing hint in notes when we do not have an exact due date yet.
    if schedule_hint and notes:
        return f"{notes}\n\nOriginal schedule hint: {schedule_hint}"
    if schedule_hint and not notes:
        return f"Original schedule hint: {schedule_hint}"
    return notes


def save_task_draft(task_draft: dict, due_date_value) -> dict:
    # Build the payload that the existing POST /tasks endpoint expects.
    payload = {
        "household_id": st.session_state.active_household_id,
        "asset_id": None,
        "title": task_draft["title"],
        # Keep relative scheduling language in notes unless the user gives us a concrete due date.
        "notes": _task_notes_for_save(task_draft.get("notes"), task_draft.get("schedule_hint")),
        # Streamlit returns a Python date object here, so convert it to ISO before JSON serialization.
        "due_date": due_date_value.isoformat() if due_date_value else None,
    }
    return post_json("/tasks", payload)
    
def save_case_draft(case_draft: dict) -> dict:
    # Build the payload that the existing POST /cases endpoint expects.
    payload = {
        "household_id": st.session_state.active_household_id,
        "asset_id": None,
        "title": case_draft["title"],
        "contractor_trade": case_draft["contractor_trade"],
        "severity": case_draft["severity"],
        "summary": case_draft["summary"]
    }
    return post_json("/cases", payload)

def render_case_draft_hitl() -> None:
    if not HOME_OPERATIONS_ENABLED:
        return
    pending_case_draft = st.session_state.pending_case_draft
    # Keep the most recent feedback visible even after the pending draft is cleared.
    if st.session_state.case_draft_status:
        status_level = st.session_state.case_draft_status_level
        if status_level == "success":
            st.success(st.session_state.case_draft_status)
        elif status_level == "error":
            st.error(st.session_state.case_draft_status)
        else:
            st.info(st.session_state.case_draft_status)
    if not pending_case_draft:
        return
    
    st.markdown("### Case Draft Ready")
    st.caption("This draft has not been saved yet. Review it before saving.")

    # Show the current draft values so the user can decide whether to save, edit, or dismiss it.
    st.markdown(f"**Title:** {pending_case_draft.get('title', '')}")
    st.markdown(f"**Summary:** {pending_case_draft.get('summary', '')}")
    st.markdown(f"**Severity:** {pending_case_draft.get('severity', '')}")
    if pending_case_draft.get('contractor_trade'): 
        st.markdown(f"**Contractor Trade:** {pending_case_draft.get('contractor_trade')}")

    action_col_1, action_col_2, action_col_3 = st.columns(3)

    # Accept saves the case immediately using the current draft values.
    if action_col_1.button("Save Case", key="save_case_button", use_container_width=True):
        try:
            saved_case = save_case_draft(pending_case_draft)
            st.session_state.case_draft_status = f"Case saved with id={saved_case['id']}."
            st.session_state.case_draft_status_level = "success"
            st.session_state.pending_case_draft = None
            st.session_state.edit_case_draft = False
            st.rerun()
        except Exception as exc:
            st.session_state.case_draft_status = f"Could not save case draft: {exc}"
            st.session_state.case_draft_status_level = "error"
            st.rerun()
    
    # Edit reveals an inline form so the user can refine the draft before saving.
    if action_col_2.button("Edit", key="edit_case_draft_button", use_container_width=True):
        st.session_state.edit_case_draft = True
        st.rerun()

    # Dismiss only removes the draft from the current UI state; nothing is persisted.
    if action_col_3.button("Dismiss", key="dismiss_case_draft_button", use_container_width=True):
        st.session_state.pending_case_draft = None
        st.session_state.edit_case_draft = False
        st.session_state.case_draft_status = "Case draft dismissed."
        st.session_state.case_draft_status_level = "info"
        st.rerun()
    
    if st.session_state.edit_case_draft:
        with st.form("case_draft_edit_form"):
            # Use explicit widget keys so Streamlit keeps the edit state stable across reruns.
            edited_title = st.text_input(
                "Title",
                value=pending_case_draft.get("title", ""),
                key="case_draft_title",
            )
            edited_severity = st.text_area(
                "Severity",
                value=pending_case_draft.get("severity", ""),
                key="case_draft_severity",
            )
            edited_summary = st.text_area(
                "Summary",
                value=pending_case_draft.get("summary", ""),
                key="case_draft_summary",
            )
            edited_contractor_trade = st.text_area(
                "Contractor Trade",
                value=pending_case_draft.get("contractor_trade", ""),
                key="case_draft_contractor_trade",
            )
          
            save_edit = st.form_submit_button("Save Edited Case", use_container_width=True)

            if save_edit:
                try:
                    # Update the in-memory draft first so the visible review card stays in sync with the saved payload.
                    st.session_state.pending_case_draft = {
                        **pending_case_draft,
                        "title": edited_title,
                        "severity": edited_severity,
                        "summary": edited_summary,
                        "contractor_trade": edited_contractor_trade
                    }
                    saved_case = save_case_draft(st.session_state.pending_case_draft)
                    st.session_state.case_draft_status = f"Case saved: {saved_case['title']}."
                    st.session_state.case_draft_status_level = "success"
                    st.session_state.pending_case_draft = None
                    st.session_state.edit_case_draft = False
                    st.rerun()
                except Exception as exc:
                    st.session_state.case_draft_status = f"Could not save case draft: {exc}"
                    st.session_state.case_draft_status_level = "error"
                    st.rerun()


def render_task_draft_hitl() -> None:
    if not HOME_OPERATIONS_ENABLED:
        return
    pending_task_draft = st.session_state.pending_task_draft
    # Keep the most recent feedback visible even after the pending draft is cleared.
    if st.session_state.task_draft_status:
        status_level = st.session_state.task_draft_status_level
        if status_level == "success":
            st.success(st.session_state.task_draft_status)
        elif status_level == "error":
            st.error(st.session_state.task_draft_status)
        else:
            st.info(st.session_state.task_draft_status)
    if not pending_task_draft:
        return

    st.markdown("### Task Draft Ready")
    st.caption("This draft has not been saved yet. Review it before saving")

    # Show the current draft values so the user can decide whether to save, edit, or dismiss it.
    st.markdown(f"**Title:** {pending_task_draft.get('title', '')}")
    st.markdown(f"**Notes:** {pending_task_draft.get('notes') or '—'}")
    st.markdown(f"**Schedule hint:** {pending_task_draft.get('schedule_hint') or '—'}")

    action_col_1, action_col_2, action_col_3 = st.columns(3)

    # Accept saves the task immediately using the current draft values.
    if action_col_1.button("Save Task", key="save_task_draft_button", use_container_width=True):
        try:
            saved_task = save_task_draft(pending_task_draft, due_date_value=None)
            st.session_state.task_draft_status = f"Task saved: {saved_task['title']}."
            st.session_state.task_draft_status_level = "success"
            st.session_state.pending_task_draft = None
            st.session_state.edit_task_draft = False
            st.rerun()
        except Exception as exc:
            st.session_state.task_draft_status = f"Could not save task draft: {exc}"
            st.session_state.task_draft_status_level = "error"
            st.rerun()

    # Edit reveals an inline form so the user can refine the draft before saving.
    if action_col_2.button("Edit", key="edit_task_draft_button", use_container_width=True):
        st.session_state.edit_task_draft = True
        st.rerun()

    # Dismiss only removes the draft from the current UI state; nothing is persisted.
    if action_col_3.button("Dismiss", key="dismiss_task_draft_button", use_container_width=True):
        st.session_state.pending_task_draft = None
        st.session_state.edit_task_draft = False
        st.session_state.task_draft_status = "Task draft dismissed."
        st.session_state.task_draft_status_level = "info"
        st.rerun()

    if st.session_state.edit_task_draft:
        with st.form("task_draft_edit_form"):
            # Use explicit widget keys so Streamlit keeps the edit state stable across reruns.
            edited_title = st.text_input(
                "Title",
                value=pending_task_draft.get("title", ""),
                key="task_draft_title",
            )
            edited_notes = st.text_area(
                "Notes",
                value=pending_task_draft.get("notes") or "",
                key="task_draft_notes",
            )
            # Show the original relative timing as context while letting the user optionally choose a real date.
            st.caption(f"Original schedule hint: {pending_task_draft.get('schedule_hint') or '—'}")
            edited_due_date = st.date_input(
                "Due date (optional)",
                value=None,
                key="task_draft_due_date",
            )
            save_edit = st.form_submit_button("Save Edited Task", use_container_width=True)

            if save_edit:
                try:
                    # Update the in-memory draft first so the visible review card stays in sync with the saved payload.
                    st.session_state.pending_task_draft = {
                        **pending_task_draft,
                        "title": edited_title,
                        "notes": edited_notes or None,
                    }
                    # Pass the raw date object; save_task_draft handles the ISO conversion.
                    saved_task = save_task_draft(st.session_state.pending_task_draft, edited_due_date)
                    st.session_state.task_draft_status = f"Task saved with id={saved_task['id']}."
                    st.session_state.task_draft_status_level = "success"
                    st.session_state.pending_task_draft = None
                    st.session_state.edit_task_draft = False
                    st.rerun()
                except Exception as exc:
                    st.session_state.task_draft_status = f"Could not save task draft: {exc}"
                    st.session_state.task_draft_status_level = "error"
                    st.rerun()


def load_saved_documents() -> list[dict]:
    # Centralize document loading so both the docs tab and any future views can reuse the same backend call.
    return get_json(f"/documents?household_id={st.session_state.active_household_id}")


def load_cases() -> list[dict]:
    # Fetch persisted cases for the active household from the existing backend endpoint.
    return get_json(f"/cases?household_id={st.session_state.active_household_id}")

def load_tasks() -> list[dict]:
    # Fetch persisted tasks for the active household from the existing backend endpoint.
    return get_json(f"/tasks?household_id={st.session_state.active_household_id}")

def update_case_record(case_id: int, case_payload: dict) -> dict:
    # Send edited case fields back to the backend.
    return put_json(f"/cases/{case_id}", case_payload)

def delete_case_record(case_id: int) -> None:
    # Remove a persisted case from the backend.
    delete_request(f"/cases/{case_id}")

def update_task_record(task_id: int, task_payload: dict) -> dict:
    # Send edited task fields back to the backend.
    return put_json(f"/tasks/{task_id}", task_payload)

def delete_single_document(entry_id: str) -> None:
    delete_request(
        f"/documents/{entry_id}?household_id={st.session_state.active_household_id}"
    )

def delete_all_documents() -> None:
    delete_request(
        f"/documents?household_id={st.session_state.active_household_id}"
    )

def delete_task_record(task_id: int) -> None:
    # Remove a persisted task from the backend.
    delete_request(f"/tasks/{task_id}")


def begin_document_index(pending_document: dict) -> None:
    """Persist the upload across a rerun so the blocking state renders first."""
    st.session_state.pending_document_index = pending_document
    st.session_state.document_indexing = True
    st.session_state.docs_status = None
    st.session_state.docs_status_level = None


def finish_pending_document_index() -> None:
    pending_document = st.session_state.get("pending_document_index")
    if not pending_document:
        st.session_state.document_indexing = False
        return

    try:
        data = {
            "household_id": st.session_state.active_household_id,
            "entry_id": pending_document["entry_id"],
            "session_id": st.session_state.session_id,
            "display_name": pending_document["display_name"],
            "doc_type": pending_document.get("doc_type"),
        }
        files = None
        if pending_document["source"] == "url":
            data["url"] = pending_document["url"]
        else:
            files = {
                "file": (
                    pending_document["filename"],
                    pending_document["content"],
                    pending_document["content_type"],
                )
            }

        result = post_form("/documents/index", data=data, files=files)
        remember_indexed_doc(
            display_name=pending_document["display_name"],
            entry_id=pending_document["entry_id"],
            chunks_indexed=result["chunks_indexed"],
        )
        st.session_state.docs_status = (
            f"Saved {pending_document['display_name']} and indexed "
            f"{result['chunks_indexed']} chunks."
        )
        st.session_state.docs_status_level = "success"
    except Exception as exc:
        st.session_state.docs_status = f"Could not save document: {exc}"
        st.session_state.docs_status_level = "error"
    finally:
        st.session_state.pending_document_index = None
        st.session_state.document_indexing = False
        st.rerun()


def render_document_indexing_overlay() -> None:
    st.markdown(
        f"""
        <div class="hb-indexing-overlay" role="dialog" aria-modal="true" aria-labelledby="hb-indexing-title">
            <div class="hb-indexing-modal">
                <div class="hb-indexing-spinner" aria-hidden="true"></div>
                <div id="hb-indexing-title" class="hb-indexing-title">Saving new doc</div>
                <div class="hb-indexing-copy">Home Buddy is indexing your document so it can be used in answers.</div>
            </div>
        </div>
        """,
        unsafe_allow_html=True,
    )


def render_contractor_suggestions(contractor_suggestions: list[dict]) -> None:
    if not contractor_suggestions:
        return

    st.markdown("### Contractor Suggestions")
    attributions = [
        suggestion.get("source_attribution")
        for suggestion in contractor_suggestions
        if suggestion.get("source_attribution")
    ]
    unique_attributions = list(dict.fromkeys(attributions))
    for attribution in unique_attributions:
        st.caption(attribution)

    for suggestion in contractor_suggestions:
        business_name = suggestion.get("business_name") or "Unknown business"
        trade = suggestion.get("trade") or "unknown"
        rating = suggestion.get("rating")
        review_count = suggestion.get("review_count")
        phone = suggestion.get("phone")
        reason = suggestion.get("reason_suggested") or "Matched contractor suggestion."
        url = suggestion.get("url")

        meta_parts = [trade]
        if rating is not None:
            meta_parts.append(f"Rating: {rating}")
        if review_count is not None:
            meta_parts.append(f"Reviews: {review_count}")
        if phone:
            meta_parts.append(f"Phone: {phone}")

        st.markdown(f"**{business_name}**")
        st.caption(" · ".join(meta_parts))
        st.markdown(reason)
        if url:
            st.link_button(
                f"Open {business_name}",
                url,
                use_container_width=False,
                key=f"contractor_link_{business_name}_{url}",
            )
        st.divider()

def render_profile_tab() -> None:
    st.markdown('<div class="hb-page-kicker">Profile</div>', unsafe_allow_html=True)
    st.markdown('<div class="hb-page-title">My Profile</div>', unsafe_allow_html=True)
    st.markdown(
        '<div class="hb-page-subtitle">Your account details and household summary.</div>',
        unsafe_allow_html=True,
    )

    user = st.session_state.current_user or {}
    user_left, user_right = st.columns([1.2, 1], gap="large")
    with user_left:
        st.markdown(
            f"""
            <div class="hb-card" style="display: flex; gap: 18px; align-items: center; box-shadow: var(--shadow-md); padding: 1.5rem;">
                <div class="hb-avatar hb-avatar-lg">{esc(initials(user))}</div>
                <div>
                    <div class="hb-card-title" style="font-size: 1.4rem;">{esc(user.get('display_name')) or 'HomeBuddy User'}</div>
                    <div class="hb-card-body">{esc(user.get('email')) or '—'}</div>
                    <div class="hb-card-body" style="font-size: 0.78rem; opacity: 0.75;">User id {esc(user.get('id')) or '—'}</div>
                </div>
            </div>
            """,
            unsafe_allow_html=True,
        )
        if st.button("Sign Out", key="sign_out_button"):
            sign_out()
            st.rerun()

    with user_right:
        household_summary = st.session_state.households[0] if st.session_state.households else {}
        if household_summary:
            st.markdown(
                f"""
                <div class="hb-card" style="background: var(--color-surface); box-shadow: var(--shadow-md); padding: 1.5rem;">
                    <div class="hb-ground-kicker">Household</div>
                    <div class="hb-card-title" style="font-size: 1.4rem;">{esc(household_summary.get('name'))}</div>
                    <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0.6rem;">
                        <span class="hb-tag hb-tag-neutral">Zip {esc(household_summary.get('zip_code')) or '—'}</span>
                        <span class="hb-tag hb-tag-accent-2">{esc(household_summary.get('role')) or 'Owner'}</span>
                        <span class="hb-tag hb-tag-neutral">{esc(household_summary.get('home_type')) or 'Home'}</span>
                    </div>
                </div>
                """,
                unsafe_allow_html=True,
            )

    if not st.session_state.households:
        st.info("No household is attached to this account yet.")
     
def render_docs_tab() -> None:
    is_indexing = st.session_state.get("document_indexing", False)
    try:
        entries = load_saved_documents()
    except Exception as exc:
        st.error(f"Could not load saved documents: {exc}")
        entries = []

    if st.session_state.docs_status:
        status_level = st.session_state.docs_status_level
        if status_level == "success":
            st.success(st.session_state.docs_status)
        elif status_level == "error":
            st.error(st.session_state.docs_status)
        else:
            st.info(st.session_state.docs_status)

    saved_doc_labels = [
        f"{entry['display_name']} ({entry['entry_id']})"
        for entry in entries
    ]
    saved_docs_by_label = {
        f"{entry['display_name']} ({entry['entry_id']})": entry
        for entry in entries
    }

    doc_types = {
        "product/user manual": "manual",
        "warranty, permit, or receipt": "warranty",
    }

    manual_count = sum(1 for entry in entries if entry["doc_type"] == "manual")
    coverage_count = len(entries) - manual_count
    left_col, right_col = st.columns(
        [1.5, 1],
        gap="large",
        vertical_alignment="top",
    )

    with left_col:
        # Keep the introduction in the left column so the add-document panel
        # can share its top edge instead of starting below the page heading.
        st.markdown(
            """
            <div class="hb-docs-intro">
                <div class="hb-page-kicker">Documents</div>
                <div class="hb-page-title">Save Docs</div>
                <div class="hb-page-subtitle">Add manuals, warranties, permits, and receipts so Home Buddy can cite them when it answers.</div>
            </div>
            """,
            unsafe_allow_html=True,
        )

        stat_a, stat_b, stat_c = st.columns(3)
        with stat_a:
            st.markdown(
                f"""
                <div class="hb-stat-card">
                    <div class="hb-stat-value">{len(entries)}</div>
                    <div class="hb-stat-label">Saved Docs</div>
                </div>
                """,
                unsafe_allow_html=True,
            )
        with stat_b:
            st.markdown(
                f"""
                <div class="hb-stat-card">
                    <div class="hb-stat-value">{manual_count}</div>
                    <div class="hb-stat-label">Manuals</div>
                </div>
                """,
                unsafe_allow_html=True,
            )
        with stat_c:
            st.markdown(
                f"""
                <div class="hb-stat-card">
                    <div class="hb-stat-value hb-stat-value-alt">{coverage_count}</div>
                    <div class="hb-stat-label">Coverage Docs</div>
                </div>
                """,
                unsafe_allow_html=True,
            )

        st.markdown('<div class="hb-stack-gap"></div>', unsafe_allow_html=True)
        st.markdown('<div class="hb-section-label">Document Library</div>', unsafe_allow_html=True)

        if saved_doc_labels:
            for entry in entries:
                doc_col, action_col = st.columns([4.4, 1.1], gap="small")
                with doc_col:
                    is_manual = entry["doc_type"] == "manual"
                    pretty_type = "Manual" if is_manual else "Coverage"
                    tag_class = "hb-tag-accent" if is_manual else "hb-tag-accent-2"
                    st.markdown(
                        f"""
                        <div class="hb-doc-row">
                            <div class="hb-doc-icon">{icon("file", 20)}</div>
                            <div style="min-width: 0; flex: 1;">
                                <div class="hb-doc-name">{esc(entry['display_name'])}</div>
                                <div class="hb-doc-meta">entry id: {esc(entry['entry_id'])}</div>
                            </div>
                            <span class="hb-tag {tag_class}">{pretty_type}</span>
                        </div>
                        """,
                        unsafe_allow_html=True,
                    )
                with action_col:
                    if st.button(
                        "Delete",
                        key=f"prompt_delete_doc_{entry['entry_id']}",
                        use_container_width=True,
                        disabled=is_indexing,
                    ):
                        # Store raw values: esc() is display-only, and the entry_id here is
                        # sent back to the delete endpoint, which must match the DB exactly.
                        st.session_state.pending_document_delete_entry_id = entry["entry_id"]
                        st.session_state.pending_document_delete_label = entry["display_name"]
                        st.session_state.docs_status = None
                        st.session_state.docs_status_level = None
                        st.rerun()
        else:
            st.markdown(
                """
                <div class="hb-card hb-empty-card">
                    <div class="hb-card-title">No saved documents yet</div>
                    <div class="hb-card-body">Add a manual, warranty, permit, or receipt to start grounding Home Buddy's answers.</div>
                </div>
                """,
                unsafe_allow_html=True,
            )

        if st.session_state.pending_document_delete_entry_id:
            st.warning(
                f"Delete saved document '{st.session_state.pending_document_delete_label}'? "
                "This will remove both the document metadata and its indexed chunks."
            )
            confirm_col, cancel_col = st.columns(2)
            if confirm_col.button(
                "Confirm Delete Document",
                key="confirm_delete_single_document",
                use_container_width=True,
                disabled=is_indexing,
            ):
                try:
                    deleted_entry_id = st.session_state.pending_document_delete_entry_id
                    delete_single_document(deleted_entry_id)
                    st.session_state.docs_status = "Document deleted."
                    st.session_state.docs_status_level = "success"
                    st.session_state.indexed_docs = [
                        doc
                        for doc in st.session_state.indexed_docs
                        if doc["entry_id"] != deleted_entry_id
                    ]
                    st.session_state.pending_document_delete_entry_id = None
                    st.session_state.pending_document_delete_label = None
                    st.rerun()
                except Exception as exc:
                    st.session_state.docs_status = f"Error deleting document: {exc}"
                    st.session_state.docs_status_level = "error"
                    st.rerun()

            if cancel_col.button(
                "Cancel",
                key="cancel_delete_single_document",
                use_container_width=True,
                disabled=is_indexing,
            ):
                st.session_state.pending_document_delete_entry_id = None
                st.session_state.pending_document_delete_label = None
                st.rerun()

        if entries and st.button(
            "Clear All Saved Docs",
            key="prompt_clear_all_docs",
            use_container_width=True,
            disabled=is_indexing,
        ):
            st.session_state.confirm_clear_all_docs = True
            st.session_state.docs_status = None
            st.session_state.docs_status_level = None
            st.rerun()

        if st.session_state.confirm_clear_all_docs:
            st.error(
                "This will remove every saved document for the active household, along with all indexed chunks. "
                "Click confirm only if you want to permanently clear the document library."
            )
            clear_confirm_col, clear_cancel_col = st.columns(2)
            if clear_confirm_col.button(
                "Confirm Clear All Docs",
                key="confirm_clear_all_docs_button",
                use_container_width=True,
                disabled=is_indexing,
            ):
                try:
                    delete_all_documents()
                    st.session_state.docs_status = "All saved documents deleted."
                    st.session_state.docs_status_level = "success"
                    st.session_state.confirm_clear_all_docs = False
                    st.session_state.indexed_docs = []
                    st.rerun()
                except Exception as exc:
                    st.session_state.docs_status = f"Error deleting documents: {exc}"
                    st.session_state.docs_status_level = "error"
                    st.rerun()

            if clear_cancel_col.button(
                "Keep Documents",
                key="cancel_clear_all_docs_button",
                use_container_width=True,
                disabled=is_indexing,
            ):
                st.session_state.confirm_clear_all_docs = False
                st.rerun()

    with right_col:
        # A keyed container carries the panel styling. An unmatched open/close
        # st.markdown div pair would instead render as its own hollow box.
        with st.container(key="hb_docs_form"):
            st.markdown(
                """
                <div class="hb-ground-kicker">Add a document</div>
                <div class="hb-card-meta" style="margin-bottom: 0.85rem;">Home Buddy indexes it for retrieval as soon as it lands.</div>
                """,
                unsafe_allow_html=True,
            )

            with st.form("add_url_document_form", clear_on_submit=True):
                new_name = st.text_input("Document name", key="new_entry_name", disabled=is_indexing)
                new_url = st.text_input("PDF URL", key="new_download_url", disabled=is_indexing)
                selected_doc_type = st.selectbox(
                    "Document type",
                    options=["— select —"] + list(doc_types.keys()),
                    key="url_doc_type",
                    disabled=is_indexing,
                )
                add_url_document = st.form_submit_button(
                    "Add and index", use_container_width=True, type="primary", disabled=is_indexing
                )

            st.markdown('<div class="hr"></div>', unsafe_allow_html=True)

            with st.form("upload_document_form", clear_on_submit=True):
                uploaded_doc_name = st.text_input(
                    "Document name", key="upload_entry_name", disabled=is_indexing
                )
                uploaded_file = st.file_uploader(
                    "Upload a PDF directly", type="pdf", disabled=is_indexing
                )
                selected_upload_doc_type = st.selectbox(
                    "Upload document type",
                    options=["— select —"] + list(doc_types.keys()),
                    key="upload_doc_type",
                    disabled=is_indexing,
                )
                upload_document = st.form_submit_button(
                    "Upload and index", use_container_width=True, disabled=is_indexing
                )

    url_doc_type = doc_types.get(selected_doc_type)
    upload_doc_type = doc_types.get(selected_upload_doc_type)

    if add_url_document:
        if not new_name.strip() or not new_url.strip():
            st.warning("Enter both a document name and PDF URL.")
        else:
            doc_name = new_name.strip()
            doc_url = new_url.strip()
            doc_entry_id = build_entry_id(doc_name, entries, download_url=doc_url)
            begin_document_index(
                {
                    "source": "url",
                    "display_name": doc_name,
                    "entry_id": doc_entry_id,
                    "url": doc_url,
                    "doc_type": url_doc_type,
                }
            )
            st.rerun()

    if upload_document:
        if uploaded_file is None:
            st.warning("Choose a PDF file first.")
        else:
            doc_name = (uploaded_doc_name or "").strip() or uploaded_file.name
            doc_entry_id = build_entry_id(doc_name, entries)
            begin_document_index(
                {
                    "source": "upload",
                    "display_name": doc_name,
                    "entry_id": doc_entry_id,
                    "doc_type": upload_doc_type,
                    "filename": uploaded_file.name,
                    "content": uploaded_file.getvalue(),
                    "content_type": uploaded_file.type or "application/pdf",
                }
            )
            st.rerun()

    if is_indexing:
        finish_pending_document_index()


def queue_chat_question(question: str | None = None) -> None:
    """Commit a typed or suggested question before Streamlit rerenders widgets."""
    if st.session_state.get("document_indexing", False):
        return

    candidate = question if question is not None else st.session_state.get("agent_input_top", "")
    candidate = str(candidate or "").strip()
    if not candidate:
        return

    st.session_state.pending_chat_question = candidate
    st.session_state.chat_processing = True
    # Callback-time mutation is safe and leaves a clean composer after the
    # pending turn has been captured.
    st.session_state.agent_input_top = ""


def render_chat_tab() -> None:
    st.markdown('<div class="hb-page-kicker">Assistant</div>', unsafe_allow_html=True)
    st.markdown('<div class="hb-page-title">Chat</div>', unsafe_allow_html=True)
    st.markdown(
        '<div class="hb-page-subtitle">Ask Home Buddy about troubleshooting, coverage, safety, reminders, and household operations. Answers are grounded in the documents you have saved.</div>',
        unsafe_allow_html=True,
    )

    try:
        persisted_messages = load_conversation_messages(st.session_state.session_id)
        persisted_agent_messages = [
            {"role": message["role"], "content": message["content"]}
            for message in persisted_messages
        ]
        st.session_state.agent_messages = merge_agent_messages(
            persisted_agent_messages,
            st.session_state.agent_messages,
        )
    except Exception as exc:
        st.warning(f"Could not load conversation history: {exc}")

    saved_docs = load_saved_documents()
    monogram = initials(st.session_state.current_user)

    if saved_docs:
        st.markdown(
            f'<div class="hb-chat-note">{icon("check", 16)}Home Buddy will answer from your saved documents and cite what it used.</div>',
            unsafe_allow_html=True,
        )
    elif not st.session_state.agent_messages:
        st.markdown(
            """
            <div class="hb-card hb-empty-card">
                <div class="hb-card-title">Start with a real household question</div>
                <div class="hb-card-body">Try troubleshooting a device, asking about warranty coverage, checking a safety concern, or requesting a reminder or contractor recommendation.</div>
            </div>
            """,
            unsafe_allow_html=True,
        )

    # ---- thread ---------------------------------------------------------
    # Oldest first, so the newest turn sits closest to the composer below.
    latest_turn = st.session_state.get("last_agent_turn")
    conversation_pairs = build_display_conversation_pairs(
        st.session_state.agent_messages,
        latest_turn,
    )

    for index, (user_message, assistant_message) in enumerate(conversation_pairs):
        if user_message:
            st.markdown(render_user_bubble(user_message, monogram), unsafe_allow_html=True)

        if not assistant_message:
            continue

        is_latest_turn = bool(
            latest_turn
            and user_message == latest_turn.get("question")
            and assistant_message == latest_turn.get("answer")
        )

        if is_latest_turn and is_urgent_turn(latest_turn):
            # Urgent turns render as the safety card: the numbered steps come
            # straight from the backend's hazard assessment.
            st.markdown(
                render_safety_bubble(assistant_message, latest_turn.get("steps") or []),
                unsafe_allow_html=True,
            )
        else:
            with st.container(key=f"hb_answer_{index}"):
                st.markdown(assistant_message)
                if is_latest_turn and latest_turn.get("retrieval_context"):
                    source_count = len(latest_turn["retrieval_context"])
                    passage_noun = "passage" if source_count == 1 else "passages"
                    st.markdown(
                        f'<span class="hb-source-chip">{icon("file-min", 14)}'
                        f"Grounded in {source_count} saved-document {passage_noun}</span>",
                        unsafe_allow_html=True,
                    )

        if is_latest_turn:
            render_contractor_suggestions(latest_turn.get("contractor_suggestions") or [])
            render_case_draft_hitl()
            render_task_draft_hitl()

    if latest_turn is None:
        render_case_draft_hitl()
        render_task_draft_hitl()

    # ---- in-flight turn -------------------------------------------------
    pending_question = st.session_state.get("pending_chat_question")
    if pending_question:
        st.markdown(render_user_bubble(pending_question, monogram), unsafe_allow_html=True)

        status_placeholder = st.empty()
        answer_placeholder = st.empty()
        try:
            status_placeholder.markdown(render_streaming_status(), unsafe_allow_html=True)
            progress_messages: list[str] = []
            final_result = None
            streamed_answer = ""
            for event in stream_query(
                {
                    "question": pending_question,
                    "session_id": st.session_state.session_id,
                    "household_id": st.session_state.active_household_id,
                    "household_zip_code": st.session_state.active_household_zip,
                }
            ):
                event_type = event.get("type")
                if event_type == "status":
                    progress_messages.append(event["message"])
                    status_placeholder.markdown(
                        render_streaming_status(progress_messages),
                        unsafe_allow_html=True,
                    )
                elif event_type == "token":
                    streamed_answer += event.get("text", "")
                    answer_placeholder.markdown(streamed_answer)
                elif event_type == "final":
                    final_result = event["result"]
                elif event_type == "error":
                    raise RuntimeError(event["message"])

            if final_result is None:
                raise RuntimeError("Streaming query completed without a final result.")

            answer = final_result["answer"]
            question_for_history = final_result.get("sanitized_query") or pending_question
            st.session_state.pending_case_draft = final_result.get("case_draft")
            st.session_state.edit_case_draft = False
            st.session_state.case_draft_status = None
            st.session_state.case_draft_status_level = None

            st.session_state.pending_task_draft = final_result.get("task_draft")
            st.session_state.edit_task_draft = False
            st.session_state.task_draft_status = None
            st.session_state.task_draft_status_level = None
            contractor_suggestions = final_result.get("contractor_suggestions", [])
            st.session_state.last_agent_turn = {
                "question": question_for_history,
                "answer": answer,
                "contractor_suggestions": contractor_suggestions,
                "case_draft": final_result.get("case_draft"),
                "task_draft": final_result.get("task_draft"),
                # Carried so the answer bubble can show a truthful grounding chip
                # and urgent turns can render the safety card.
                "retrieval_context": final_result.get("retrieval_context") or [],
                "steps": final_result.get("steps") or [],
                "urgency_level": final_result.get("urgency_level"),
                "should_escalate": final_result.get("should_escalate", False),
            }

            status_placeholder.empty()
            answer_placeholder.empty()
            st.session_state.agent_messages.append({"role": "user", "content": question_for_history})
            st.session_state.agent_messages.append({"role": "assistant", "content": answer})
            st.session_state.pending_chat_question = None
            st.session_state.chat_processing = False
            st.rerun()
        except Exception as exc:
            st.session_state.chat_processing = False
            st.session_state.pending_chat_question = None
            status_placeholder.empty()
            st.error(str(exc))

    # ---- composer -------------------------------------------------------
    is_processing = st.session_state.get("chat_processing", False)

    if not is_processing:
        st.markdown('<div class="hb-suggestions"></div>', unsafe_allow_html=True)
        suggestion_cols = st.columns(len(CHAT_SUGGESTIONS))
        for col, suggestion in zip(suggestion_cols, CHAT_SUGGESTIONS):
            with col:
                st.button(
                    suggestion,
                    key=f"suggest_{suggestion}",
                    use_container_width=True,
                    on_click=queue_chat_question,
                    args=(suggestion,),
                )

    with st.form("agent_query_form"):
        # Input and Send sit on one row so the composer reads as a single pill.
        input_col, send_col = st.columns([6, 1], gap="small", vertical_alignment="bottom")
        with input_col:
            st.text_input(
                "Ask HomeBuddy",
                key="agent_input_top",
                placeholder="Ask about a manual, a warranty, or something that just broke",
                disabled=is_processing,
                label_visibility="collapsed",
            )
        with send_col:
            st.form_submit_button(
                "Send",
                use_container_width=True,
                type="primary",
                disabled=is_processing,
                on_click=queue_chat_question,
            )

    st.markdown(
        '<div class="hb-chat-hint">Answers are grounded in your saved documents and name what they were drawn from.</div>',
        unsafe_allow_html=True,
    )

    if st.session_state.agent_messages:
        if st.button("Clear agent conversation", key="clear_chat_button", use_container_width=True):
            try:
                clear_conversation_messages(st.session_state.session_id)
                st.session_state.agent_messages = []
                st.session_state.pending_task_draft = None
                st.session_state.edit_task_draft = False
                st.session_state.task_draft_status = None
                st.session_state.task_draft_status_level = None
                st.session_state.pending_case_draft = None
                st.session_state.edit_case_draft = False
                st.session_state.case_draft_status = None
                st.session_state.case_draft_status_level = None
                st.session_state.last_agent_turn = None
                st.rerun()
            except Exception as exc:
                st.error(f"Could not clear conversation history: {exc}")


def render_tasks_tab() -> None:
    st.markdown('<div class="hb-page-kicker">Tasks</div>', unsafe_allow_html=True)
    st.markdown('<div class="hb-page-title">My Tasks</div>', unsafe_allow_html=True)
    st.markdown(
        '<div class="hb-page-subtitle">Review, update, and delete household tasks.</div>',
        unsafe_allow_html=True,
    )

    try:
        tasks = load_tasks()
    except Exception as exc:
        st.error(f"Could not load tasks: {exc}")
        return

    if not tasks:
        st.info("No saved tasks yet.")
        return

    for task in tasks:
        # Use a bordered container-like pattern so each task reads as a distinct record without requiring a full redesign.
        with st.container():
            st.markdown(f"### {task['title']}")
            st.markdown(f"**Notes:** {task.get('notes', '')}")
            st.markdown(f"**Due date:** {task.get('due_date', '')}")
            action_col_1, action_col_2 = st.columns(2)

            # Open an inline edit form for the selected persisted task.
            if action_col_1.button("Edit Task", key=f"edit_task_{task['id']}", use_container_width=True):
                st.session_state.editing_task_id = task["id"]
                st.rerun()

            # Delete immediately removes the persisted record from the backend and refreshes the list.
            if action_col_2.button("Delete Task", key=f"delete_task_{task['id']}", use_container_width=True):
                try:
                    delete_task_record(task["id"])
                    st.session_state.task_status = f"Task deleted: {task['title']}"
                    st.session_state.tasks_status_level = "success"
                    if st.session_state.editing_task_id == task["id"]:
                        st.session_state.editing_task_id = None
                    st.rerun()
                except Exception as exc:
                    st.session_state.tasks_status = f"Could not delete task: {exc}"
                    st.session_state.tasks_status_level = "error"
                    st.rerun()

            if st.session_state.editing_task_id == task["id"]:
                with st.form(f"edit_saved_task_form_{task['id']}"):
                    # Seed the form with the current task values so edits behave like record maintenance, not draft review.
                    edited_title = st.text_input("Title", value=task.get("title", ""), key=f"saved_task_title_{task['id']}")
                    edited_notes = st.text_input(
                        "Notes",
                        value=task.get("notes") or "",
                        key=f"saved_notes_{task['id']}",
                    )
                    edited_due_date = st.date_input(
                        "Due date (optional)",
                        value=None,
                        key=f"saved_task_due_date_{task['id']}",
                    )
                    save_task_edit = st.form_submit_button("Save Changes", use_container_width=True)

                    if save_task_edit:
                        try:
                            updated_task = update_task_record(
                                task["id"],
                                {
                                    "title": edited_title,
                                    "notes": edited_notes,
                                    "due_date": edited_due_date.isoformat() if edited_due_date else None
                                },
                            )
                            st.session_state.task_status = f"Task updated: {updated_task['title']}"
                            st.session_state.tasks_status_level = "success"
                            st.session_state.editing_task_id = None
                            st.rerun()
                        except Exception as exc:
                            st.session_state.tasks_status = f"Could not update task: {exc}"
                            st.session_state.tasks_status_level = "error"
                            st.rerun()
            st.divider()

def render_cases_tab() -> None:
        st.markdown('<div class="hb-page-kicker">Cases</div>', unsafe_allow_html=True)
        st.markdown('<div class="hb-page-title">My Cases</div>', unsafe_allow_html=True)
        st.markdown(
            '<div class="hb-page-subtitle">Issues Home Buddy has flagged or you have asked it to track.</div>',
            unsafe_allow_html=True,
        )

        try:
            cases = load_cases()
        except Exception as exc:
            st.error(f"Could not load cases: {exc}")
            return

        if not cases:
            st.info("No saved cases yet.")
            return

        for case in cases:
            # Use a bordered container-like pattern so each case reads as a distinct record without requiring a full redesign.
            with st.container():
                st.markdown(f"### {case['title']}")
                case_meta_left, case_meta_right, case_meta_third = st.columns(3)
                case_meta_left.markdown(f'<span class="hb-tag hb-tag-neutral">{esc(case.get("status")) or "unknown"}</span>', unsafe_allow_html=True)
                case_meta_right.markdown(f'<span class="hb-tag hb-tag-accent">{esc(case.get("severity")) or "unknown"}</span>', unsafe_allow_html=True)
                case_meta_third.markdown(f"**Trade:** {case.get('contractor_trade') or '—'}")
                st.markdown(f"**Summary:** {case.get('summary') or '—'}")
                action_col_1, action_col_2 = st.columns(2)

                # Open an inline edit form for the selected persisted case.
                if action_col_1.button("Edit Case", key=f"edit_case_{case['id']}", use_container_width=True):
                    st.session_state.editing_case_id = case["id"]
                    st.rerun()

                # Delete immediately removes the persisted record from the backend and refreshes the list.
                if action_col_2.button("Delete Case", key=f"delete_case_{case['id']}", use_container_width=True):
                    try:
                        delete_case_record(case["id"])
                        st.session_state.cases_status = f"Case deleted: {case['title']}"
                        st.session_state.cases_status_level = "success"
                        if st.session_state.editing_case_id == case["id"]:
                            st.session_state.editing_case_id = None
                        st.rerun()
                    except Exception as exc:
                        st.session_state.cases_status = f"Could not delete case: {exc}"
                        st.session_state.cases_status_level = "error"
                        st.rerun()

                if st.session_state.editing_case_id == case["id"]:
                    with st.form(f"edit_saved_case_form_{case['id']}"):
                        # Seed the form with the current case values so edits behave like record maintenance, not draft review.
                        edited_title = st.text_input("Title", value=case.get("title", ""), key=f"saved_case_title_{case['id']}")
                        edited_summary = st.text_area("Summary", value=case.get("summary", ""), key=f"saved_case_summary_{case['id']}")
                        severity_options = ["low", "medium", "high", "critical"]
                        status_options = ["open", "pending", "closed"]
                        edited_severity = st.selectbox(
                            "Severity",
                            options=severity_options,
                            index=severity_options.index(case.get("severity", "medium")) if case.get("severity", "medium") in severity_options else 1,
                            key=f"saved_case_severity_{case['id']}",
                        )
                        edited_status = st.selectbox(
                            "Status",
                            options=status_options,
                            index=status_options.index(case.get("status", "open")) if case.get("status", "open") in status_options else 0,
                            key=f"saved_case_status_{case['id']}",
                        )
                        edited_trade = st.text_input(
                            "Contractor trade",
                            value=case.get("contractor_trade") or "",
                            key=f"saved_case_trade_{case['id']}",
                        )
                        save_case_edit = st.form_submit_button("Save Changes", use_container_width=True)

                        if save_case_edit:
                            try:
                                updated_case = update_case_record(
                                    case["id"],
                                    {
                                        "title": edited_title,
                                        "summary": edited_summary,
                                        "severity": edited_severity,
                                        "contractor_trade": edited_trade or None,
                                        "status": edited_status,
                                    },
                                )
                                st.session_state.cases_status = f"Case updated: {updated_case['title']}"
                                st.session_state.cases_status_level = "success"
                                st.session_state.editing_case_id = None
                                st.rerun()
                            except Exception as exc:
                                st.session_state.cases_status = f"Could not update case: {exc}"
                                st.session_state.cases_status_level = "error"
                                st.rerun()
                st.divider()


# ---------------------------------------------------------------------------
# Page config
# ---------------------------------------------------------------------------

st.set_page_config(
    page_title="Home Buddy",
    page_icon="🏠",
    layout="wide",
    initial_sidebar_state="expanded",
)

st.markdown("""
<style>
    @import url('https://fonts.googleapis.com/css2?family=Caprasimo&family=Figtree:wght@400;500;600;700&display=swap');

    /* ---------------------------------------------------------------------
       Organic design system tokens. This block is the source of truth for the
       look; retune here rather than hard-coding colors further down.
       --------------------------------------------------------------------- */
    :root {
        --color-bg: #f5ead8;
        --color-surface: #ebddc5;
        --color-text: #201e1d;
        --color-accent: #c67139;
        --color-accent-2: #7a8a5e;
        --color-divider: color-mix(in srgb, #201e1d 16%, transparent);

        --color-neutral-100: #f9f4ed;
        --color-neutral-200: #eee7db;
        --color-neutral-300: #dcd3c4;
        --color-neutral-400: #c0b6a5;
        --color-neutral-500: #a19786;
        --color-neutral-600: #82796a;
        --color-neutral-700: #645c50;
        --color-neutral-800: #474238;
        --color-neutral-900: #2e2b25;

        --color-accent-100: #fff2eb;
        --color-accent-200: #ffe1d0;
        --color-accent-300: #ffc6a5;
        --color-accent-400: #f6a06b;
        --color-accent-500: #d67f48;
        --color-accent-600: #b2622d;
        --color-accent-700: #8c491a;
        --color-accent-800: #643312;
        --color-accent-900: #402310;

        --color-accent-2-100: #f0fae1;
        --color-accent-2-200: #e1eecc;
        --color-accent-2-300: #ccdbb2;
        --color-accent-2-400: #aebf92;
        --color-accent-2-500: #8fa073;
        --color-accent-2-600: #728157;
        --color-accent-2-700: #56633f;
        --color-accent-2-800: #3d472b;
        --color-accent-2-900: #272e1b;

        --font-heading: "Caprasimo", system-ui, sans-serif;
        --font-body: "Figtree", system-ui, sans-serif;

        --radius-md: 16px;
        --radius-lg: 28px;

        --shadow-sm: 0 1px 2px color-mix(in srgb, #2e2b25 14%, transparent);
        --shadow-md: 0 3px 10px color-mix(in srgb, #2e2b25 16%, transparent);
        --shadow-lg: 0 12px 32px color-mix(in srgb, #2e2b25 22%, transparent);

        /* Aliases so any older hb-* rule still resolves against the new palette. */
        --hb-bg: var(--color-bg);
        --hb-surface: var(--color-surface);
        --hb-card: var(--color-neutral-100);
        --hb-border: var(--color-divider);
        --hb-text: var(--color-text);
        --hb-muted: color-mix(in srgb, var(--color-text) 60%, transparent);
        --hb-accent: var(--color-accent);
        --hb-accent-soft: var(--color-accent-100);
    }

    html, body, [class*="css"] {
        font-family: var(--font-body);
        color: var(--color-text);
    }

    h1, h2, h3, h4, h5, h6 {
        font-family: var(--font-heading);
        font-weight: 400;
        letter-spacing: -0.015em;
        line-height: 1.12;
    }

    /* The ground: warm page field with two soft accent blooms, matching the
       decorative circles in the canvas. Pinned + non-interactive so they never
       intercept clicks on Streamlit widgets. */
    [data-testid="stAppViewContainer"] {
        background: var(--color-bg);
        position: relative;
    }

    [data-testid="stAppViewContainer"]::before,
    [data-testid="stAppViewContainer"]::after {
        content: "";
        position: fixed;
        border-radius: 50%;
        pointer-events: none;
        z-index: 0;
    }

    [data-testid="stAppViewContainer"]::before {
        top: -180px;
        right: -140px;
        width: 620px;
        height: 620px;
        background: radial-gradient(circle at 40% 40%, color-mix(in srgb, var(--color-accent-300) 60%, transparent), transparent 68%);
    }

    [data-testid="stAppViewContainer"]::after {
        bottom: -220px;
        left: -120px;
        width: 560px;
        height: 560px;
        background: radial-gradient(circle at 60% 40%, color-mix(in srgb, var(--color-accent-2-300) 55%, transparent), transparent 66%);
    }

    /* The Streamlit toolbar floats over the designed canvas and creates a
       phantom top-right control cluster. The app supplies its own navigation
       and account actions, so remove the framework chrome from the surface. */
    [data-testid="stHeader"] {
        background: transparent;
        height: 0;
        min-height: 0;
    }

    [data-testid="stToolbar"],
    [data-testid="stDecoration"] {
        display: none !important;
    }
    [data-testid="stSidebar"] { display: none; }

    .block-container {
        max-width: 1440px;
        padding-top: 1.75rem;
        padding-bottom: 2rem;
        position: relative;
        z-index: 1;
    }

    /* --- app shell ----------------------------------------------------- */

    .st-key-hb_shell {
        background: var(--color-neutral-200);
        border-radius: 34px;
        padding: 1.5rem 1.75rem 1.75rem;
        box-shadow: var(--shadow-lg);
    }

    .st-key-hb_rail {
        background: var(--color-surface);
        border-radius: 34px;
        padding: 1.4rem 1.15rem;
        box-shadow: var(--shadow-md);
        gap: 0.65rem !important;
        align-self: flex-start !important;
        margin-top: 0 !important;
    }

    /* Streamlit stretches each column to the height of the tallest sibling.
       Keep the shorter navigation rail pinned to the top of that row instead
       of letting it center itself beside a tall Documents page. */
    [data-testid="stHorizontalBlock"]:has(.st-key-hb_rail) {
        align-items: flex-start !important;
    }

    [data-testid="stColumn"]:has(.st-key-hb_rail) {
        align-self: flex-start !important;
        justify-content: flex-start !important;
    }

    [data-testid="stColumn"]:has(.st-key-hb_rail)
    [data-testid="stVerticalBlock"] {
        justify-content: flex-start !important;
    }

    .st-key-rail_sign_out {
        margin-top: 1rem !important;
    }

    /* --- brand lockup -------------------------------------------------- */

    .hb-brand {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 1.35rem;
    }

    .hb-brand-mark {
        width: 40px;
        height: 40px;
        flex: none;
        border-radius: 50%;
        background: var(--color-accent);
        color: var(--color-bg);
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: var(--shadow-sm);
    }

    .hb-brand-mark svg { display: block; }

    .hb-brand-name {
        font-family: var(--font-heading);
        font-size: 1.25rem;
        line-height: 1.1;
    }

    .hb-brand-sub {
        font-size: 0.75rem;
        color: var(--hb-muted);
    }

    .hb-rail-title {
        font-family: var(--font-heading);
        font-size: 1.25rem;
        line-height: 1.1;
    }

    .hb-rail-subtitle {
        color: var(--hb-muted);
        font-size: 0.75rem;
        margin-bottom: 0.9rem;
    }

    .hb-rail-label {
        font-size: 0.625rem;
        letter-spacing: 0.12em;
        text-transform: uppercase;
        color: color-mix(in srgb, var(--color-text) 50%, transparent);
        margin: 0 0 0.5rem 0.875rem;
    }

    /* Rail nav pills: active state comes from Streamlit's primary button kind,
       so the selected view reads as a filled accent pill. */
    [class*="st-key-nav_"] > div > button {
        display: flex;
        align-items: center;
        justify-content: flex-start;
        gap: 12px;
        border: 0 !important;
        border-radius: 999px !important;
        background: transparent !important;
        color: var(--color-text) !important;
        font-family: var(--font-body) !important;
        font-size: 0.94rem !important;
        font-weight: 500 !important;
        min-height: 46px;
        padding: 0 1rem;
        box-shadow: none !important;
    }

    /* Streamlit wraps the label in two nested flex boxes that both center it;
       the rail wants it flush left against the icon. */
    [class*="st-key-nav_"] > div > button > div,
    [class*="st-key-nav_"] > div > button > div > span {
        justify-content: flex-start !important;
        text-align: left !important;
    }

    [class*="st-key-nav_"] > div > button p {
        text-align: left !important;
        margin: 0;
    }

    /* Nav icons are masked SVGs painted with currentColor, so they invert
       automatically when the pill becomes the filled active state. */
    [class*="st-key-nav_"] > div > button::before {
        content: "";
        width: 20px;
        height: 20px;
        flex: none;
        background-color: currentColor;
        -webkit-mask: var(--nav-icon) center / 20px 20px no-repeat;
        mask: var(--nav-icon) center / 20px 20px no-repeat;
    }

    .st-key-nav_chat > div > button {
        --nav-icon: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.75' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M7.9 20A9 9 0 1 0 4 16.1L2 22Z'/%3E%3C/svg%3E");
    }

    .st-key-nav_docs > div > button {
        --nav-icon: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.75' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z'/%3E%3C/svg%3E");
    }

    .st-key-nav_profile > div > button {
        --nav-icon: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.75' stroke-linecap='round' stroke-linejoin='round'%3E%3Ccircle cx='12' cy='8' r='5'/%3E%3Cpath d='M20 21a8 8 0 0 0-16 0'/%3E%3C/svg%3E");
    }

    .st-key-nav_cases > div > button {
        --nav-icon: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.75' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z'/%3E%3Cpath d='M14 2v4a2 2 0 0 0 2 2h4'/%3E%3C/svg%3E");
    }

    .st-key-nav_tasks > div > button {
        --nav-icon: url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.75' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6 9 17l-5-5'/%3E%3C/svg%3E");
    }

    [class*="st-key-nav_"] > div > button:hover {
        background: color-mix(in srgb, var(--color-text) 7%, transparent) !important;
    }

    [class*="st-key-nav_"] > div > button[kind="primary"] {
        background: var(--color-accent) !important;
        color: var(--color-bg) !important;
        font-weight: 600 !important;
        box-shadow: var(--shadow-md) !important;
    }

    [class*="st-key-nav_"] > div > button[kind="primary"]:hover {
        background: var(--color-accent-600) !important;
    }

    /* --- grounding card + account chip in the rail --------------------- */

    .hb-ground-card {
        padding: 1rem;
        border-radius: 24px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-sm);
        margin-top: 0;
        margin-bottom: 1rem
    }

    .hb-ground-kicker {
        font-size: 0.625rem;
        letter-spacing: 0.12em;
        text-transform: uppercase;
        color: var(--color-accent-700);
        margin-bottom: 0.4rem;
    }

    .hb-ground-body {
        font-size: 0.875rem;
        line-height: 1.4;
    }

    .hb-account-chip {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 10px 12px;
        border-radius: 999px;
        background: color-mix(in srgb, var(--color-text) 5%, transparent);
        margin-top: 0;
    }

    .hb-avatar {
        width: 32px;
        height: 32px;
        flex: none;
        border-radius: 50%;
        background: var(--color-accent-2);
        color: var(--color-bg);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 0.8rem;
        font-weight: 700;
    }

    .hb-avatar-lg {
        width: 62px;
        height: 62px;
        font-size: 1.25rem;
    }

    .hb-account-email {
        font-size: 0.75rem;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: color-mix(in srgb, var(--color-text) 70%, transparent);
    }

    /* --- top bar ------------------------------------------------------- */

    .hb-topbar {
        display: flex;
        align-items: center;
        gap: 12px;
        min-height: 58px;
        padding: 0 0.25rem 0.9rem;
        margin-bottom: 1rem;
        border-bottom: 1px solid var(--color-divider);
        flex-wrap: wrap;
    }

    .hb-crumb {
        font-size: 0.875rem;
        color: color-mix(in srgb, var(--color-text) 60%, transparent);
    }

    .hb-crumb-current {
        font-size: 0.875rem;
        font-weight: 600;
    }

    .hb-topbar-spacer { margin-left: auto; }

    /* --- page headings ------------------------------------------------- */

    .hb-page-kicker {
        color: var(--color-accent-700);
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        margin-bottom: 0.4rem;
    }

    .hb-page-title {
        font-family: var(--font-heading);
        font-size: 2.5rem;
        font-weight: 400;
        line-height: 1.12;
        letter-spacing: -0.015em;
        margin-bottom: 0.4rem;
    }

    .hb-page-subtitle {
        color: color-mix(in srgb, var(--color-text) 68%, transparent);
        font-size: 0.95rem;
        line-height: 1.6;
        margin-bottom: 1.4rem;
        max-width: 64ch;
    }

    .hb-section-label {
        color: var(--hb-muted);
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        margin: 0 0 0.65rem;
    }

    .main-header {
        font-family: var(--font-heading);
        font-weight: 400;
        color: var(--color-text);
        font-size: 3.25rem;
        line-height: 1.1;
        margin: 0;
    }

    .sub-header {
        color: color-mix(in srgb, var(--color-text) 68%, transparent);
        font-size: 0.96rem;
        margin: 0.4rem 0 0;
    }

    .hb-rule {
        width: 160px;
        height: 4px;
        background: var(--color-accent);
        border-radius: 999px;
        margin: 0.85rem 0 1.1rem;
    }

    .hb-header-wrap { margin-bottom: 0.75rem; }

    /* --- cards --------------------------------------------------------- */

    .hb-card {
        background: var(--color-neutral-100);
        border-radius: 26px;
        padding: 1.1rem 1.25rem;
        margin-bottom: 0.8rem;
        box-shadow: var(--shadow-sm);
    }

    .hb-form-card { padding-bottom: 0.5rem; }
    .hb-compact-card { padding: 0.95rem 1.1rem; }

    .hb-empty-card {
        background: var(--color-neutral-100);
        border: 1px dashed color-mix(in srgb, var(--color-text) 25%, transparent);
        box-shadow: none;
    }

    .hb-card-title {
        font-family: var(--font-heading);
        font-size: 1.05rem;
        font-weight: 400;
        line-height: 1.2;
        color: var(--color-text);
        margin-bottom: 0.25rem;
    }

    .hb-card-body,
    .hb-card-meta {
        color: var(--hb-muted);
        font-size: 0.875rem;
        line-height: 1.5;
    }

    /* --- stat tiles ---------------------------------------------------- */

    .hb-stat-card {
        background: var(--color-neutral-100);
        border-radius: 28px;
        padding: 1.35rem;
        box-shadow: var(--shadow-md);
    }

    .hb-stat-value {
        font-family: var(--font-heading);
        font-size: 2.35rem;
        font-weight: 400;
        line-height: 1.05;
        color: var(--color-accent);
    }

    .hb-stat-value-alt { color: var(--color-accent-2); }

    .hb-stat-label {
        font-size: 0.6875rem;
        color: var(--hb-muted);
        text-transform: uppercase;
        letter-spacing: 0.1em;
        margin-top: 0.25rem;
    }

    .hb-stack-gap { margin-top: 1rem; }

    /* --- chat thread --------------------------------------------------- */

    @keyframes hbPulse {
        0%, 100% { opacity: 0.35; transform: translateY(0); }
        50% { opacity: 1; transform: translateY(-2px); }
    }

    .hb-msg {
        display: flex;
        gap: 12px;
        align-items: flex-start;
        margin-bottom: 1.1rem;
    }

    .hb-msg-user {
        justify-content: flex-end;
        align-items: flex-end;
    }

    .hb-msg-avatar {
        width: 36px;
        height: 36px;
        flex: none;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: var(--shadow-sm);
        background: var(--color-neutral-100);
        color: var(--color-accent);
    }

    .hb-msg-avatar svg { display: block; }

    .hb-msg-avatar-user {
        background: var(--color-accent-2);
        color: var(--color-bg);
        font-size: 0.75rem;
        font-weight: 700;
    }

    .hb-msg-avatar-safety {
        background: var(--color-accent);
        color: var(--color-bg);
    }

    .hb-bubble-user {
        max-width: 62%;
        padding: 16px 22px;
        border-radius: 26px 26px 8px 26px;
        background: var(--color-accent);
        color: var(--color-bg);
        font-size: 0.95rem;
        line-height: 1.55;
        box-shadow: var(--shadow-md);
    }

    .hb-bubble-answer {
        max-width: 74%;
        padding: 18px 24px;
        border-radius: 8px 26px 26px 26px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-md);
    }

    .hb-bubble-answer p:last-child { margin-bottom: 0; }

    .hb-source-chip {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        margin: 0.85rem 0 0.2rem;
        padding: 8px 16px;
        border-radius: 999px;
        background: var(--color-bg);
        box-shadow: var(--shadow-sm);
        font-size: 0.75rem;
        max-width: 100%;
        white-space: normal;
        color: var(--color-accent-800);
    }

    .hb-source-chip svg { flex: none; }

    /* safety variant — urgent answers get the stop-and-do-this treatment */
    .hb-bubble-safety {
        max-width: 74%;
        border-radius: 8px 26px 26px 26px;
        background: var(--color-accent-100);
        box-shadow: var(--shadow-md);
        overflow: hidden;
    }

    .hb-safety-head {
        padding: 14px 24px;
        background: var(--color-accent-200);
        font-size: 0.6875rem;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        color: var(--color-accent-800);
    }

    .hb-safety-body {
        padding: 18px 24px 22px;
        color: var(--color-accent-900);
    }

    .hb-safety-body p:last-child { margin-bottom: 0; }

    .hb-step {
        display: flex;
        gap: 12px;
        align-items: flex-start;
        padding: 12px 16px;
        border-radius: 20px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-sm);
        margin-top: 0.6rem;
        color: var(--color-text);
    }

    .hb-step-n {
        width: 24px;
        height: 24px;
        flex: none;
        border-radius: 50%;
        background: var(--color-accent);
        color: var(--color-bg);
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 0.75rem;
        font-weight: 700;
    }

    .hb-step-text {
        font-size: 0.875rem;
        line-height: 1.5;
    }

    /* status card while the agent runs */
    .hb-chat-status {
        min-width: 340px;
        max-width: 74%;
        padding: 18px 22px;
        border-radius: 8px 26px 26px 26px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-md);
        margin-bottom: 1rem;
    }

    .hb-chat-status-title {
        display: flex;
        align-items: center;
        gap: 10px;
        color: var(--hb-muted);
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        margin-bottom: 0.65rem;
    }

    .hb-dots { display: inline-flex; gap: 4px; }

    .hb-dots span {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: var(--color-accent);
        animation: hbPulse 1.1s ease-in-out infinite;
    }

    .hb-dots span:nth-child(2) { animation-delay: 0.18s; }
    .hb-dots span:nth-child(3) { animation-delay: 0.36s; }

    .hb-status-line {
        display: flex;
        align-items: center;
        gap: 10px;
        font-size: 0.875rem;
        line-height: 1.5;
        color: color-mix(in srgb, var(--color-text) 78%, transparent);
        margin-top: 0.5rem;
    }

    .hb-status-tick {
        width: 20px;
        height: 20px;
        flex: none;
        border-radius: 50%;
        background: var(--color-accent-2-200);
        color: var(--color-accent-2-800);
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .hb-chat-status-body {
        color: var(--hb-muted);
        font-size: 0.9rem;
        line-height: 1.55;
    }

    .hb-chat-note {
        display: flex;
        align-items: center;
        gap: 10px;
        background: var(--color-accent-2-100);
        border-radius: 22px;
        padding: 0.9rem 1.15rem;
        color: var(--color-accent-2-900);
        font-size: 0.9rem;
        margin-bottom: 1rem;
    }

    .hb-chat-hint {
        font-size: 0.78rem;
        color: color-mix(in srgb, var(--color-text) 50%, transparent);
        padding-left: 0.4rem;
        margin-top: 0.35rem;
    }

    /* Assistant answers stay real Streamlit markdown (lists, bold, code all
       keep working), so the bubble is painted onto their keyed container and
       the avatar is drawn as a ::before rather than as sibling markup. */
    [class*="st-key-hb_answer_"] {
        position: relative;
        margin: 0 0 1.1rem 48px;
        max-width: 74%;
        padding: 18px 24px 30px;
        border-radius: 8px 26px 26px 26px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-md);
    }

    [class*="st-key-hb_answer_"]::before {
        content: "";
        position: absolute;
        left: -48px;
        top: 0;
        width: 36px;
        height: 36px;
        border-radius: 50%;
        box-shadow: var(--shadow-sm);
        background:
            var(--color-neutral-100)
            url("data:image/svg+xml;charset=utf-8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%23c67139' stroke-width='2.75' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z'/%3E%3C/svg%3E")
            center / 18px 18px no-repeat;
    }

    [class*="st-key-hb_answer_"] .stMarkdown p:last-child { margin-bottom: 0; }

    /* Suggestion chips above the composer */
    [class*="st-key-suggest_"] > div > button {
        border-radius: 999px !important;
        border: 1px solid var(--color-divider) !important;
        background: var(--color-neutral-100) !important;
        color: var(--color-text) !important;
        font-family: var(--font-body) !important;
        font-size: 0.8rem !important;
        font-weight: 400 !important;
        min-height: 38px;
        box-shadow: none !important;
    }

    [class*="st-key-suggest_"] > div > button:hover {
        background: var(--color-accent-100) !important;
        border-color: var(--color-accent-300) !important;
    }

    .hb-partial-block {
        background: var(--color-neutral-100);
        border-radius: 22px;
        padding: 0.95rem 1.1rem;
        margin-bottom: 0.9rem;
        box-shadow: var(--shadow-sm);
    }

    .hb-partial-title {
        color: var(--hb-muted);
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        margin-bottom: 0.55rem;
    }

    /* --- documents ----------------------------------------------------- */

    .hb-docs-intro .hb-page-subtitle {
        margin-bottom: 0.35rem;
    }

    /* Streamlit stretches sibling columns to the height of the document form
       and can vertically center the shorter library column. Pin both column
       stacks to the row start so corpus growth never moves either header. */
    [data-testid="stHorizontalBlock"]:has(.hb-docs-intro):has(.st-key-hb_docs_form) {
        align-items: flex-start !important;
    }

    [data-testid="stHorizontalBlock"]:has(.hb-docs-intro):has(.st-key-hb_docs_form)
    > [data-testid="stColumn"] {
        align-self: flex-start !important;
        justify-content: flex-start !important;
    }

    [data-testid="stHorizontalBlock"]:has(.hb-docs-intro):has(.st-key-hb_docs_form)
    > [data-testid="stColumn"] > [data-testid="stVerticalBlock"] {
        justify-content: flex-start !important;
    }

    .hb-doc-row {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 1rem 1.15rem;
        border-radius: 26px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-sm);
    }

    /* Keep each document action vertically aligned with its card without a
       brittle spacer that changes as soon as the row wraps. */
    [data-testid="stHorizontalBlock"]:has(.hb-doc-row) {
        align-items: center;
        margin-bottom: 0.75rem;
    }

    .st-key-prompt_clear_all_docs {
        margin-top: 0.25rem;
    }

    .hb-doc-icon {
        width: 44px;
        height: 44px;
        flex: none;
        border-radius: 50%;
        background: var(--color-bg);
        color: var(--color-accent);
        display: flex;
        align-items: center;
        justify-content: center;
    }

    .hb-doc-icon svg { display: block; }

    /* Add-a-document panel */
    .st-key-hb_docs_form {
        background: var(--color-surface);
        border-radius: 30px;
        padding: 1.5rem;
        box-shadow: var(--shadow-md);
    }

    .hr {
        height: 1px;
        border: 0;
        margin: 1.1rem 0;
        background: var(--color-divider);
    }

    .hb-doc-name {
        font-family: var(--font-heading);
        font-size: 1.05rem;
        font-weight: 400;
        line-height: 1.2;
    }

    .hb-doc-meta {
        color: var(--hb-muted);
        font-size: 0.75rem;
        line-height: 1.45;
        margin-top: 3px;
    }

    /* --- tags ---------------------------------------------------------- */

    .hb-tag {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 0.35rem 0.85rem;
        border-radius: 999px;
        font-size: 0.75rem;
        font-weight: 500;
        line-height: 1.1;
        white-space: nowrap;
    }

    .hb-tag svg { flex: none; }
    .hb-tag-neutral { background: var(--color-neutral-200); color: var(--color-neutral-800); }
    .hb-tag-accent { background: var(--color-accent-100); color: var(--color-accent-800); }
    .hb-tag-accent-2 { background: var(--color-accent-2-100); color: var(--color-accent-2-800); }

    /* --- tables -------------------------------------------------------- */

    .hb-table-wrap { margin-top: 0.6rem; }

    .hb-table-head {
        color: var(--hb-muted);
        font-size: 0.6875rem;
        font-weight: 600;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        padding-bottom: 0.55rem;
        border-bottom: 1px solid var(--color-divider);
    }

    .hb-table-cell {
        border-bottom: 1px solid color-mix(in srgb, var(--color-text) 8%, transparent);
        padding: 0.85rem 0;
        font-size: 0.9rem;
        color: var(--color-text);
        min-height: 3.1rem;
    }

    /* --- sign-in ------------------------------------------------------- */

    .hb-auth-shell { margin-top: 1.5rem; }

    .st-key-hb_auth_copy {
        background: var(--color-neutral-100);
        border-radius: 34px;
        padding: 2.75rem 2.5rem;
        box-shadow: var(--shadow-lg);
    }

    .st-key-hb_auth_hero {
        position: relative;
        background: var(--color-surface);
        border-radius: 34px;
        padding: 2.5rem 2.25rem;
        box-shadow: var(--shadow-lg);
        overflow: hidden;
    }

    .hb-auth-eyebrow {
        color: var(--color-accent-700);
        font-size: 0.6875rem;
        font-weight: 600;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        margin-bottom: 0.55rem;
    }

    .hb-auth-title {
        font-family: var(--font-heading);
        font-weight: 400;
        font-size: 2.6rem;
        line-height: 1.1;
        letter-spacing: -0.015em;
        margin-bottom: 0.9rem;
        max-width: 18ch;
    }

    .hb-auth-hero-title {
        font-family: var(--font-heading);
        font-weight: 400;
        font-size: 1.85rem;
        line-height: 1.15;
        margin-bottom: 1.1rem;
        max-width: 22ch;
    }

    .hb-auth-copy {
        color: color-mix(in srgb, var(--color-text) 72%, transparent);
        font-size: 1rem;
        line-height: 1.7;
        margin-bottom: 1.5rem;
        max-width: 52ch;
    }

    .hb-auth-note {
        font-size: 0.8rem;
        color: var(--hb-muted);
        margin-top: 1rem;
    }

    .hb-auth-feature {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 16px 18px;
        border-radius: 22px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-sm);
        font-size: 0.875rem;
        line-height: 1.45;
        margin-bottom: 0.7rem;
    }

    .hb-auth-feature svg { flex: none; margin-top: 1px; }

    /* Streamlit themes bare <a> with its own link color + underline, so the
       CTA has to win on both properties explicitly. */
    a.hb-signin-btn,
    a.hb-signin-btn:link,
    a.hb-signin-btn:visited {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        min-height: 52px;
        padding: 0 2.15rem;
        border-radius: 999px;
        background: var(--color-accent);
        color: var(--color-bg) !important;
        font-family: var(--font-heading);
        font-weight: 400;
        font-size: 1rem;
        text-decoration: none !important;
        box-shadow: var(--shadow-md);
    }

    a.hb-signin-btn:hover {
        background: var(--color-accent-600);
        color: var(--color-bg) !important;
        text-decoration: none !important;
    }

    /* --- Streamlit widgets --------------------------------------------- */

    .stButton > button,
    .stFormSubmitButton > button {
        border-radius: 999px;
        border: 1px solid var(--color-divider);
        background: transparent;
        color: var(--color-text);
        font-family: var(--font-heading);
        font-weight: 400;
        min-height: 2.75rem;
    }

    .stButton > button:hover,
    .stFormSubmitButton > button:hover {
        background: color-mix(in srgb, var(--color-text) 7%, transparent);
        border-color: var(--color-divider);
        color: var(--color-text);
    }

    /* Form submits report kind="primaryFormSubmit", so match on a prefix
       rather than an exact value or the composer's Send stays outlined. */
    .stButton > button[kind^="primary"],
    .stFormSubmitButton > button[kind^="primary"] {
        background: var(--color-accent);
        color: var(--color-bg);
        border-color: var(--color-accent);
        box-shadow: var(--shadow-md);
    }

    .stButton > button[kind^="primary"]:hover,
    .stFormSubmitButton > button[kind^="primary"]:hover {
        background: var(--color-accent-600);
        border-color: var(--color-accent-600);
        color: var(--color-bg);
    }

    /* The composer is a single pill row in the design, so the form wrapper
       itself carries no chrome. */
    [data-testid="stForm"] {
        border: 0;
        padding: 0;
        background: transparent;
    }

    .stTextInput input,
    .stTextArea textarea,
    .stDateInput input,
    .stSelectbox [data-baseweb="select"] > div,
    .stFileUploader section {
        border-radius: 999px !important;
        border-color: var(--color-divider) !important;
        background: var(--color-neutral-100) !important;
        color: var(--color-text) !important;
    }

    .stTextArea textarea,
    .stFileUploader section {
        border-radius: 22px !important;
    }

    .stTextInput input:focus,
    .stTextArea textarea:focus {
        border-color: var(--color-accent) !important;
        box-shadow: none !important;
    }

    button:focus-visible,
    a:focus-visible,
    input:focus-visible,
    textarea:focus-visible,
    [role="combobox"]:focus-visible {
        outline: 2px solid var(--color-accent) !important;
        outline-offset: 2px !important;
    }

    .stTextInput input::placeholder,
    .stTextArea textarea::placeholder {
        color: var(--hb-muted) !important;
        opacity: 1 !important;
    }

    .stFileUploader section,
    .stFileUploader section *,
    .stFileUploader small {
        color: var(--color-text) !important;
    }

    label, .stMarkdown, .stCaption, [data-testid="stWidgetLabel"] p {
        color: var(--color-text) !important;
    }

    /* Selectbox / date picker portals render outside the styled container, so they need
       their own explicit background + text color to avoid inheriting the browser/OS dark theme. */
    div[data-baseweb="popover"] { background: var(--color-neutral-100) !important; }

    div[data-baseweb="popover"] *,
    div[data-baseweb="calendar"] *,
    div[role="listbox"] * {
        color: var(--color-text) !important;
    }

    div[data-baseweb="popover"] li:hover,
    div[role="option"]:hover {
        background: var(--color-accent-100) !important;
    }

    [data-testid="stExpander"] details {
        border: 0;
        border-radius: 26px;
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-sm);
    }

    [data-testid="stExpander"] summary { border-radius: 26px; }

    .stChatMessage {
        background: transparent;
        border: 0;
        color: var(--color-text);
        padding-left: 0;
        padding-right: 0;
    }

    .stChatMessage * { color: var(--color-text); }

    .stAlert { border-radius: 22px; }

    /* --- responsive shell --------------------------------------------- */

    @media (max-width: 900px) {
        .block-container {
            max-width: none;
            padding: 0.9rem !important;
        }

        /* Streamlit keeps the desktop rail/content columns side by side at
           widths where the rail is already too narrow. Stack the two app
           regions, then turn the rail itself into a compact navigation bar. */
        [data-testid="stHorizontalBlock"]:has(.st-key-hb_rail) {
            flex-direction: column !important;
            gap: 0.75rem !important;
        }

        [data-testid="stHorizontalBlock"]:has(.st-key-hb_rail)
        > [data-testid="stColumn"] {
            width: 100% !important;
            flex: 1 1 100% !important;
        }

        .st-key-hb_rail {
            display: grid !important;
            grid-template-columns: repeat(3, minmax(0, 1fr));
            gap: 0.45rem !important;
            padding: 0.85rem !important;
            border-radius: 24px;
        }

        .st-key-hb_rail > .stElementContainer:has(.hb-brand) {
            grid-column: 1 / -1;
        }

        .st-key-hb_rail .hb-brand {
            margin-bottom: 0.1rem;
        }

        .st-key-hb_rail .hb-brand-sub,
        .st-key-hb_rail .hb-rail-label,
        .st-key-hb_rail > .stElementContainer:has(.hb-ground-card),
        .st-key-hb_rail > .stElementContainer:has(.hb-account-chip),
        .st-key-hb_rail > .st-key-rail_manage_docs,
        .st-key-hb_rail > .st-key-rail_sign_out {
            margin-top: 1rem;
            display: none !important;
        }

        [class*="st-key-nav_"] > div > button {
            justify-content: center;
            gap: 6px;
            min-height: 42px;
            padding: 0 0.45rem;
            font-size: 0.78rem !important;
        }

        [class*="st-key-nav_"] > div > button > div,
        [class*="st-key-nav_"] > div > button > div > span,
        [class*="st-key-nav_"] > div > button p {
            justify-content: center !important;
            text-align: center !important;
        }

        .st-key-hb_shell {
            padding: 1rem !important;
            border-radius: 24px;
        }

        .hb-topbar {
            min-height: 46px;
            gap: 8px;
            margin-bottom: 0.85rem;
            padding-bottom: 0.75rem;
        }

        .hb-page-title { font-size: 2.15rem; }

        .hb-bubble-user {
            max-width: 82%;
            padding: 13px 18px;
            border-radius: 22px 22px 6px 22px;
        }

        .hb-msg > .hb-msg-avatar {
            display: none;
        }

        .hb-bubble-safety,
        .hb-chat-status {
            width: 100%;
            min-width: 0;
            max-width: 100%;
            border-radius: 6px 22px 22px 22px;
        }

        [class*="st-key-hb_answer_"] {
            width: 100%;
            max-width: 100%;
            margin-left: 0;
            padding: 16px 18px 24px;
            border-radius: 6px 22px 22px 22px;
        }

        [class*="st-key-hb_answer_"]::before {
            display: none;
        }

        /* Starter prompts stay one compact, horizontally scrollable row. */
        [data-testid="stHorizontalBlock"]:has([class*="st-key-suggest_"]) {
            flex-direction: row !important;
            flex-wrap: nowrap !important;
            gap: 0.5rem !important;
            overflow-x: auto;
            padding-bottom: 0.25rem;
            scrollbar-width: none;
        }

        [data-testid="stHorizontalBlock"]:has([class*="st-key-suggest_"])
        > [data-testid="stColumn"] {
            width: auto !important;
            min-width: max-content;
            flex: 0 0 auto !important;
        }

        /* Keep the chat field and Send action together as one composer row. */
        [data-testid="stForm"]:has(input[aria-label="Ask HomeBuddy"])
        [data-testid="stHorizontalBlock"] {
            flex-direction: row !important;
            flex-wrap: nowrap !important;
            align-items: flex-end !important;
            gap: 0.5rem !important;
        }

        [data-testid="stForm"]:has(input[aria-label="Ask HomeBuddy"])
        [data-testid="stHorizontalBlock"] > [data-testid="stColumn"]:first-child {
            width: auto !important;
            min-width: 0;
            flex: 1 1 auto !important;
        }

        [data-testid="stForm"]:has(input[aria-label="Ask HomeBuddy"])
        [data-testid="stHorizontalBlock"] > [data-testid="stColumn"]:last-child {
            width: 72px !important;
            flex: 0 0 72px !important;
        }

        .hb-doc-row {
            align-items: flex-start;
            flex-wrap: wrap;
        }

        .hb-auth-title {
            font-size: clamp(2.05rem, 9.5vw, 2.6rem);
        }
    }

    @media (max-width: 640px) {
        .st-key-hb_auth_copy,
        .st-key-hb_auth_hero {
            padding: 2rem 1.5rem;
            border-radius: 28px;
        }

        .hb-auth-shell { margin-top: 0.35rem; }
        .hb-auth-hero-title { font-size: 1.6rem; }
        .hb-auth-feature { padding: 14px 16px; }

        .hb-topbar .hb-crumb,
        .hb-topbar > svg {
            display: none;
        }

        .hb-topbar-spacer { margin-left: 0; }
        .hb-topbar .hb-tag { margin-left: auto; }

        .hb-chat-note {
            align-items: flex-start;
            padding: 0.8rem 0.95rem;
        }

        .hb-safety-head { padding: 12px 18px; }
        .hb-safety-body { padding: 16px 18px 18px; }
        .hb-step { padding: 10px 12px; }

        [data-testid="stHorizontalBlock"]:has(.hb-doc-row) {
            flex-direction: row !important;
            flex-wrap: nowrap !important;
        }

        [data-testid="stHorizontalBlock"]:has(.hb-doc-row)
        > [data-testid="stColumn"]:first-child {
            width: auto !important;
            min-width: 0;
            flex: 1 1 auto !important;
        }

        [data-testid="stHorizontalBlock"]:has(.hb-doc-row)
        > [data-testid="stColumn"]:last-child {
            width: 82px !important;
            flex: 0 0 82px !important;
        }
    }

    @media (prefers-reduced-motion: reduce) {
        .hb-dots span {
            animation: none;
        }
    }

    .hb-indexing-overlay {
        position: fixed;
        inset: 0;
        z-index: 999999;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 1.5rem;
        background: color-mix(in srgb, var(--color-neutral-900) 48%, transparent);
        backdrop-filter: grayscale(1) blur(2px);
        cursor: wait;
        pointer-events: all;
    }

    .hb-indexing-modal {
        width: min(390px, calc(100vw - 3rem));
        padding: 2rem;
        border: 1px solid var(--color-divider);
        border-radius: var(--radius-lg);
        background: var(--color-neutral-100);
        box-shadow: var(--shadow-lg);
        text-align: center;
    }

    .hb-indexing-spinner {
        width: 42px;
        height: 42px;
        margin: 0 auto 1.1rem;
        border: 4px solid var(--color-accent-200);
        border-top-color: var(--color-accent);
        border-radius: 50%;
        animation: hb-indexing-spin 0.8s linear infinite;
    }

    .hb-indexing-title {
        margin-bottom: 0.45rem;
        font-family: var(--font-heading);
        font-size: 1.65rem;
    }

    .hb-indexing-copy {
        color: var(--hb-muted);
        font-size: 0.9rem;
        line-height: 1.5;
    }

    @keyframes hb-indexing-spin {
        to { transform: rotate(360deg); }
    }
</style>
""", unsafe_allow_html=True)

# ---------------------------------------------------------------------------
# State init
# ---------------------------------------------------------------------------

if "pending_question" not in st.session_state:
    st.session_state.pending_question = None
if "agent_messages" not in st.session_state:
    st.session_state.agent_messages = []
if "last_agent_turn" not in st.session_state:
    st.session_state.last_agent_turn = None
if "session_id" not in st.session_state:
    # Unique per browser session so chat threads don't merge across users/devices.
    # DEMO_SESSION_ID remains available to pin a stable thread for demos.
    st.session_state.session_id = os.getenv("DEMO_SESSION_ID") or str(uuid.uuid4())
if "doc_count" not in st.session_state:
    st.session_state.doc_count = 0
if "indexed_docs" not in st.session_state:
    st.session_state.indexed_docs = []
if "active_entry_id" not in st.session_state:
    st.session_state.active_entry_id = None
if "pending_document_delete_entry_id" not in st.session_state:
    st.session_state.pending_document_delete_entry_id = None
if "pending_document_delete_label" not in st.session_state:
    st.session_state.pending_document_delete_label = None
if "confirm_clear_all_docs" not in st.session_state:
    st.session_state.confirm_clear_all_docs = False
if "docs_status" not in st.session_state:
    st.session_state.docs_status = None
if "docs_status_level" not in st.session_state:
    st.session_state.docs_status_level = None
if "active_household_id" not in st.session_state:
    st.session_state.active_household_id = None
if "active_household_zip" not in st.session_state:
    st.session_state.active_household_zip = None
if "pending_task_draft" not in st.session_state:
    st.session_state.pending_task_draft = None
if "edit_task_draft" not in st.session_state:
    st.session_state.edit_task_draft = False
if "task_draft_status" not in st.session_state:
    st.session_state.task_draft_status = None
if "task_draft_status_level" not in st.session_state:
    st.session_state.task_draft_status_level = None
if "editing_task_id" not in st.session_state:
    st.session_state.editing_task_id = None
if "tasks_status" not in st.session_state:
    st.session_state.tasks_status = None
if "tasks_status_level" not in st.session_state:
    st.session_state.tasks_status_level = None
if "pending_case_draft" not in st.session_state:
    st.session_state.pending_case_draft = None
if "edit_case_draft" not in st.session_state:
    st.session_state.edit_case_draft = False
if "case_draft_status" not in st.session_state:
    st.session_state.case_draft_status = None
if "case_draft_status_level" not in st.session_state:
    st.session_state.case_draft_status_level = None
if "auth_token" not in st.session_state:
    st.session_state.auth_token = None
if "id_token" not in st.session_state:
    st.session_state.id_token = None
if "access_token" not in st.session_state:
    st.session_state.access_token = None
if "auth_signed_in" not in st.session_state:
    st.session_state.auth_signed_in = False
if "current_user" not in st.session_state:
    st.session_state.current_user = None
if "auth_error" not in st.session_state:
    st.session_state.auth_error = None
if "processed_auth_code" not in st.session_state:
    st.session_state.processed_auth_code = None
if "editing_case_id" not in st.session_state:
    st.session_state.editing_case_id = None
if "cases_status" not in st.session_state:
    st.session_state.cases_status = None
if "cases_status_level" not in st.session_state:
    st.session_state.cases_status_level = None
if "households" not in st.session_state:
    st.session_state.households = []
if "active_view" not in st.session_state:
    st.session_state.active_view = "chat"
if "chat_processing" not in st.session_state:
    st.session_state.chat_processing = False
if "pending_chat_question" not in st.session_state:
    st.session_state.pending_chat_question = None
if "document_indexing" not in st.session_state:
    st.session_state.document_indexing = False
if "pending_document_index" not in st.session_state:
    st.session_state.pending_document_index = None
if "auth_storage_action" not in st.session_state:
    st.session_state.auth_storage_action = "read"
if "logout_after_auth_clear" not in st.session_state:
    st.session_state.logout_after_auth_clear = False

st.session_state.doc_count = len(st.session_state.indexed_docs)
if not HOME_OPERATIONS_ENABLED:
    st.session_state.pending_task_draft = None
    st.session_state.edit_task_draft = False
    st.session_state.task_draft_status = None
    st.session_state.task_draft_status_level = None
    st.session_state.editing_task_id = None
    st.session_state.tasks_status = None
    st.session_state.tasks_status_level = None
    st.session_state.pending_case_draft = None
    st.session_state.edit_case_draft = False
    st.session_state.case_draft_status = None
    st.session_state.case_draft_status_level = None
    st.session_state.editing_case_id = None
    st.session_state.cases_status = None
    st.session_state.cases_status_level = None

if str(st.query_params.get("logout", "")).strip() == "1":
    logout_url = build_cognito_logout_url()
    if logout_url:
        safe_logout_url = html.escape(logout_url, quote=True)
        st.markdown('<p class="main-header">HomeBuddy</p>', unsafe_allow_html=True)
        st.markdown(
            '<p class="sub-header">Signing you out of HomeBuddy and Cognito.</p>',
            unsafe_allow_html=True,
        )
        st.markdown(
            f"""
            <meta http-equiv="refresh" content="0;url={safe_logout_url}">
            <script>
            window.top.location.replace({json.dumps(logout_url)});
            </script>
            """,
            unsafe_allow_html=True,
        )
        st.link_button("Continue Sign Out", logout_url, use_container_width=True)
        st.stop()
    del st.query_params["logout"]
handle_cognito_callback()

# Handle the one-time OAuth callback before mounting the browser-storage
# component. Its initial value can trigger a Streamlit rerun, which must never
# interrupt an authorization-code exchange.
auth_storage_action = st.session_state.auth_storage_action
auth_storage_result = sync_browser_auth_storage(
    auth_storage_action,
    st.session_state.access_token if auth_storage_action == "write" else None,
)
auth_storage_ready = bool(
    isinstance(auth_storage_result, dict)
    and auth_storage_result.get("ready")
    and auth_storage_result.get("action") == auth_storage_action
)

if auth_storage_action == "clear":
    if not auth_storage_ready:
        st.markdown("Signing out…")
        st.stop()
    st.session_state.auth_storage_action = "read"
    if st.session_state.logout_after_auth_clear:
        st.session_state.logout_after_auth_clear = False
        st.query_params["logout"] = "1"
    st.rerun()

if auth_storage_action == "write" and auth_storage_ready:
    st.session_state.auth_storage_action = "read"
    st.rerun()

# A browser refresh creates a new Streamlit session. Restore the access token
# from the local component before deciding whether to show the sign-in screen.
if not st.session_state.auth_signed_in and not st.query_params.get("code"):
    if not auth_storage_ready:
        st.stop()
    stored_access_token = auth_storage_result.get("access_token")
    if stored_access_token:
        if jwt_is_expired(stored_access_token):
            st.session_state.auth_error = "Your session expired. Please sign in again."
            st.session_state.auth_storage_action = "clear"
            st.rerun()
        st.session_state.auth_token = stored_access_token
        st.session_state.access_token = stored_access_token
        st.session_state.auth_signed_in = True

# Resolve auth-backed user and household state before rendering the main app.
if st.session_state.auth_signed_in:
    try:
        st.session_state.current_user = load_current_user()
        st.session_state.households = get_json("/households")
    except BackendRequestError as exc:
        if exc.status_code not in {401, 403}:
            st.error(f"HomeBuddy could not restore your workspace: {exc}")
            st.info("Your sign-in is still saved. Refresh to try again.")
            st.stop()
        st.session_state.auth_signed_in = False
        st.session_state.auth_token = None
        st.session_state.id_token = None
        st.session_state.access_token = None
        st.session_state.current_user = None
        st.session_state.households = []
        st.session_state.auth_error = _format_session_restore_error(exc)
        st.session_state.auth_storage_action = "clear"
        st.rerun()
    except Exception as exc:
        # Network and backend availability failures must not silently sign the user out.
        st.error(f"HomeBuddy could not restore your workspace: {exc}")
        st.info("Your sign-in is still saved. Refresh to try again.")
        st.stop()

households = st.session_state.households

if households and st.session_state.active_household_id is None:
    st.session_state.active_household_id = households[0]["id"]
    st.session_state.active_household_zip = households[0]["zip_code"]

if not st.session_state.auth_signed_in:
    render_sign_in_screen()
    st.stop()

if not households:
    render_household_setup_screen()
    st.stop()

# ---------------------------------------------------------------------------
# Main layout
# ---------------------------------------------------------------------------

nav_options = {
    "chat": "Chat",
    "docs": "Save Docs",
    "profile": "My Profile",
}
if HOME_OPERATIONS_ENABLED:
    nav_options.update(
        {
            "cases": "My Cases",
            "tasks": "My Tasks",
        }
    )

if st.session_state.active_view not in nav_options:
    st.session_state.active_view = "chat"

current_user = st.session_state.current_user or {}
account_email = current_user.get("email") or "your account"
monogram = initials(current_user)
household_name = households[0]["name"] if households else "Your household"

# One read here feeds both the rail's grounding card and the top bar's tag.
try:
    grounded_docs = load_saved_documents()
except Exception:
    grounded_docs = []
doc_count = len(grounded_docs)
doc_noun = "document" if doc_count == 1 else "documents"

if st.session_state.document_indexing:
    render_document_indexing_overlay()

nav_col, content_col = st.columns(
    [0.95, 3.55],
    gap="large",
    vertical_alignment="top",
)

with nav_col:
    with st.container(key="hb_rail"):
        st.markdown(
            f"""
            <div class="hb-brand">
                <div class="hb-brand-mark">{icon("home", 20)}</div>
                <div>
                    <div class="hb-brand-name">HomeBuddy</div>
                    <div class="hb-brand-sub">{esc(household_name)}</div>
                </div>
            </div>
            <div class="hb-rail-label">Workspace</div>
            """,
            unsafe_allow_html=True,
        )

        for view_key, label in nav_options.items():
            button_type = "primary" if st.session_state.active_view == view_key else "secondary"
            if st.button(
                label,
                key=f"nav_{view_key}",
                use_container_width=True,
                type=button_type,
                disabled=st.session_state.document_indexing,
            ):
                st.session_state.active_view = view_key
                st.rerun()

        grounding_copy = (
            f"{doc_count} {doc_noun} indexed and ready to cite."
            if doc_count
            else "No documents indexed yet — add one to ground answers."
        )
        st.markdown(
            f"""
            <div class="hb-ground-card">
                <div class="hb-ground-kicker">Grounding</div>
                <div class="hb-ground-body">{esc(grounding_copy)}</div>
            </div>
            """,
            unsafe_allow_html=True,
        )
        if st.button(
            "Manage docs",
            key="rail_manage_docs",
            use_container_width=True,
            disabled=st.session_state.document_indexing,
        ):
            st.session_state.active_view = "docs"
            st.rerun()

        st.markdown(
            f"""
            <div class="hb-account-chip">
                <div class="hb-avatar">{esc(monogram)}</div>
                <div class="hb-account-email">{esc(account_email)}</div>
            </div>
            """,
            unsafe_allow_html=True,
        )
        if st.button(
            "Sign out",
            key="rail_sign_out",
            use_container_width=True,
            disabled=st.session_state.document_indexing,
        ):
            sign_out()
            st.rerun()

with content_col:
    with st.container(key="hb_shell"):
        st.markdown(
            f"""
            <div class="hb-topbar">
                <span class="hb-crumb">Household</span>
                {icon("chevron", 16, "color-mix(in srgb, var(--color-text) 40%, transparent)")}
                <span class="hb-crumb-current">{esc(nav_options[st.session_state.active_view])}</span>
                <span class="hb-topbar-spacer"></span>
                <span class="hb-tag hb-tag-accent-2">{icon("check", 14)}{doc_count} docs grounded</span>
            </div>
            """,
            unsafe_allow_html=True,
        )
        if st.session_state.active_view == "docs":
            render_docs_tab()
        elif st.session_state.active_view == "chat":
            render_chat_tab()
        elif st.session_state.active_view == "cases" and HOME_OPERATIONS_ENABLED:
            if st.session_state.cases_status:
                if st.session_state.cases_status_level == "success":
                    st.success(st.session_state.cases_status)
                elif st.session_state.cases_status_level == "error":
                    st.error(st.session_state.cases_status)
                else:
                    st.info(st.session_state.cases_status)
            render_cases_tab()
        elif st.session_state.active_view == "tasks" and HOME_OPERATIONS_ENABLED:
            if st.session_state.tasks_status:
                if st.session_state.tasks_status_level == "success":
                    st.success(st.session_state.tasks_status)
                elif st.session_state.tasks_status_level == "error":
                    st.error(st.session_state.tasks_status)
                else:
                    st.info(st.session_state.tasks_status)
            render_tasks_tab()
        else:
            render_profile_tab()
