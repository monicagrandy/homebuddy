from datetime import date, datetime
from typing import Optional

from pydantic import BaseModel, EmailStr, Field, field_validator


class CitationOut(BaseModel):
    """A saved document an answer drew on, so clients can show and open it."""

    entry_id: str
    display_name: str
    page: Optional[int] = None
    doc_type: Optional[str] = None
    url: Optional[str] = None


class CaseDraft(BaseModel):
    title: str
    summary: str
    severity: str
    contractor_trade: Optional[str] = None

class TaskDraft(BaseModel):
    title: str
    notes: Optional[str] = None
    schedule_hint: Optional[str] = None

class ContractorSuggestion(BaseModel):
    business_name: str
    trade: str
    rating: Optional[float] = None
    review_count: Optional[int] = None
    phone: Optional[str] = None
    url: Optional[str] = None
    reason_suggested: str
    provider: str
    source_attribution: Optional[str] = None
    online_submission: bool = False

class QueryRequest(BaseModel):
    question: str
    session_id: str
    entry_id: Optional[str] = None
    response_mode: str = "web"
    household_id: Optional[int] = None
    asset_id: Optional[int] = None
    household_zip_code: Optional[str] = None

class QueryResponse(BaseModel):
    answer: str
    steps: list[str] = Field(default_factory=list, deprecated=True)
    route: list[str] = Field(default_factory=list)
    route_confidence: float = 0.0
    route_explanation: Optional[str] = None
    retrieval_context: list[str] = Field(default_factory=list)
    urgency_level: Optional[str] = None
    should_escalate: bool = False
    case_draft: Optional[CaseDraft] = None
    task_draft: Optional[TaskDraft] = None
    contractor_suggestions: list[ContractorSuggestion] = Field(default_factory=list)
    sources: list[CitationOut] = Field(default_factory=list)

class DocumentJobOut(BaseModel):
    """State of a background indexing job (POST /documents/jobs, GET /documents/jobs/{job_id})."""

    job_id: Optional[str] = None
    entry_id: str
    # queued | running | succeeded | failed, or duplicate when nothing was queued.
    status: str
    duplicate_of: Optional[str] = None
    error_code: Optional[str] = None
    error: Optional[str] = None
    chunks_indexed: Optional[int] = None
    attempts: int = 0


class DocumentIndexResponse(BaseModel):
    entry_id: str
    chunks_indexed: int

    model_config = {"from_attributes": True}


class DocumentOut(BaseModel):
    id: int
    entry_id: str
    display_name: str
    download_url: Optional[str] = None
    doc_type: str
    uploaded_at: datetime | None = None
    # "indexing" while a background job is still building its search index.
    status: str = "ready"

    model_config = {"from_attributes": True}


class AssetBase(BaseModel):
    name: str
    brand: Optional[str] = None
    model_number: Optional[str] = None
    room: Optional[str] = None
    install_date: Optional[date] = None
    warranty_end_date: Optional[date] = None


class AssetCreate(AssetBase):
    household_id: int


class AssetOut(AssetBase):
    id: int
    household_id: int

    model_config = {"from_attributes": True}


class CaseBase(BaseModel):
    title: str
    summary: str
    severity: str
    contractor_trade: Optional[str] = None


class CaseCreate(CaseBase):
    household_id: int
    asset_id: Optional[int] = None


class CaseUpdate(BaseModel):
    title: str
    summary: str
    severity: str
    contractor_trade: Optional[str] = None
    status: str


class CaseOut(CaseBase):
    id: int
    status: str

    model_config = {"from_attributes": True}


class TaskBase(BaseModel):
    title: str
    notes: Optional[str] = None
    due_date: Optional[date] = None


class TaskCreate(TaskBase):
    household_id: int
    asset_id: Optional[int] = None


class TaskUpdate(BaseModel):
    title: str
    notes: str
    due_date: Optional[date] = None
 

class TaskOut(TaskBase):
    id: int
    status: str

    model_config = {"from_attributes": True}


class ClientConfigOut(BaseModel):
    """Read by the native apps at startup, before sign-in."""

    min_supported: dict[str, str]
    retention_days: int
    features: dict[str, bool]


class UserOut(BaseModel):
    id: int
    cognito_sub: str
    email: EmailStr
    display_name: Optional[str] = None

    model_config = {"from_attributes": True}

class AuthBootstrapRequest(BaseModel):
    id_token: str
    access_token: str

class AuthCodeExchangeRequest(BaseModel):
    code: str


class AuthRefreshRequest(BaseModel):
    refresh_token: str


class AuthCodeExchangeResponse(BaseModel):
    id_token: str
    access_token: str
    expires_in: int
    token_type: str
    refresh_token: Optional[str] = None


class ConversationOut(BaseModel):
    """A chat thread; `id` is the session_id the client used to start it."""

    id: str
    household_id: int
    title: str
    created_at: datetime
    updated_at: datetime


class ConversationMessageOut(BaseModel):
    id: int
    household_id: int
    session_id: str
    role: str
    content: str
    created_at: datetime | None = None
    # Answers only: what the live answer cited and how urgent it was. Empty for older answers.
    sources: list[CitationOut] = Field(default_factory=list)
    urgency_level: Optional[str] = None
    should_escalate: bool = False

    model_config = {"from_attributes": True}

    @field_validator("sources", mode="before")
    @classmethod
    def _no_sources_saved(cls, value):
        return value or []


class HouseholdCreate(BaseModel):
    name: str
    zip_code: str
    home_type: str


class HouseholdOut(BaseModel):
    id: int
    name: str
    zip_code: str
    home_type: str
    role: str


class HouseholdInvitationCreate(BaseModel):
    household_id: int
    email: EmailStr
    role: str = "member"


class HouseholdInvitationOut(BaseModel):
    id: int
    household_id: int
    email: EmailStr
    role: str
    status: str
    token: str
    created_at: datetime | None = None
    accepted_at: datetime | None = None

    model_config = {"from_attributes": True}
