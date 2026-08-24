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


def bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


HOME_OPERATIONS_ENABLED = bool_env("HOME_OPERATIONS_ENABLED", False)

def esc(value) -> str:
    return html.escape(str(value or ""))

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
        raise RuntimeError(_read_backend_error(response))
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
    lines = messages or ["Thinking..."]
    body = "<br>".join(esc(line) for line in lines)
    return (
        '<div class="hb-chat-status">'
        '<div class="hb-chat-status-title">Processing</div>'
        f'<div class="hb-chat-status-body">{body}</div>'
        "</div>"
    )


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
        return

    code = st.query_params.get("code")
    if not code:
        return

    code = str(code)
    if st.session_state.get("processed_auth_code") == code:
        clear_auth_query_params()
        return

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
        st.session_state.auth_error = None
        st.session_state.processed_auth_code = code
        clear_auth_query_params()
        st.rerun()
    except Exception as exc:
        st.session_state.auth_signed_in = False
        st.session_state.auth_token = None
        st.session_state.access_token = None
        st.session_state.id_token = None
        st.session_state.current_user = None
        st.session_state.auth_error = _format_auth_error(exc)
        st.session_state.processed_auth_code = code
        clear_auth_query_params()


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
    clear_auth_query_params()
    st.query_params["logout"] = "1"
    return True


def render_sign_in_screen() -> None:
    subtitle = (
        "Sign in with your invited beta account to continue to your documents, chat history, tasks, and cases."
        if COGNITO_ALLOWED_GROUPS
        else "Sign in to continue to your documents, chat history, tasks, and cases."
    )
    auth_eyebrow = "Private Beta" if COGNITO_ALLOWED_GROUPS else "Welcome Back"
    auth_copy = (
        "HomeBuddy is currently invite-only. Use the Cognito account you were invited with to get back to your saved documents, household activity, and assistant history."
        if COGNITO_ALLOWED_GROUPS
        else "HomeBuddy keeps your saved documents, household activity, and assistant history together so you can come back to the same workspace at any time."
    )
    st.markdown('<p class="main-header">HomeBuddy</p>', unsafe_allow_html=True)
    st.markdown(
        f'<p class="sub-header">{esc(subtitle)}</p>',
        unsafe_allow_html=True,
    )
    st.markdown('<div class="hb-auth-shell"></div>', unsafe_allow_html=True)
    with st.container(key="hb-auth-parent"):
        st.markdown(
            f"""
            <div class="hb-auth-eyebrow">{esc(auth_eyebrow)}</div>
            <div class="hb-auth-title">Sign in and pick up where you left off.</div>
            <div class="hb-auth-copy">
                {esc(auth_copy)}
            </div>
            """,
            unsafe_allow_html=True,
        )

        if st.session_state.get("auth_error"):
            st.error(st.session_state.auth_error)

        authorize_url = build_cognito_authorize_url()
        if authorize_url:
            st.markdown(
                f"""
                <a href="{authorize_url}" target="_self" style="
                    display: inline-block;
                    width: 100%;
                    text-align: center;
                    padding: 0.9rem 1rem;
                    border-radius: 999px;
                    background: #0f766e;
                    color: white;
                    text-decoration: none;
                    font-weight: 700;
                    margin: 3.5rem 0.5rem 3.5rem 0;
                ">Sign In</a>
                """,
                unsafe_allow_html=True,
            )
        else:
            st.error("Cognito sign-in is not fully configured. Check domain, client id, and redirect URI.")

        st.markdown(
            """
            <div class="hb-auth-hero">
                <div class="hb-auth-eyebrow">Inside HomeBuddy</div>
                <div class="hb-auth-title">Grounded answers, cleaner follow-through, less home chaos.</div>
                <div class="hb-auth-list">
                    • Save manuals, warranties, and receipts for grounded answers<br>
                    • Ask for troubleshooting, safety, and coverage help<br>
                    • Turn issues into cases, reminders, and next steps
                </div>
            </div>
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

def load_assets() -> list[dict]:
    return get_json(f"/assets?household_id={st.session_state.active_household_id}")

def update_case_record(case_id: int, case_payload: dict) -> dict:
    # Send edited case fields back to the backend.
    return put_json(f"/cases/{case_id}", case_payload)

def delete_case_record(case_id: int) -> None:
    # Remove a persisted case from the backend.
    delete_request(f"/cases/{case_id}")

def delete_asset(asset_id: int) -> None:
    delete_request(f"/assets/{asset_id}")

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
        '<div class="hb-page-subtitle">Your account details, household summary, and the assets Home Buddy is tracking.</div>',
        unsafe_allow_html=True,
    )

    user = st.session_state.current_user or {}
    user_left, user_right = st.columns([2, 1])
    with user_left:
        st.markdown("### Account")
        st.markdown(
            f"""
            <div class="hb-card">
                <div class="hb-card-title">{esc(user.get('display_name')) or 'HomeBuddy User'}</div>
                <div class="hb-card-body">
                    Email: {esc(user.get('email')) or '—'}<br>
                    User id: {esc(user.get('id')) or '—'}
                </div>
            </div>
            """,
            unsafe_allow_html=True,
        )
    with user_right:
        st.markdown("### Session")
        if st.button("Sign Out", key="sign_out_button", use_container_width=True):
            sign_out()
            st.rerun()

    if not st.session_state.households:
        st.info("No household is attached to this account yet.")
        return

    # Keep the household summary compact and easy to scan before dropping into the heavier asset-management section.
    household = st.session_state.households[0]
    summary_left, summary_right = st.columns([1.6, 1])
    with summary_left:
        st.markdown("### Household")
        st.markdown(
            f"""
            <div class="hb-card">
                <div class="hb-card-title">{esc(household['name'])}</div>
                <div class="hb-card-body">
                    Zip code: {esc(household.get('zip_code')) or '—'}<br>
                    Role: {esc(household.get('role')) or 'owner'}
                </div>
            </div>
            """,
            unsafe_allow_html=True,
        )
    with summary_right:
        st.markdown("### Active Household")
        st.markdown(
            f"""
            <div class="hb-stat-card">
                <div class="hb-stat-value">{st.session_state.active_household_id or '—'}</div>
                <div class="hb-stat-label">Household Id</div>
            </div>
            """,
            unsafe_allow_html=True,
        )

    st.markdown("### Household Assets")
    st.caption("Review each tracked appliance or home system, then add or remove assets as needed.")

    try:
        assets = load_assets()
    except Exception as exc:
        st.error(f"Could not load assets: {exc}")
        assets = []

    if assets:
        st.markdown('<div class="hb-table-wrap">', unsafe_allow_html=True)
        header_cols = st.columns([1.5, 1.2, 1.3, 1.0, 1.1, 1.2, 0.8])
        headers = ["Name", "Brand", "Model", "Room", "Installed", "Warranty", ""]
        for col, label in zip(header_cols, headers):
            col.markdown(f'<div class="hb-table-head">{label}</div>', unsafe_allow_html=True)

        for asset in assets:
            row_cols = st.columns([1.5, 1.2, 1.3, 1.0, 1.1, 1.2, 0.8])
            row_cols[0].markdown(f'<div class="hb-table-cell"><strong>{esc(asset["name"])}</strong></div>', unsafe_allow_html=True)
            row_cols[1].markdown(f'<div class="hb-table-cell">{esc(asset.get("brand")) or "—"}</div>', unsafe_allow_html=True)
            row_cols[2].markdown(f'<div class="hb-table-cell">{esc(asset.get("model_number")) or "—"}</div>', unsafe_allow_html=True)
            row_cols[3].markdown(f'<div class="hb-table-cell">{esc(asset.get("room")) or "—"}</div>', unsafe_allow_html=True)
            row_cols[4].markdown(f'<div class="hb-table-cell">{esc(asset.get("install_date")) or "—"}</div>', unsafe_allow_html=True)
            row_cols[5].markdown(f'<div class="hb-table-cell">{esc(asset.get("warranty_end_date")) or "—"}</div>', unsafe_allow_html=True)
            if row_cols[6].button("Delete", key=f"delete_asset_{asset['id']}", use_container_width=True):
                try:
                    # esc() is display-only — never escape ids or values sent back to the API.
                    delete_asset(asset["id"])
                    st.success(f"Deleted asset: {asset['name']}")
                    st.rerun()
                except Exception as exc:
                    st.error(f"Could not delete asset: {exc}")
        st.markdown('</div>', unsafe_allow_html=True)
    else:
        st.info("No assets added yet.")

    # Keep the creation form collapsed below the current list so the record-management view stays focused.
    with st.expander("Add a New Asset", expanded=not assets):
        with st.form("add_asset_form", clear_on_submit=True):
            form_left, form_right = st.columns(2)
            with form_left:
                name = st.text_input("Asset name", key="new_asset_name")
                brand = st.text_input("Brand", key="new_asset_brand")
                room = st.text_input("Room", key="new_asset_room")
            with form_right:
                model_number = st.text_input("Model number", key="new_asset_model_number")
                install_date = st.date_input("Install date", value=None, key="install_date")
                warranty_end_date = st.date_input("Warranty end date", value=None, key="warranty_end_date")

            add_new_asset = st.form_submit_button("Add asset", use_container_width=True)

    if add_new_asset:
        if not name.strip():
            st.warning("Enter an asset name.")
        else:
            try:
                post_json(
                    "/assets",
                    payload={
                        "name": name.strip(),
                        "household_id": st.session_state.active_household_id,
                        "brand": brand.strip() or None,
                        "model_number": model_number.strip() or None,
                        "room": room.strip() or None,
                        "install_date": install_date.isoformat() if install_date else None,
                        "warranty_end_date": warranty_end_date.isoformat() if warranty_end_date else None,
                    },
                )
                st.success(f"Saved asset: {name.strip()}")
                st.rerun()
            except Exception as exc:
                st.error(f"Could not save asset: {exc}")
     
def render_docs_tab() -> None:
    st.markdown('<div class="hb-page-kicker">Documents</div>', unsafe_allow_html=True)
    st.markdown('<div class="hb-page-title">Save Docs</div>', unsafe_allow_html=True)
    st.markdown(
        '<div class="hb-page-subtitle">Add manuals, warranties, permits, and receipts so Home Buddy can cite them when it answers.</div>',
        unsafe_allow_html=True,
    )

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

    left_col, right_col = st.columns([1.15, 1], gap="large")
    manual_count = sum(1 for entry in entries if entry["doc_type"] == "manual")
    coverage_count = len(entries) - manual_count

    with left_col:
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
                    <div class="hb-stat-value">{coverage_count}</div>
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
                    pretty_type = "Manual" if entry["doc_type"] == "manual" else "Warranty / Permit / Receipt"
                    st.markdown(
                        f"""
                        <div class="hb-card hb-compact-card">
                            <div class="hb-card-title">{esc(entry['display_name'])}</div>
                            <div class="hb-doc-meta">{pretty_type} · entry id: {esc(entry['entry_id'])}</div>
                        </div>
                        """,
                        unsafe_allow_html=True,
                    )
                with action_col:
                    st.markdown("<div style='height: 0.85rem;'></div>", unsafe_allow_html=True)
                    if st.button(
                        "Delete",
                        key=f"prompt_delete_doc_{entry['entry_id']}",
                        use_container_width=True,
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
            ):
                st.session_state.pending_document_delete_entry_id = None
                st.session_state.pending_document_delete_label = None
                st.rerun()

        if entries and st.button(
            "Clear All Saved Docs",
            key="prompt_clear_all_docs",
            use_container_width=True,
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
            ):
                st.session_state.confirm_clear_all_docs = False
                st.rerun()

    with right_col:
        st.markdown('<div class="hb-card hb-form-card">', unsafe_allow_html=True)
        st.markdown('<div class="hb-section-label">Add a Document</div>', unsafe_allow_html=True)
        st.markdown(
            '<div class="hb-card-meta" style="margin-bottom: 0.85rem;">Choose how you want to add the file, then Home Buddy will index it for retrieval.</div>',
            unsafe_allow_html=True,
        )

        with st.form("add_url_document_form", clear_on_submit=True):
            new_name = st.text_input("Document name", key="new_entry_name")
            new_url = st.text_input("PDF URL", key="new_download_url")
            selected_doc_type = st.selectbox(
                "Document type",
                options=["— select —"] + list(doc_types.keys()),
                key="url_doc_type",
            )
            add_url_document = st.form_submit_button("Add and index", use_container_width=True)

        with st.form("upload_document_form", clear_on_submit=True):
            uploaded_doc_name = st.text_input("Document name", key="upload_entry_name")
            uploaded_file = st.file_uploader("Upload a PDF directly", type="pdf")
            selected_upload_doc_type = st.selectbox(
                "Upload document type",
                options=["— select —"] + list(doc_types.keys()),
                key="upload_doc_type",
            )
            upload_document = st.form_submit_button("Upload and index", use_container_width=True)
        st.markdown('</div>', unsafe_allow_html=True)

    url_doc_type = doc_types.get(selected_doc_type)
    upload_doc_type = doc_types.get(selected_upload_doc_type)

    if add_url_document:
        if not new_name.strip() or not new_url.strip():
            st.warning("Enter both a document name and PDF URL.")
        else:
            doc_name = new_name.strip()
            doc_url = new_url.strip()
            doc_entry_id = build_entry_id(doc_name, entries, download_url=doc_url)
            try:
                with st.spinner("Indexing..."):
                    result = post_form(
                        "/documents/index",
                        data={
                            "household_id": st.session_state.active_household_id,
                            "entry_id": doc_entry_id,
                            "session_id": st.session_state.session_id,
                            "display_name": doc_name,
                            "url": doc_url,
                            "doc_type": url_doc_type,
                        },
                    )
                remember_indexed_doc(
                    display_name=doc_name,
                    entry_id=doc_entry_id,
                    chunks_indexed=result["chunks_indexed"],
                )
                st.success(f"Indexed {result['chunks_indexed']} chunks.")
                st.rerun()
            except Exception as exc:
                st.error(str(exc))

    if upload_document:
        if uploaded_file is None:
            st.warning("Choose a PDF file first.")
        else:
            doc_name = (uploaded_doc_name or "").strip() or uploaded_file.name
            doc_entry_id = build_entry_id(doc_name, entries)
            try:
                with st.spinner("Indexing..."):
                    result = post_form(
                        "/documents/index",
                        data={
                            "household_id": st.session_state.active_household_id,
                            "entry_id": doc_entry_id,
                            "session_id": st.session_state.session_id,
                            "display_name": doc_name,
                            "doc_type": upload_doc_type,
                        },
                        files={
                            "file": (
                                uploaded_file.name,
                                uploaded_file.getvalue(),
                                uploaded_file.type or "application/pdf",
                            )
                        },
                    )
                remember_indexed_doc(
                    display_name=doc_name,
                    entry_id=doc_entry_id,
                    chunks_indexed=result["chunks_indexed"],
                )
                st.success(f"Indexed {result['chunks_indexed']} chunks.")
                st.rerun()
            except Exception as exc:
                st.error(str(exc))

def render_chat_tab() -> None:
    st.markdown('<div class="hb-page-kicker">Assistant</div>', unsafe_allow_html=True)
    st.markdown('<div class="hb-page-title">Chat</div>', unsafe_allow_html=True)
    st.markdown(
        '<div class="hb-page-subtitle">Ask Home Buddy about troubleshooting, coverage, safety, reminders, and household operations.</div>',
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
    if saved_docs:
        st.markdown(
            '<div class="hb-chat-note">🤖 Home Buddy will use your saved documents and agent workflow to answer grounded questions.</div>',
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

    with st.form("agent_query_form", clear_on_submit=True):
        question = st.text_input(
            "Ask HomeBuddy",
            key="agent_input_top",
            placeholder="Try: Why is my tire pressure light on?",
            disabled=st.session_state.get("chat_processing", False),
        )
        submit_question = st.form_submit_button(
            "Send",
            use_container_width=True,
            disabled=st.session_state.get("chat_processing", False),
        )

    if submit_question and question.strip():
        st.session_state.pending_chat_question = question.strip()
        st.session_state.chat_processing = True
        st.rerun()

    pending_question = st.session_state.get("pending_chat_question")
    if pending_question:
        with st.chat_message("user", avatar="🧑‍💻"):
            st.markdown(pending_question)

        with st.chat_message("assistant", avatar="🤖"):
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
                }

                status_placeholder.empty()
                answer_placeholder.markdown(answer)
                render_contractor_suggestions(contractor_suggestions)
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

    latest_turn = st.session_state.get("last_agent_turn")
    conversation_pairs = build_display_conversation_pairs(
        st.session_state.agent_messages,
        latest_turn,
    )
    for index, (user_message, assistant_message) in enumerate(reversed(conversation_pairs)):
        if user_message:
            st.markdown(
                f"""
                <div class="hb-card hb-compact-card">
                    <div class="hb-partial-title">You Asked</div>
                    {esc(user_message)}
                </div>
                """,
                unsafe_allow_html=True,
            )
        if assistant_message:
            preview = re.sub(r"\s+", " ", assistant_message).strip()[:96] or "Assistant response"
            is_latest_turn = (
                latest_turn
                and user_message == latest_turn.get("question")
                and assistant_message == latest_turn.get("answer")
            )
            with st.expander(f"🤖 {preview}", expanded=index == 0):
                st.markdown(assistant_message)
                if is_latest_turn:
                    render_contractor_suggestions(
                        latest_turn.get("contractor_suggestions") or []
                    )
                    render_case_draft_hitl()
                    render_task_draft_hitl()

    if latest_turn and not conversation_pairs:
        st.markdown(
            f"""
            <div class="hb-card hb-compact-card">
                <div class="hb-partial-title">You Asked</div>
                {esc(latest_turn.get("question") or "")}
            </div>
            """,
            unsafe_allow_html=True,
        )
        with st.expander("🤖 Latest response", expanded=True):
            st.markdown(latest_turn.get("answer") or "")
            render_contractor_suggestions(
                latest_turn.get("contractor_suggestions") or []
            )
            render_case_draft_hitl()
            render_task_draft_hitl()
    elif latest_turn is None:
        render_case_draft_hitl()
        render_task_draft_hitl()

    if st.session_state.agent_messages:
        if st.button("🗑️ Clear agent conversation", key="clear_chat_button", use_container_width=True):
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
    @import url('https://fonts.googleapis.com/css2?family=Instrument+Serif&family=Inter:wght@400;500;600;700&display=swap');

    :root {
        --hb-bg: #f3f2f2;
        --hb-surface: #fbfaf9;
        --hb-card: #ffffff;
        --hb-border: #ddd7d2;
        --hb-text: #201e1d;
        --hb-muted: #6d6660;
        --hb-accent: #0088b0;
        --hb-accent-soft: #e2f3f8;
    }

    html, body, [class*="css"] {
        font-family: 'Inter', sans-serif;
        color: var(--hb-text);
    }

    [data-testid="stAppViewContainer"] {
        background: var(--hb-bg);
    }

    [data-testid="stHeader"] {
        background: transparent;
    }

    [data-testid="stSidebar"] {
        display: none;
    }

    .block-container {
        max-width: 1380px;
        padding-top: 2rem;
        padding-bottom: 2rem;
    }

    .main-header {
        font-family: 'Instrument Serif', serif;
        color: var(--hb-text);
        font-size: 3.5rem;
        margin: 0;
    }

    .sub-header {
        color: var(--hb-muted);
        font-size: 0.96rem;
        margin: 0.4rem 3.5 rem 0;
    }

    .hb-rule {
        width: 160px;
        height: 4px;
        background: var(--hb-accent);
        border-radius: 999px;
        margin: 0.85rem 0 1.1rem;
    }

    .hb-header-wrap {
        margin-bottom: 0.5rem;
    }

    /* Targeting the st-key-* class lets these rules land on the actual container that
       wraps the tab content / nav buttons, instead of an empty div from an unmatched
       open/close st.markdown pair (which rendered as its own hollow, floating box). */
    .st-key-hb_shell {
        background: var(--hb-surface);
        border: 1px solid var(--hb-border);
        border-radius: 28px;
        padding: 1.25rem;
        box-shadow: 0 10px 30px rgba(32, 30, 29, 0.06);
    }

    .st-key-hb_rail {
        background: #f7f4f1;
        border: 1px solid var(--hb-border);
        border-radius: 22px;
        padding: 1rem;
    }

    .hb-rail-title {
        font-family: 'Instrument Serif', serif;
        font-size: 1.7rem;
        margin-bottom: 0.25rem;
    }

    .hb-rail-subtitle {
        color: var(--hb-muted);
        font-size: 0.84rem;
        margin-bottom: 1rem;
    }

    .hb-page-kicker {
        color: var(--hb-muted);
        font-size: 0.76rem;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        margin-bottom: 0.35rem;
    }

    .hb-page-title {
        font-size: 1.9rem;
        font-weight: 600;
        margin-bottom: 0.25rem;
    }

    .hb-page-subtitle {
        color: var(--hb-muted);
        font-size: 0.96rem;
        margin-bottom: 1.5rem;
        max-width: 72ch;
    }

    .hb-section-label {
        color: var(--hb-muted);
        font-size: 0.72rem;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        margin: 0 0 0.65rem;
    }

    .hb-card {
        background: var(--hb-card);
        border: 1px solid var(--hb-border);
        border-radius: 18px;
        padding: 1rem 1.05rem;
        margin-bottom: 0.8rem;
        box-shadow: 0 6px 14px rgba(32, 30, 29, 0.04);
    }

    .hb-form-card {
        padding-bottom: 0.5rem;
    }

    .hb-compact-card {
        padding: 0.9rem 1rem;
    }

    .hb-empty-card {
        background: #fcfbfa;
    }

    .hb-card-title {
        font-size: 1rem;
        font-weight: 600;
        color: var(--hb-text);
        margin-bottom: 0.25rem;
    }

    .hb-card-body,
    .hb-card-meta {
        color: var(--hb-muted);
        font-size: 0.9rem;
        line-height: 1.45;
    }

    .hb-stat-card {
        background: var(--hb-card);
        border: 1px solid var(--hb-border);
        border-radius: 18px;
        padding: 1rem;
        text-align: center;
    }

    .hb-stat-value {
        font-size: 1.6rem;
        font-weight: 700;
        color: var(--hb-accent);
    }

    .hb-stat-label {
        font-size: 0.76rem;
        color: var(--hb-muted);
        text-transform: uppercase;
        letter-spacing: 0.06em;
    }

    .hb-stack-gap {
        margin-top: 1rem;
    }

    .hb-chat-note {
        background: #f8fbfc;
        border: 1px solid #d7ebf2;
        border-radius: 16px;
        padding: 0.9rem 1rem;
        color: var(--hb-muted);
        font-size: 0.92rem;
        margin-bottom: 1rem;
    }

    .hb-chat-status {
        background: #fcfbfa;
        border: 1px solid var(--hb-border);
        border-radius: 16px;
        padding: 0.9rem 1rem;
        color: var(--hb-text);
        margin-bottom: 1rem;
    }

    .hb-chat-status-title {
        color: var(--hb-muted);
        font-size: 0.75rem;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        margin-bottom: 0.45rem;
    }

    .hb-chat-status-body {
        color: var(--hb-muted);
        font-size: 0.92rem;
        line-height: 1.55;
    }

    .hb-partial-block {
        background: #fcfbfa;
        border: 1px solid var(--hb-border);
        border-radius: 16px;
        padding: 0.95rem 1rem;
        margin-bottom: 0.9rem;
    }

    .hb-partial-title {
        color: var(--hb-muted);
        font-size: 0.75rem;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        margin-bottom: 0.55rem;
    }

    .hb-doc-meta {
        color: var(--hb-muted);
        font-size: 0.84rem;
        line-height: 1.45;
    }
    
    .hb-auth-parent {
        align-content: center;
        width: 100vh;    
        height: 100vh;
    }

    .hb-auth-hero {
        background:
            radial-gradient(circle at top right, rgba(0, 136, 176, 0.16), transparent 34%),
            linear-gradient(135deg, #fffaf6 0%, #f2f7f8 100%);
        width: fit-content; 
        margin-left: auto;
        margin-right: auto;
        height: fit-content;
        border: 1px solid var(--hb-border);
        border-radius: 24px;
        padding: 1.25rem;
        box-shadow: 0 12px 30px rgba(32, 30, 29, 0.06);
    }

    .hb-auth-shell {
        margin-top: 3rem;
    }

    .hb-auth-panel {
        background: #fffdfb;
        border: 1px solid var(--hb-border);
        border-radius: 24px;
        padding: 1.35rem;
        min-height: 19rem;
        box-shadow: 0 12px 30px rgba(32, 30, 29, 0.06);
    }

    .hb-auth-eyebrow {
        color: var(--hb-muted);
        font-size: 0.74rem;
        font-weight: 700;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        margin-bottom: 0.55rem;
    }

    .hb-auth-title {
        font-family: 'Instrument Serif', serif;
        font-size: 2rem;
        line-height: 1.05;
        margin-bottom: 0.8rem;
    }

    .hb-auth-list {
        color: var(--hb-muted);
        font-size: 0.94rem;
        line-height: 1.6;
    }

    .hb-auth-copy {
        color: var(--hb-muted);
        font-size: 0.96rem;
        line-height: 1.65;
        margin-bottom: 1rem;
    }

    .hb-tag {
        display: inline-block;
        padding: 0.28rem 0.62rem;
        border-radius: 999px;
        font-size: 0.78rem;
        font-weight: 600;
        line-height: 1.1;
    }

    .hb-tag-neutral {
        background: #efebe8;
        color: #4f4842;
    }

    .hb-tag-accent {
        background: var(--hb-accent-soft);
        color: #086783;
    }

    .hb-table-wrap {
        margin-top: 0.6rem;
    }

    .hb-table-head {
        color: var(--hb-muted);
        font-size: 0.74rem;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        padding-bottom: 0.55rem;
    }

    .hb-table-cell {
        background: var(--hb-card);
        border-top: 1px solid #eee8e3;
        padding: 0.85rem 0;
        font-size: 0.92rem;
        color: var(--hb-text);
        min-height: 3.1rem;
    }

    .stButton > button,
    .stFormSubmitButton > button {
        border-radius: 14px;
        border: 1px solid var(--hb-border);
        background: #f6f1eb;
        color: var(--hb-text);
        font-weight: 600;
        min-height: 2.8rem;
    }

    .stButton > button[kind="primary"],
    .stFormSubmitButton > button[kind="primary"] {
        background: var(--hb-text);
        color: white;
        border-color: var(--hb-text);
    }

    .stTextInput input,
    .stTextArea textarea,
    .stDateInput input,
    .stSelectbox [data-baseweb="select"] > div,
    .stFileUploader section {
        border-radius: 14px !important;
        border-color: var(--hb-border) !important;
        background: #fff !important;
        color: var(--hb-text) !important;
    }

    .stTextInput input::placeholder,
    .stTextArea textarea::placeholder {
        color: var(--hb-muted) !important;
        opacity: 1 !important;
    }

    .stFileUploader section,
    .stFileUploader section * ,
    .stFileUploader small {
        color: var(--hb-text) !important;
    }

    label, .stMarkdown, .stCaption, [data-testid="stWidgetLabel"] p {
        color: var(--hb-text) !important;
    }

    /* Selectbox / date picker portals render outside the styled container, so they need
       their own explicit background + text color to avoid inheriting the browser/OS dark theme. */
    div[data-baseweb="popover"] {
        background: #fff !important;
    }

    div[data-baseweb="popover"] *,
    div[data-baseweb="calendar"] *,
    div[role="listbox"] * {
        color: var(--hb-text) !important;
    }

    div[data-baseweb="popover"] li:hover,
    div[role="option"]:hover {
        background: var(--hb-accent-soft) !important;
    }

    .stChatMessage {
        background: rgba(255, 255, 255, 0.72);
        border: 1px solid var(--hb-border);
        border-radius: 18px;
        color: var(--hb-text);
    }

    .stChatMessage * {
        color: var(--hb-text);
    }

    .stAlert {
        border-radius: 16px;
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

# Resolve auth-backed user and household state before rendering the main app.
if st.session_state.auth_signed_in:
    try:
        st.session_state.current_user = load_current_user()
        st.session_state.households = get_json("/households")
    except Exception as exc:
        # If auth resolution fails, drop back to the sign-in screen instead of rendering a broken app shell.
        st.session_state.auth_signed_in = False
        st.session_state.auth_token = None
        st.session_state.id_token = None
        st.session_state.access_token = None
        st.session_state.current_user = None
        st.session_state.households = []
        st.session_state.auth_error = _format_session_restore_error(exc)

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

st.markdown('<div class="hb-header-wrap">', unsafe_allow_html=True)
st.markdown('<p class="main-header">HomeBuddy</p>', unsafe_allow_html=True)
st.markdown('<div class="hb-rule"></div>', unsafe_allow_html=True)
st.markdown(
    '<p class="sub-header">A grounded assistant for troubleshooting, documents, safety, and household follow-through.</p>',
    unsafe_allow_html=True,
)
st.markdown('</div>', unsafe_allow_html=True)

nav_col, content_col = st.columns([0.95, 3.55], gap="large")

with nav_col:
    with st.container(key="hb_rail"):
        st.markdown('<div class="hb-rail-title">HomeBuddy</div>', unsafe_allow_html=True)
        st.markdown(
            f'<div class="hb-rail-subtitle">{esc((st.session_state.current_user or {}).get("email")) or "your account"}</div>',
            unsafe_allow_html=True,
        )

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

        for view_key, label in nav_options.items():
            button_type = "primary" if st.session_state.active_view == view_key else "secondary"
            if st.button(label, key=f"nav_{view_key}", use_container_width=True, type=button_type):
                st.session_state.active_view = view_key
                st.rerun()

with content_col:
    with st.container(key="hb_shell"):
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
